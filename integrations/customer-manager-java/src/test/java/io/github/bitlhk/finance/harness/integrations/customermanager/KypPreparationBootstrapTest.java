package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationKnowledge;
import io.github.bitlhk.finance.harness.examples.wealth.ServicePreparation;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.mcp.PinnedMcpReader;
import io.github.bitlhk.finance.harness.providers.customercrm.*;
import io.github.bitlhk.finance.harness.providers.kyp.KypProductFactory;
import io.github.bitlhk.finance.harness.providers.knowledge.KnowledgeProvider;
import io.github.bitlhk.finance.harness.providers.knowledge.KnowledgeFactoryV01;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KypPreparationBootstrapTest {
    @TempDir Path directory;
    Path root, state, authority;
    HttpServer crmServer, products;
    java.util.concurrent.ExecutorService executor;
    final AtomicInteger productCalls = new AtomicInteger(), crmCalls = new AtomicInteger();
    final Instant now = Instant.now();
    static final Map<String,Object> SCHEMA = Map.of("type", "object", "properties", Map.of(
            "keyword", Map.of("type", "string"), "page", Map.of("type", "integer"), "pageSize", Map.of("type", "integer")),
            "required", List.of("keyword", "page", "pageSize"), "additionalProperties", false);
    volatile Runnable afterProduct = () -> {};
    PreparationAccess crm;

    @BeforeEach void setup() throws Exception {
        root = Files.createDirectory(directory.resolve("artifact")); state = Files.createDirectory(directory.resolve("private"));
        authority = directory.resolve("product-authority.json");
        executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        crmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        crmServer.setExecutor(executor);
        crmServer.createContext("/rest/", exchange -> {
            crmCalls.incrementAndGet();
            assertEquals("Bearer fixture-crm-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String entity = exchange.getRequestURI().getPath().substring(6);
            Object data = switch(entity) {
                case "customerAssignments" -> List.of(Map.of("globalCustomerId", "customer-a", "principalRef", "employee-a",
                        "assignmentStatus", "active", "validFrom", now.minusSeconds(60).toString(), "validTo", now.plusSeconds(3600).toString()));
                case "enterpriseCustomers" -> List.of(Map.of("globalCustomerId", "customer-a", "name", "Synthetic customer",
                        "customerStatus", "active", "businessAsOf", now.toString()));
                case "customerDomainProfiles" -> List.of(Map.of("globalCustomerId", "customer-a", "profileType", "wealth", "detail",
                        Map.of("riskAssessment", Map.of("riskLevel", "C3", "validUntil", now.plusSeconds(3600).toString()))));
                default -> List.of();
            };
            reply(exchange, Map.of("data", Map.of(entity, data)));
        });
        crmServer.start();
        products = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        products.setExecutor(executor);
        products.createContext("/mcp", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(405, -1); exchange.close(); return; }
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            var request = HarnessJson.mapper().readTree(exchange.getRequestBody());
            String method = request.path("method").asText();
            if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Object result = switch(method) {
                case "initialize" -> Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()),
                        "serverInfo", Map.of("name", "isolated-kyp", "version", "1"));
                case "tools/list" -> Map.of("tools", List.of(Map.of("name", "wealth_assistant_product_search", "inputSchema", SCHEMA)));
                case "tools/call" -> {
                    productCalls.incrementAndGet();
                    assertEquals("", request.path("params").path("arguments").path("keyword").asText());
                    assertFalse(request.toString().contains("customer-a"));
                    afterProduct.run();
                    yield Map.of("content", List.of(Map.of("type", "text", "text", HarnessJson.mapper().writeValueAsString(
                            Map.of("data", Map.of("products", List.of(Map.of("productId", "P001", "dataSourceType", "demo",
                                    "productFacts", Map.of("name", "Synthetic product", "riskLevel", "R2", "minPurchaseAmount", 1000,
                                            "termMonths", 6, "asOf", now.toString()),
                                    "salesEligibility", Map.of("salesStatus", "on_sale", "channels", List.of("branch"), "asOf", now.toString())))))))));
                }
                default -> throw new IllegalStateException("UNEXPECTED_FIXTURE_METHOD");
            };
            reply(exchange, Map.of("jsonrpc", "2.0", "id", request.path("id"), "result", result));
        });
        products.start();
        var crmAuthority = new CustomerCrmAuthority("owner-a", "employee-a", "crm-revision", Set.of("customer-a"),
                now.plusSeconds(3600), FinanceContextV1.AuthenticationLevel.STRONG);
        var client = new CustomerCrmClient(URI.create("http://127.0.0.1:" + crmServer.getAddress().getPort()), Duration.ofSeconds(2),
                () -> "fixture-crm-token");
        crm = new DirectCrmPreparationAccess("owner-a", "session-a", new CustomerCrmProvider("owner-a", client, () -> crmAuthority, Clock.systemUTC()));
        write(authority, Map.of("schemaVersion", "finance-product-read-authority.v1", "revision", "product-revision", "grants", List.of(
                Map.of("ownerRef", "owner-a", "principalId", "employee-a", "connectionRef", "product-source", "sourceId", "demo-source",
                        "subjects", List.of("customer-a"), "expiresAt", now.plusSeconds(3600).toString(),
                        "scopes", List.of(KypProductFactory.SCOPE), "purposes", List.of("WEALTH_PRODUCT_REVIEW")))));
        write(root.resolve(KypPreparationBootstrap.SOURCE), Map.of("schemaVersion", "finance-kyp-source.v1", "connectionRef", "product-source",
                "endpoint", "http://127.0.0.1:" + products.getAddress().getPort() + "/mcp", "tool", "wealth_assistant_product_search",
                "inputSchemaSha256", PinnedMcpReader.schemaDigest(SCHEMA), "timeoutMillis", 2000, "sourceId", "demo-source",
                "salesTrust", "DEMO", "maximumAgeSeconds", 86400));
        write(root.resolve("component-plan.json"), Map.of("capabilityBindings", Map.of(KypProductFactory.CAPABILITY, KypProductFactory.ID,
                        CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.PROVIDER_ID,
                        CustomerCrmProvider.ALLOCATION_CAPABILITY, CustomerCrmProvider.PROVIDER_ID),
                "providerConnections", Map.of(KypProductFactory.ID, Map.of("connectionRef", "product-source", "kind", "wealth-product-mcp.v1"),
                        CustomerCrmProvider.PROVIDER_ID, Map.of("connectionRef", "crm-current", "kind", "customer-crm-readonly.v1"))));
        lock(true);
    }
    @AfterEach void stop() { if(crmServer!=null)crmServer.stop(0); if(products!=null)products.stop(0); if(executor!=null)executor.shutdownNow(); }
    void lock(boolean product) throws Exception {
        Map<String,String> files = new HashMap<>();
        for (String name : List.of("component-plan.json", KypPreparationBootstrap.SOURCE))
            files.put(name, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(name)))));
        write(root.resolve("service-lock.json"), Map.of("capabilities", product ? List.of(KypProductFactory.CAPABILITY,
                CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.ALLOCATION_CAPABILITY) : List.of(), "files", files));
    }
    static void write(Path path, Object value) throws Exception { Files.createDirectories(path.getParent()); HarnessJson.mapper().writeValue(path.toFile(), value); }
    static void reply(com.sun.net.httpserver.HttpExchange exchange, Object value) throws java.io.IOException {
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    PreparationAccess open(String owner, Path grants) throws Exception {
        var observed = new ObservationRun("host-request", "process-a", "instance-a", event -> true);
        return KypPreparationBootstrap.open(new ManagedScenarioExtensionV01.Context(root, state, "build-a", "instance-a", owner, null,
                () -> observed), "session-a", crm, grants);
    }
    CapabilityResultV1 query(PreparationAccess access) {
        return access.query("request-a", "trace-a", "customer-a", CustomerCrmProvider.ALLOCATION_CAPABILITY,
                Map.of("amount", 100000, "horizonMonths", 12, "channel", "branch"));
    }
    @Test void lockedBootstrapActuallyQueriesBothSourcesAndKeepsDemoIneligible() throws Exception {
        var result = query(open("owner-a", authority));
        assertEquals(1, productCalls.get()); assertTrue(crmCalls.get() >= 4);
        assertEquals("partial", result.data().get("status")); assertEquals(List.of(), result.data().get("eligibleProducts"));
        assertNotNull(result.data().get("sourceEvidence"));
        try(var files=Files.list(state.resolve("service-preparation-reports/decision-evidence"))) { assertTrue(files.count() >= 2); }
    }
    @Test void businessFlowCombinesActualCrmAndProductHttpWithControlledKnowledgeEvidence() throws Exception {
        var knowledgeCalls = new AtomicInteger();
        PreparationKnowledge knowledge = new PreparationKnowledge() {
            public CapabilityResultV1 query(String request, String trace, boolean product) {
                assertTrue(product); knowledgeCalls.incrementAndGet();
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request, trace,
                        CapabilityResultV1.Status.COMPLETED, "controlled knowledge fixture", Map.of(
                            "decision", "EVIDENCE_FOUND", "evidence", List.of(Map.of("text", "需核实产品销售资格", "anchor", "clause-a")),
                            "knowledgeReceipt", Map.of("requestId", request, "traceId", trace, "manifest", "a".repeat(64))),
                        List.of(), List.of(), "fixture", now, null, null);
            }
            public void current() { }
            public void authorizeSaved(com.fasterxml.jackson.databind.JsonNode report) {
                throw new SecurityException("INDEPENDENT_HISTORY_REQUIRED");
            }
            public void close() { }
        };
        try (var service = new ServicePreparation("owner-a", open("owner-a", authority),
                () -> new ServicePreparation.Mapping("mapping-a", Map.of("Synthetic customer", "customer-a")),
                (method, goal, refs, facts) -> {
                    assertEquals("customer-a", facts.at("/customer/customerId").asText());
                    assertEquals("P001", facts.at("/productReview/sourceProducts/0/productId").asText());
                    assertEquals("clause-a", facts.at("/knowledgeEvidence/evidence/0/anchor").asText());
                    assertTrue(refs.contains("/productReview/sourceProducts/0/name"));
                    assertTrue(refs.contains("/knowledgeEvidence/evidence/0/text"));
                    var report = HarnessJson.mapper().createObjectNode();
                    report.putArray("communicationFocus").addObject().put("text", "核实客户服务需求")
                            .putArray("factRefs").add("/customer/customerId");
                    report.putArray("preparationAdvice").addObject().put("text", "产品仅为合成资料，需核实销售资格")
                            .putArray("factRefs").add("/productReview/sourceProducts/0/name").add("/knowledgeEvidence/evidence/0/text");
                    report.putArray("pendingItems").add("核实产品销售资格"); report.putArray("reviewChecklist").add("经理复核");
                    return report;
                }, state.resolve("joint-reports"), Map.of(),
                new InstalledPreparationMethod("fixture", "1", "sha256:fixture", "sha256:lock", "fixture"), Map::of, knowledge)) {
            long selection = service.select("owner-a", "Synthetic customer", "检查产品并准备服务建议");
            var report = service.prepareProduct("owner-a", selection,
                    Map.of("amount", 100000, "horizonMonths", 12, "channel", "branch"), ServicePreparation.PreparationListener.NONE);
            assertEquals("READY", report.path("explanationStatus").asText(), report.toString());
            assertEquals("PARTIAL", report.path("status").asText());
            assertTrue(report.at("/productReview/eligibleProducts").isEmpty());
            assertEquals(1, productCalls.get()); assertEquals(1, knowledgeCalls.get()); assertTrue(crmCalls.get() > 0);
        }
    }
    @Test void legacyArtifactDoesNotReadOrAcquireProductAuthority() throws Exception {
        lock(false); assertSame(crm, open("owner-a", directory.resolve("missing.json"))); assertEquals(0, productCalls.get());
    }
    @Test void installationConstructsActualDescriptorsWithoutNetworkOrAuthority() throws Exception {
        Files.delete(authority);
        var providers = new CustomerManagerScenarioExtension().installationProviders(root);
        assertEquals(Set.of(CustomerCrmProvider.PROVIDER_ID, KypProductFactory.ID), providers.keySet());
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
        assertTrue(((DescribedFinanceProvider)providers.get(KypProductFactory.ID)).descriptor().capabilities().stream()
                .anyMatch(cap -> cap.capabilityId().equals(KypProductFactory.CAPABILITY)));
    }
    void joint() throws Exception {
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(root.resolve("component-plan.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("capabilityBindings")).put(KnowledgeProvider.SEARCH, KnowledgeProvider.ID);
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("providerConnections")).putObject(KnowledgeProvider.ID)
                .put("connectionRef", "knowledge-isolated").put("kind", KnowledgeFactoryV01.KIND);
        write(root.resolve("component-plan.json"), plan);
        String config = "provider-configuration/knowledge-service/knowledge-binding.json";
        write(root.resolve(config), Map.of("schemaVersion", "knowledge-binding.v0.1", "productId", "fixture-policy",
                "manifest", "a".repeat(64), "businessDate", "2026-10-31"));
        var providers = new HashMap<String, Object>();
        for (String id : List.of(CustomerCrmProvider.PROVIDER_ID, KypProductFactory.ID, KnowledgeProvider.ID)) {
            var reference = plan.path("providerConnections").path(id);
            providers.put(id, Map.of("connectionRef", reference.path("connectionRef").asText(),
                    "kind", reference.path("kind").asText(), "timeoutMillis", 2000,
                    "endpoint", KnowledgeProvider.ID.equals(id) ? "http://127.0.0.1:1/query" :
                            KypProductFactory.ID.equals(id) ? "http://127.0.0.1:" + products.getAddress().getPort() + "/mcp" :
                                    "http://127.0.0.1:" + crmServer.getAddress().getPort(),
                    "configuration", KnowledgeProvider.ID.equals(id) ? Map.of("knowledge-binding",
                            Map.of("path", config, "sha256", digest(config))) : Map.of()));
        }
        write(root.resolve("provider-bindings.json"), Map.of("schemaVersion", "finance-host-provider-bindings.v0.1", "providers", providers));
        lockJoint();
    }
    String digest(String name) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(name))));
    }
    void lockJoint() throws Exception {
        var plan = HarnessJson.mapper().readTree(root.resolve("component-plan.json").toFile());
        var capabilities = new ArrayList<String>(); plan.path("capabilityBindings").fieldNames().forEachRemaining(capabilities::add);
        var files = new HashMap<String, String>();
        for (String file : List.of("component-plan.json", KypPreparationBootstrap.SOURCE, "provider-bindings.json",
                "provider-configuration/knowledge-service/knowledge-binding.json")) files.put(file, digest(file));
        write(root.resolve("service-lock.json"), Map.of("capabilities", capabilities, "files", files));
    }
    @Test void jointPreparationUsesThreeActualAdaptersWithoutBorrowingAuthority() throws Exception {
        joint(); Files.delete(authority);
        var providers = new CustomerManagerScenarioExtension().installationProviders(root);
        assertEquals(Set.of(CustomerCrmProvider.PROVIDER_ID, KypProductFactory.ID, KnowledgeProvider.ID), providers.keySet());
        var expected = Map.of(CustomerCrmProvider.PROVIDER_ID, Set.of(CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.ALLOCATION_CAPABILITY),
                KypProductFactory.ID, Set.of(KypProductFactory.CAPABILITY), KnowledgeProvider.ID, Set.of(KnowledgeProvider.SEARCH));
        expected.forEach((id, capabilities) -> capabilities.forEach(capability -> assertTrue(
                ((DescribedFinanceProvider) providers.get(id)).descriptor().supports(capability, CapabilityRequestV1.Operation.QUERY))));
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
    }
    @Test void jointCrmReadDoesNotRequireProductOrKnowledgeAuthority() throws Exception {
        joint();
        var access = open("owner-a", null);
        var result = access.query("crm-only", "trace", "customer-a", CustomerCrmProvider.CAPABILITY, Map.of());
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertTrue(crmCalls.get() > 0); assertEquals(0, productCalls.get());
    }
    @Test void jointConnectionsCannotSilentlyUseAnUnselectedEndpointOrTimeout() throws Exception {
        joint();
        URI current = URI.create("http://127.0.0.1:" + crmServer.getAddress().getPort());
        KypPreparationBootstrap.requireCrmConnection(root, current, Duration.ofMillis(2000));
        assertThrows(SecurityException.class, () -> KypPreparationBootstrap.requireCrmConnection(root,
                URI.create("http://127.0.0.1:1"), Duration.ofMillis(2000)));
        assertThrows(SecurityException.class, () -> KypPreparationBootstrap.requireCrmConnection(root, current, Duration.ofMillis(1000)));
        var binding = (com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(root.resolve("provider-bindings.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)binding.path("providers").path(KypProductFactory.ID))
                .put("endpoint", "http://127.0.0.1:1/mcp");
        write(root.resolve("provider-bindings.json"), binding); lockJoint();
        assertEquals("PROVIDER_CONNECTION_LOCK_MISMATCH", assertThrows(SecurityException.class,
                () -> new CustomerManagerScenarioExtension().installationProviders(root)).getMessage());
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
    }
    @Test void jointRegistryRejectsUnknownCapabilitiesAndTamperedKnowledgeConfiguration() throws Exception {
        joint();
        var plan = (com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(root.resolve("component-plan.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("capabilityBindings")).put("unknown.write", CustomerCrmProvider.PROVIDER_ID);
        write(root.resolve("component-plan.json"), plan); lockJoint();
        assertThrows(IllegalArgumentException.class, () -> new CustomerManagerScenarioExtension().installationProviders(root));
        ((com.fasterxml.jackson.databind.node.ObjectNode)plan.path("capabilityBindings")).remove("unknown.write");
        write(root.resolve("component-plan.json"), plan); lockJoint();
        Files.writeString(root.resolve("provider-configuration/knowledge-service/knowledge-binding.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> new CustomerManagerScenarioExtension().installationProviders(root));
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
    }
    @Test void crmOnlyPreparationDoesNotRequireAnUnselectedProductSource() throws Exception {
        write(root.resolve("component-plan.json"), Map.of("capabilityBindings", Map.of(CustomerCrmProvider.CAPABILITY, CustomerCrmProvider.PROVIDER_ID),
                "providerConnections", Map.of(CustomerCrmProvider.PROVIDER_ID,
                        Map.of("connectionRef", "crm-current", "kind", "customer-crm-readonly.v1"))));
        write(root.resolve("service-lock.json"), Map.of("capabilities", List.of(CustomerCrmProvider.CAPABILITY),
                "files", Map.of("component-plan.json", digest("component-plan.json"))));
        Files.delete(root.resolve(KypPreparationBootstrap.SOURCE)); Files.delete(authority);
        assertEquals(Set.of(CustomerCrmProvider.PROVIDER_ID), new CustomerManagerScenarioExtension().installationProviders(root).keySet());
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
    }
    @Test void selectedProductRequiresSeparateCurrentGrant() throws Exception {
        var without = open("owner-a", null);
        var other = open("another-owner", authority);
        assertThrows(SecurityException.class, () -> query(without));
        assertThrows(SecurityException.class, () -> query(other));
        assertEquals(0, productCalls.get()); assertEquals(0, crmCalls.get());
    }
    @Test void unrelatedCrmReadDoesNotAcquireProductGrant() throws Exception {
        var access = open("owner-a", null);
        assertEquals(0, crmCalls.get()); assertEquals(0, productCalls.get());
        var result = access.query("crm-only", "trace", "customer-a", CustomerCrmProvider.CAPABILITY, Map.of());
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertTrue(crmCalls.get() > 0); assertEquals(0, productCalls.get());
    }
    @Test void expiredProductGrantDoesNotPreventSessionConstruction() throws Exception {
        var value = (com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(authority.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)value.path("grants").get(0)).put("expiresAt", "2000-01-01T00:00:00Z");
        write(authority, value);
        var access = open("owner-a", authority);
        assertEquals(0, crmCalls.get()); assertEquals(0, productCalls.get());
        assertThrows(SecurityException.class, () -> query(access));
        assertEquals(0, crmCalls.get()); assertEquals(0, productCalls.get());
    }
    @Test void tamperedLockedSourceIsRejectedBeforeNetwork() throws Exception {
        Files.writeString(root.resolve(KypPreparationBootstrap.SOURCE), "{}");
        assertThrows(SecurityException.class, () -> open("owner-a", authority)); assertEquals(0, productCalls.get());
    }
    @Test void sourcePolicyCannotPromoteDemoToBankVerified() throws Exception {
        var source = (com.fasterxml.jackson.databind.node.ObjectNode)HarnessJson.mapper().readTree(root.resolve(KypPreparationBootstrap.SOURCE).toFile());
        source.put("salesTrust", "BANK_VERIFIED"); write(root.resolve(KypPreparationBootstrap.SOURCE), source); lock(true);
        assertThrows(SecurityException.class, () -> open("owner-a", authority));
    }
    @Test void revokedFileCannotReleaseLateMcpResult() throws Exception {
        var access = open("owner-a", authority);
        afterProduct = () -> { try { Files.writeString(authority, "{}"); } catch(Exception failure) { throw new RuntimeException(failure); } };
        assertThrows(SecurityException.class, () -> query(access)); assertEquals(1, productCalls.get());
    }
    @Test void auditFailurePreventsRemoteProductCall() throws Exception {
        Files.createDirectories(state.resolve("service-preparation-reports"));
        Files.createFile(state.resolve("service-preparation-reports/decision-evidence"));
        var access = open("owner-a", authority);
        assertThrows(RuntimeException.class, () -> query(access)); assertEquals(0, productCalls.get());
    }
    @Test void authorityLinkAndArtifactOwnedGrantAreRejected() throws Exception {
        Path linked = directory.resolve("linked-policy.json"); Files.createSymbolicLink(linked, authority);
        assertThrows(SecurityException.class, () -> query(open("owner-a", linked)));
        Path inside = root.resolve("grant.json"); Files.copy(authority, inside);
        assertThrows(SecurityException.class, () -> query(open("owner-a", inside)));
    }
    @Test void duplicatePolicyKeysFailClosed() throws Exception {
        String bytes = Files.readString(authority);
        Files.writeString(authority, bytes.replace("\"revision\":", "\"revision\":\"duplicate\",\"revision\":"));
        assertThrows(SecurityException.class, () -> query(open("owner-a", authority)));
    }
}
