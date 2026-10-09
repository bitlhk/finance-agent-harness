package io.github.bitlhk.finance.harness.providers.customercrm;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerCrmProviderTest {
    @Test void declaresManagedReadOnlyCapability() {
        var factory = new CustomerCrmFactoryV01();
        assertEquals(CustomerCrmProvider.PROVIDER_ID, factory.providerId());
        assertEquals("customer-crm-readonly.v1", factory.connectionKind());
        assertEquals(Set.of(CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.ALLOCATION_CAPABILITY), factory.requirements().keySet());
        assertEquals(CapabilityRequestV1.Operation.QUERY,
                provider.descriptor().capabilities().getFirst().operation());
    }

    HttpServer server;
    final AtomicReference<String> assignmentStatus = new AtomicReference<>("active");
    final AtomicInteger calls = new AtomicInteger();
    CustomerCrmProvider provider;
    final Instant now = Instant.parse("2026-10-09T10:00:00Z");

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer fixture-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String object = exchange.getRequestURI().getPath().substring("/rest/".length());
            Object rows = switch (object) {
                case "customerAssignments" -> List.of(Map.of("assignmentKey", "a-1",
                        "globalCustomerId", "CUST-000001", "principalRef", "employee-1",
                        "assignmentStatus", assignmentStatus.get(), "validFrom", "2026-01-01T00:00:00Z",
                        "validTo", "2027-01-01T00:00:00Z"));
                case "enterpriseCustomers" -> List.of(Map.of("globalCustomerId", "CUST-000001",
                        "name", "合成客户甲", "customerStatus", "active", "maskedPhone", "138****0001",
                        "businessAsOf", "2026-10-08T00:00:00Z"));
                case "customerDomainProfiles" -> List.of(Map.of("globalCustomerId", "CUST-000001",
                        "profileType", "wealth", "runtimeStatus", "active", "detailSchemaVersion", "1",
                        "detail", "{\"riskAssessment\":{\"riskLevel\":\"C3\",\"validUntil\":\"2027-01-01T00:00:00Z\"},\"totalAssets\":125000}"));
                default -> List.of();
            };
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(
                    Map.of("data", Map.of(object, rows)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        CustomerCrmClient client = new CustomerCrmClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2),
                () -> "fixture-token");
        provider = new CustomerCrmProvider("owner-1", client, () -> authority(Set.of("CUST-000001")),
                Clock.fixed(now, ZoneOffset.UTC));
    }
    private CapabilityRequestV1 allocationRequest() {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-products", "trace-products",
                "employee-1", FinanceContextV1.PrincipalType.EMPLOYEE, "finance-harness", "WEALTH_PRODUCT_REVIEW",
                "CUST-000001", "session-1", FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("wealth:allocation:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CustomerCrmProvider.ALLOCATION_CAPABILITY,
                CapabilityRequestV1.Operation.QUERY, context, Map.of("customerRef","CUST-000001","amount",100000,
                "horizonMonths",12,"channel","手机银行"), null, null, Map.of(), null);
    }
    @AfterEach void stop() { server.stop(0); }

    private CustomerCrmAuthority authority(Set<String> customers) {
        return new CustomerCrmAuthority("owner-1", "employee-1", "mapping-v1", customers,
                now.plusSeconds(3600), FinanceContextV1.AuthenticationLevel.BASIC);
    }
    private CapabilityRequestV1 request(String customer) {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-1", "trace-1",
                "employee-1", FinanceContextV1.PrincipalType.EMPLOYEE, "finance-harness", "WEALTH_PREVISIT",
                customer, "session-1", FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("wealth:previsit:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CustomerCrmProvider.CAPABILITY,
                CapabilityRequestV1.Operation.QUERY, context, Map.of("customerRef", customer),
                null, null, Map.of(), null);
    }

    @Test void readsFactsOnlyWhenMappingAndCrmAssignmentIntersect() {
        CapabilityResultV1 result = provider.query(request("CUST-000001"));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("CUST-000001", ((Map<?,?>)((Map<?,?>)result.data().get("previsitContext")).get("customer")).get("customerId"));
        assertEquals("mapping-v1", ((Map<?,?>)result.data().get("hostEvidence")).get("mappingRevision"));
        assertFalse(result.data().toString().contains("fixture-token"));
    }
    @Test void controlledMappingCannotBypassCrmRevocation() {
        assignmentStatus.set("inactive");
        assertThrows(SecurityException.class, () -> provider.query(request("CUST-000001")));
        assertEquals(1, calls.get());
    }
    @Test void crmRelationshipCannotBypassControlledMapping() {
        provider = new CustomerCrmProvider("owner-1",
                new CustomerCrmClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofSeconds(2), () -> "fixture-token"),
                () -> authority(Set.of("CUST-000002")), Clock.fixed(now, ZoneOffset.UTC));
        assertThrows(SecurityException.class, () -> provider.query(request("CUST-000001")));
        assertEquals(0, calls.get());
    }
    @Test void executeIsAlwaysRejected() {
        assertThrows(SecurityException.class, () -> provider.executeTask(request("CUST-000001")));
    }
    @Test void screensVersionedSyntheticProductsDeterministically() {
        CapabilityResultV1 result=provider.query(allocationRequest());
        assertEquals(CapabilityResultV1.Status.COMPLETED,result.status());
        assertEquals("ready",result.data().get("status"));
        List<?> eligible=(List<?>)result.data().get("eligibleProducts");
        assertEquals(2,eligible.size());
        assertTrue(result.data().toString().contains("wealth-suitability-v1"));
        assertTrue(result.data().toString().contains("PRODUCT_TERM_NOT_LONGER_THAN_CUSTOMER_HORIZON"));
        assertFalse(result.data().toString().contains("fixture-token"));
    }
}
