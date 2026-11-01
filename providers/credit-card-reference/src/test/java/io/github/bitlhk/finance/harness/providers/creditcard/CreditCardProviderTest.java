package io.github.bitlhk.finance.harness.providers.creditcard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreditCardProviderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Test void installedPythonRuntimeUsesActualManagedProvider() throws Exception {
        String endpoint = System.getenv("CREDIT_INSTALLED_PROVIDER_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(endpoint != null,
                "explicit installed Python Runtime and private transport binding required");
        var source = JSON.readValue(System.getenv("CREDIT_INSTALLED_SOURCE_BINDING"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        String principal = (String) source.get("principal_id"), customer = (String) source.get("customer_id");
        String hostSession = "session-jvm-" + UUID.randomUUID().toString().replace("-", "");
        Instant expiry = Instant.now().plusSeconds(180);
        var authority = new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of(customer), Set.of("CREDIT_CARD_SERVICE"), Set.of(CreditCardFactory.SCOPE),
                hostSession, "synthetic-installed-authority", expiry);
        var audits = new ArrayList<Map<String,String>>();
        var attempts = new ArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var uri = URI.create(endpoint);
        var factory = new CreditCardFactory(uri, () -> authority,
                ignored -> new CreditCardFactory.Binding(source, expiry), row -> { audits.add(row); return true; });
        var session = new ManagedProviderFactoryV01.SourceSession(
                Objects.requireNonNull(System.getenv("CREDIT_INSTALLED_PROVIDER_TOKEN")),
                customer, principal, hostSession, expiry);
        var provider = factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(),
                uri, Duration.ofSeconds(180), () -> session, () -> null, attempts::add), Map.of());
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"installed-request","installed-trace",
                principal,FinanceContextV1.PrincipalType.EMPLOYEE,"WEB","CREDIT_CARD_SERVICE",customer,
                hostSession,FinanceContextV1.AuthenticationLevel.STRONG,Set.of(CreditCardFactory.SCOPE));
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,CreditCardFactory.BENEFITS,
                CapabilityRequestV1.Operation.QUERY,context,
                Map.of("caseRef",source.get("case_id"),"goal","推荐三项适合当前客户的权益，说明适用条件",
                        "topK",3,"engines",List.of("llm")),
                "operation-jvm-"+UUID.randomUUID().toString().replace("-",""),null,Map.of(),null);
        var result = provider.query(request);
        assertEquals(CapabilityResultV1.Status.COMPLETED,result.status());
        assertFalse(attempts.isEmpty());
        assertTrue(audits.size() >= 2);
        assertTrue(attempts.stream().allMatch(row -> row.status() == 200));
    }
    static FinanceContextV1 context() {
        return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-1", "trace-1", "manager-1",
            FinanceContextV1.PrincipalType.EMPLOYEE, "WEB", "CREDIT_CARD_SERVICE", "customer-1", "session-1",
            FinanceContextV1.AuthenticationLevel.STRONG, Set.of(CreditCardFactory.SCOPE));
    }
    static BoundFinanceProvider.Grant grant() {
        return new BoundFinanceProvider.Grant("manager-1", FinanceContextV1.PrincipalType.EMPLOYEE,
            Set.of("customer-1"), Set.of("CREDIT_CARD_SERVICE"), Set.of(CreditCardFactory.SCOPE),
            "session-1", "synthetic-authority", Instant.now().plusSeconds(60));
    }
    static Map<String,Object> binding() {
        return Map.of("tenant_id", "tenant-1", "principal_id", "manager-1", "customer_id", "customer-1",
            "case_id", "case-1", "observed_at", "2026-06-12T00:00:00Z",
            "visible_source_ids", List.of("ledger", "benefits"), "snapshot_digest", "a".repeat(64));
    }
    static CapabilityRequestV1 request(Map<String,Object> args) {
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CreditCardFactory.LIMIT,
            CapabilityRequestV1.Operation.QUERY, context(), args, "operation-1", null, Map.of(), null);
    }
    static DescribedFinanceProvider provider(URI endpoint, AtomicReference<BoundFinanceProvider.Grant> grants) {
        var factory = new CreditCardFactory(endpoint, grants::get,
            request -> new CreditCardFactory.Binding(binding(), Instant.now().plusSeconds(3)), row -> true);
        var session = new ManagedProviderFactoryV01.SourceSession("synthetic-private-transport-token", "customer-1",
            "manager-1", "session-1", Instant.now().plusSeconds(60));
        return factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(), endpoint,
            Duration.ofSeconds(3), () -> session, () -> null, ignored -> {}), Map.of());
    }
    @Test void queryUsesOnlyHostIdentityAndChecksRevocationBeforeReturning() throws Exception {
        var calls = new AtomicInteger();
        var grants = new AtomicReference<>(grant());
        var revoke = new AtomicReference<>(false);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer synthetic-private-transport-token", exchange.getRequestHeaders().getFirst("Authorization"));
            var body = JSON.readTree(exchange.getRequestBody());
            assertEquals("manager-1", body.path("binding").path("principal_id").asText());
            assertEquals("session-1", body.path("session_ref").asText());
            assertTrue(body.path("remaining_ms").asInt() > 0);
            if (revoke.get()) grants.set(null);
            byte[] bytes = "{\"status\":\"complete\",\"customer_id\":\"customer-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), grants);
            var request = request(Map.of("caseRef", "case-1", "goal", "检查额度风险"));
            assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request).status());
            assertThrows(IllegalArgumentException.class, () -> provider.query(request(Map.of(
                "caseRef", "case-1", "goal", "query", "endpoint", "http://outside.invalid"))));
            assertEquals(1, calls.get());
            revoke.set(true);
            assertThrows(SecurityException.class, () -> provider.query(request));
            assertEquals(2, calls.get());
            assertThrows(SecurityException.class, () -> provider.query(request));
            assertEquals(2, calls.get());
            assertThrows(SecurityException.class, () -> provider.executeTask(request));
        } finally { server.stop(0); }
    }
    @Test void unconfiguredDiscoveryCannotGrantAccess() {
        assertThrows(SecurityException.class, () -> new CreditCardFactory().create(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreditCardFactory(URI.create("http://remote.invalid:80"),
            CreditCardProviderTest::grant, ignored -> null, ignored -> true));
    }
    @Test void redirectIsRejectedWithoutContactingItsTarget() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            exchange.getResponseHeaders().add("Location", "/credential-target");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/credential-target", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), new AtomicReference<>(grant()));
            assertThrows(IllegalStateException.class, () -> provider.query(request(Map.of("caseRef", "case-1", "goal", "query"))));
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
}
