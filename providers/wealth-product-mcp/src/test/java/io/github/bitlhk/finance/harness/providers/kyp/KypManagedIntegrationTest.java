package io.github.bitlhk.finance.harness.providers.kyp;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.mcp.PinnedMcpReader;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmFactoryV01;
import org.junit.jupiter.api.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class KypManagedIntegrationTest {
    static final String SUBJECT = "synthetic-customer", ACTOR = "synthetic-employee";
    static final String CRM_ID = "customer-crm-readonly", CRM_CAP = "wealth.previsit-context.query";
    static final Map<String,Object> SCHEMA = Map.of("type", "object", "properties", Map.of(
            "keyword", Map.of("type", "string"), "page", Map.of("type", "integer"),
            "pageSize", Map.of("type", "integer")), "required", List.of("keyword", "page", "pageSize"), "additionalProperties", false);
    HttpServer crm, mcp;
    ExecutorService executor;
    Instant now;
    final AtomicInteger crmReads = new AtomicInteger(), mcpRequests = new AtomicInteger(), toolCalls = new AtomicInteger();
    final AtomicReference<BoundFinanceProvider.Grant> crmGrant = new AtomicReference<>(), productGrant = new AtomicReference<>();
    final List<Map<String,String>> audit = new CopyOnWriteArrayList<>();
    final List<String> productHeaders = new CopyOnWriteArrayList<>(), productArguments = new CopyOnWriteArrayList<>();
    volatile boolean schemaMismatch, toolError, oversized, stateful, assignmentRevoked;
    volatile Runnable afterTool = () -> {};
    volatile String failAuditPhase = "";
    volatile Duration timeout = Duration.ofSeconds(2);
    final List<ManagedProviderFactoryV01.HttpAttempt> attempts = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        now = Instant.now();
        crmGrant.set(grant(Set.of("wealth:previsit:read"), "WEALTH_PREVISIT"));
        productGrant.set(grant(Set.of(KypProductFactory.SCOPE), "WEALTH_PRODUCT_REVIEW"));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        crm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); crm.setExecutor(executor);
        crm.createContext("/rest/", exchange -> {
            crmReads.incrementAndGet();
            String object = exchange.getRequestURI().getPath().substring("/rest/".length());
            if (!"Bearer isolated-crm-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(403, -1); exchange.close(); return;
            }
            Object rows = switch (object) {
                case "customerAssignments" -> List.of(Map.of("globalCustomerId", SUBJECT, "principalRef", ACTOR,
                        "assignmentStatus", assignmentRevoked ? "inactive" : "active", "validFrom", now.minusSeconds(60).toString(),
                        "validTo", now.plusSeconds(3600).toString()));
                case "enterpriseCustomers" -> List.of(Map.of("globalCustomerId", SUBJECT, "name", "Synthetic customer",
                        "customerStatus", "active", "businessAsOf", now.toString()));
                case "customerDomainProfiles" -> List.of(Map.of("globalCustomerId", SUBJECT, "profileType", "wealth",
                        "detail", Map.of("riskAssessment", Map.of("riskLevel", "C3", "validUntil", now.plusSeconds(3600).toString()),
                                "totalAssets", 1800000, "cash", 360000, "investmentAssets", 1440000,
                                "recentContactSummary", "Discuss maturity proceeds and diversification")));
                default -> List.of();
            };
            reply(exchange, Map.of("data", Map.of(object, rows)));
        });
        crm.start();
        mcp = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); mcp.setExecutor(executor);
        mcp.createContext("/mcp", exchange -> {
            mcpRequests.incrementAndGet();
            productHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            try {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); return; }
                var request = HarnessJson.mapper().readTree(exchange.getRequestBody());
                String method = request.path("method").asText();
                if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); return; }
                Object result;
                if (method.equals("initialize")) {
                    if (stateful) exchange.getResponseHeaders().add("Mcp-Session-Id", "unowned-session");
                    result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                            "serverInfo", Map.of("name", "isolated-product", "version", "1"));
                } else if (method.equals("tools/list")) {
                    result = Map.of("tools", List.of(Map.of("name", "wealth_assistant_product_search", "inputSchema",
                            schemaMismatch ? Map.of("type", "object") : SCHEMA)));
                } else if (method.equals("tools/call")) {
                    toolCalls.incrementAndGet(); productArguments.add(request.path("params").toString());
                    afterTool.run();
                    if (oversized) { exchange.sendResponseHeaders(200, 262145); return; }
                    result = Map.of("isError", toolError, "content", List.of(Map.of("type", "text", "text",
                            HarnessJson.mapper().writeValueAsString(payload()))));
                } else { exchange.sendResponseHeaders(400, -1); return; }
                reply(exchange, Map.of("jsonrpc", "2.0", "id", request.path("id"), "result", result));
            } finally { exchange.close(); }
        });
        mcp.start();
    }
    @AfterEach void stop() { crm.stop(0); mcp.stop(0); executor.shutdownNow(); }
    static void reply(com.sun.net.httpserver.HttpExchange exchange, Object value) throws java.io.IOException {
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    Object payload() {
        return Map.of("data", Map.of("products", List.of(Map.of("productId", "P001", "dataSourceType", "public",
                "productFacts", Map.of("name", "Synthetic public fund", "riskLevel", "R2", "minPurchaseAmount", 1000,
                        "termMonths", 6, "asOf", now.toString()),
                "salesEligibility", Map.of("salesStatus", "on_sale", "channels", List.of("branch"), "asOf", now.toString())))));
    }
    BoundFinanceProvider.Grant grant(Set<String> scopes, String purpose) {
        return new BoundFinanceProvider.Grant(ACTOR, FinanceContextV1.PrincipalType.EMPLOYEE, Set.of(SUBJECT),
                Set.of(purpose), scopes, "fixture-session", "fixture-authority", Instant.now().plusSeconds(3600));
    }
    CapabilityRequestV1 request(boolean product) {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, product ? "product-request" : "crm-request",
                "composite-trace", ACTOR, FinanceContextV1.PrincipalType.EMPLOYEE, "isolated-host",
                product ? "WEALTH_PRODUCT_REVIEW" : "WEALTH_PREVISIT", SUBJECT, "fixture-session",
                FinanceContextV1.AuthenticationLevel.STRONG, Set.of(product ? KypProductFactory.SCOPE : "wealth:previsit:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, product ? KypProductFactory.CAPABILITY : CRM_CAP,
                CapabilityRequestV1.Operation.QUERY, context, Map.of(), null, null, Map.of(), null);
    }
    Map<String,FinanceProvider> installed() {
        var endpoint = URI.create("http://127.0.0.1:" + mcp.getAddress().getPort() + "/mcp");
        var target = new PinnedMcpReader.Target(endpoint, "wealth_assistant_product_search",
                PinnedMcpReader.schemaDigest(SCHEMA), timeout);
        var kyp = new KypProductFactory(target, new ProductCatalog.SourcePolicy("isolated-product",
                ProductCatalog.SalesTrust.DEMO, Duration.ofDays(1)),
                productGrant::get, fields -> {
                    audit.add(fields); return !fields.get("phase").equals(failAuditPhase);
                }, Clock.systemUTC());
        var crmConnection = new ManagedProviderFactoryV01.Connection("customer-crm-readonly.v1",
                URI.create("http://127.0.0.1:" + crm.getAddress().getPort()), Duration.ofSeconds(2),
                () -> new ManagedProviderFactoryV01.SourceSession("isolated-crm-token", SUBJECT, ACTOR,
                        "fixture-session", now.plusSeconds(3600)), () -> null, ignored -> {});
        var productConnection = new ManagedProviderFactoryV01.Connection("wealth-product-mcp.v1", endpoint, timeout,
                () -> { throw new AssertionError("Public product transport must never acquire a CRM credential"); }, () -> null, attempts::add);
        var registry = new ManagedProviderFactoriesV01(List.of(new CustomerCrmFactoryV01(), kyp));
        var ids = Set.of(CRM_ID, KypProductFactory.ID);
        return registry.createReferenced(Map.of(
                        CRM_ID, new ManagedProviderFactoriesV01.ConnectionReference("crm-source", crmConnection.kind()),
                        KypProductFactory.ID, new ManagedProviderFactoriesV01.ConnectionReference("product-source", productConnection.kind())),
                Map.of("crm-source", crmConnection, "product-source", productConnection),
                Map.of(CRM_ID, crmGrant::get, KypProductFactory.ID, productGrant::get),
                ids, registry.configurationDigests(ids), Map.of(CRM_ID, ManagedProviderFactoriesV01.connectionDigest(crmConnection),
                        KypProductFactory.ID, ManagedProviderFactoriesV01.connectionDigest(productConnection)));
    }
    @Test void actualCrmHttpAndOfficialMcpSdkRunWithSeparateConnections() {
        var providers = installed();
        assertEquals(0, crmReads.get()); assertEquals(0, mcpRequests.get());
        var facts = providers.get(CRM_ID).query(request(false));
        var products = providers.get(KypProductFactory.ID).query(request(true));
        assertEquals(CapabilityResultV1.Status.COMPLETED, facts.status());
        assertTrue(facts.data().toString().contains("1800000"));
        assertTrue(facts.data().toString().contains("1440000"));
        assertTrue(facts.data().toString().contains("Discuss maturity proceeds"));
        assertEquals(CapabilityResultV1.Status.COMPLETED, products.status());
        assertEquals("PARTIAL", products.data().get("readiness"));
        assertEquals(false, products.data().get("suitabilityAssessed"));
        assertTrue(products.missingInputs().contains("BANK_SALES_NOT_VERIFIED"));
        assertEquals(facts.traceId(), products.traceId());
        assertEquals(1, toolCalls.get()); assertTrue(crmReads.get() >= 4);
        assertTrue(productHeaders.stream().allMatch("null"::equals));
        assertFalse(productArguments.toString().contains(SUBJECT));
        assertFalse(productArguments.toString().contains("isolated-crm-token"));
        assertEquals(List.of("STARTED", "COMPLETED"), audit.stream().map(a -> a.get("phase")).toList());
        assertTrue(attempts.stream().anyMatch(a -> a.status() == 200 && a.route().equals("product-source")));
    }
    @Test void revokedProductGrantDoesNotRevokeOrBorrowCrmGrant() {
        var providers = installed(); productGrant.set(grant(Set.of(), "WEALTH_PRODUCT_REVIEW"));
        assertThrows(SecurityException.class, () -> providers.get(KypProductFactory.ID).query(request(true)));
        assertEquals(0, mcpRequests.get());
        assertEquals(CapabilityResultV1.Status.COMPLETED, providers.get(CRM_ID).query(request(false)).status());
    }
    @Test void revocationDuringToolCallDiscardsLateResult() {
        var provider = installed().get(KypProductFactory.ID);
        afterTool = () -> productGrant.set(grant(Set.of(), "WEALTH_PRODUCT_REVIEW"));
        assertThrows(SecurityException.class, () -> provider.query(request(true)));
        assertEquals(1, toolCalls.get());
        assertFalse(audit.stream().anyMatch(a -> a.get("phase").equals("COMPLETED")));
    }
    @Test void requiredAuditFailurePreventsSourceCall() {
        failAuditPhase = "STARTED";
        assertThrows(IllegalStateException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(0, mcpRequests.get());
    }
    @Test void completionAuditFailurePreventsResultDelivery() {
        failAuditPhase = "COMPLETED";
        assertThrows(IllegalStateException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(1, toolCalls.get());
    }
    @Test void schemaDriftRejectsBeforeBusinessCall() {
        schemaMismatch = true;
        assertThrows(IllegalArgumentException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(0, toolCalls.get());
    }
    @Test void sourceFailureHasNoCatalogFallback() {
        toolError = true;
        assertThrows(IllegalStateException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(1, toolCalls.get());
    }
    @Test void oversizedWireResponseRejectsBeforeContractParsing() {
        oversized = true;
        assertThrows(RuntimeException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(1, toolCalls.get());
    }
    @Test void unexpectedStatefulSourceIsNotAdmitted() {
        stateful = true;
        assertThrows(RuntimeException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertEquals(0, toolCalls.get());
    }
    @Test void crmAssignmentRevocationStillRejects() {
        assignmentRevoked = true;
        assertThrows(SecurityException.class, () -> installed().get(CRM_ID).query(request(false)));
        assertEquals(0, mcpRequests.get());
    }
    @Test void changedAuthorityGenerationDiscardsOtherwiseValidResult() {
        var provider = installed().get(KypProductFactory.ID);
        afterTool = () -> {
            var old = productGrant.get();
            productGrant.set(new BoundFinanceProvider.Grant(old.principalId(), old.principalType(), old.subjects(),
                    old.purposes(), old.scopes(), old.sessionRef(), "replacement-authority", old.expiresAt()));
        };
        assertThrows(SecurityException.class, () -> provider.query(request(true)));
        assertFalse(audit.stream().anyMatch(a -> a.get("phase").equals("COMPLETED")));
    }
    @Test void arbitraryCustomerOrEndpointArgumentsNeverReachMcp() {
        var provider = installed().get(KypProductFactory.ID);
        var base = request(true);
        for (String key : List.of("endpoint", "customer_id", "crmToken", "toolName")) {
            var supplied = new CapabilityRequestV1(base.schemaVersion(), base.capabilityId(), base.operation(), base.context(),
                    Map.of(key, "untrusted"), null, null, Map.of(), null);
            assertThrows(RuntimeException.class, () -> provider.query(supplied));
        }
        assertEquals(0, mcpRequests.get());
    }
    @Test void timeoutReturnsNoFallbackAndNoSuccessfulAudit() {
        timeout = Duration.ofMillis(300);
        afterTool = () -> { try { Thread.sleep(900); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
        assertThrows(RuntimeException.class, () -> installed().get(KypProductFactory.ID).query(request(true)));
        assertFalse(audit.stream().anyMatch(a -> a.get("phase").equals("COMPLETED")));
        assertTrue(toolCalls.get() <= 1);
    }
    @Test void cancellationDoesNotDeliverALateToolResult() throws Exception {
        var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CompletableFuture<Throwable>();
        afterTool = () -> {
            reached.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        var provider = installed().get(KypProductFactory.ID);
        var work = executor.submit(() -> {
            try { provider.query(request(true)); finished.complete(null); }
            catch (Throwable failure) { finished.complete(failure); }
        });
        try {
            assertTrue(reached.await(5, TimeUnit.SECONDS)); work.cancel(true);
            assertNotNull(finished.get(5, TimeUnit.SECONDS));
            assertFalse(audit.stream().anyMatch(a -> a.get("phase").equals("COMPLETED")));
        } finally { release.countDown(); }
    }
}
