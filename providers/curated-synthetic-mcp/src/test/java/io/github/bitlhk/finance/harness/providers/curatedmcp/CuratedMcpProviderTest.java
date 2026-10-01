package io.github.bitlhk.finance.harness.providers.curatedmcp;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CuratedMcpProviderTest {
    HttpServer server;
    URI endpoint;
    List<JsonNode> calls = new CopyOnWriteArrayList<>();
    List<String> sessions = new CopyOnWriteArrayList<>();
    List<String> deletedSessions = new CopyOnWriteArrayList<>();
    Set<String> active = ConcurrentHashMap.newKeySet();
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger deletes = new AtomicInteger();
    volatile CountDownLatch deleteStarted = new CountDownLatch(0);
    volatile CountDownLatch releaseDelete = new CountDownLatch(0);
    AtomicReference<ManagedProviderFactoryV01.SourceSession> session = new AtomicReference<>(
            new ManagedProviderFactoryV01.SourceSession("bank-secret-never-forward", "host-customer", "actor", "host-session", Instant.now().plusSeconds(300)));
    volatile boolean isError, wrongIdentity, schemaMismatch, sse;
    volatile Runnable afterList = () -> {};
    volatile Runnable afterCall = () -> {};
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (exchange.getRequestMethod().equals("DELETE")) {
                    deleteStarted.countDown();
                    try {
                        if (!releaseDelete.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("DELETE test gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);
                    }
                    String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                    assertNotNull(id);assertTrue(active.remove(id), "DELETE must target an active SDK-owned session");
                    deletedSessions.add(id);deletes.incrementAndGet();
                    exchange.sendResponseHeaders(200, -1);return;
                }
                JsonNode body = HarnessJson.mapper().readTree(exchange.getRequestBody());
                Object result;
                String method = body.path("method").asText();
                if (method.equals("initialize")) {
                    String id = UUID.randomUUID().toString();active.add(id);sessions.add(id);
                    exchange.getResponseHeaders().set("Mcp-Session-Id", id);
                    result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "synthetic-test", "version", "1"));
                } else {
                    assertTrue(active.contains(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                    if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1);return; }
                    if (method.equals("tools/list")) {
                        result = Map.of("tools", CuratedTools.ALL.stream().map(t -> Map.of("name", t.name(), "inputSchema", schema(t, schemaMismatch))).toList());
                        afterList.run();
                    } else if (method.equals("tools/call")) {
                        calls.add(body);
                        var tool = CuratedTools.named(body.path("params").path("name").asText());
                        var args = body.path("params").path("arguments");
                        var data = fixture(tool, args, wrongIdentity);
                        result = Map.of("isError", isError, "content", List.of(Map.of("type", "text", "text", HarnessJson.mapper().writeValueAsString(data))));
                        afterCall.run();
                    } else throw new IllegalArgumentException(method);
                }
                String json = HarnessJson.mapper().writeValueAsString(Map.of("jsonrpc", "2.0", "id", body.get("id"), "result", result));
                byte[] bytes = (sse ? "event: message\ndata: " + json + "\n\n" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }
    @AfterEach void stop() { if (server != null) server.stop(0); }
    static McpSchema.JsonSchema schema(CuratedTools.Tool tool, boolean changed) {
        var props = new LinkedHashMap<String, Object>();
        if (!tool.selector().isEmpty()) props.put(tool.selector(), Map.of("type", changed ? "integer" : "string"));
        if (tool.name().equals("prepare_wealth_allocation_context")) for (String p : List.of("investable_amount", "investment_horizon_years", "liquidity_ratio")) props.put(p, Map.of("type", "number"));
        return new McpSchema.JsonSchema("object", props, tool.selector().isEmpty() || tool.defaultRef() != null ? List.of() : List.of(tool.selector()), false, null, null);
    }
    static Map<String, Object> fixture(CuratedTools.Tool tool, JsonNode args, boolean wrong) {
        var value = new LinkedHashMap<String, Object>();
        value.put("synthetic", true);value.put("source", tool.source());value.put("snapshotVersion", tool.snapshot());value.put("asOf", "2026-09-05");
        value.put(tool.requiredField(), List.of());
        String ref = args.path(tool.selector()).asText();
        if (wrong) ref = "unauthorized";
        switch (tool.identityPointer()) {
            case "/reference" -> value.put("reference", ref);
            case "/profile/customerId" -> value.put("profile", Map.of("customerId", ref));
            case "/customerId" -> value.put("customerId", ref);
            case "/product/productId" -> value.put("product", Map.of("productId", ref));
        }
        if (tool.name().equals("prepare_wealth_allocation_context")) value.put("readiness", "READY");
        if (tool.name().equals("fraud_transfer_risk_assess")) {
            value.put("decision", ref.equals("bene-unknown") ? "UNKNOWN" : "NO_KNOWN_HIT");
            value.put("status", ref.equals("bene-unknown") ? "UNAVAILABLE" : "READY");
        }
        return value;
    }
    CuratedSourceConfig config() {
        var pins = new LinkedHashMap<String, String>();var resources = new LinkedHashMap<String, Set<String>>();
        for (var tool : CuratedTools.ALL) {
            pins.put(tool.name(), CuratedTools.schemaPin(schema(tool, false)));
            resources.put(tool.name(), tool.selector().equals("customer_id") ? Set.of("C005") : tool.resources());
        }
        return new CuratedSourceConfig("curated-synthetic-source.v1", endpoint, "1.0.0", pins,
                Map.of("host-customer", new CuratedSourceConfig.Subject("C005", resources)));
    }
    CuratedMcpProvider provider() {
        return new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(3), session::get, () -> null, ignored -> {}), config());
    }
    CapabilityRequestV1 request(String name, Map<String, Object> args) {
        var tool = CuratedTools.named(name);
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, tool.capabilityId(), CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request", "trace", "actor", FinanceContextV1.PrincipalType.CUSTOMER,
                    "test", "synthetic-evaluation", "host-customer", "host-session", FinanceContextV1.AuthenticationLevel.STRONG, Set.of(tool.scope())),
                args, null, null, Map.of(), null);
    }
    @Test void allSixteenContractsUseActualSdkMcpNotBankHttp() {
        for (var tool : CuratedTools.ALL) {
            Map<String, Object> args = tool.selector().equals("customer_id") ? Map.of("customer_id", "host-customer")
                    : tool.selector().equals("product_id") ? Map.of("product_id", "P009") : Map.of();
            var result = provider().query(request(tool.name(), args));
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), tool.name() + ": " + result);
            assertTrue(result.data().containsKey("mcpExecution"));
        }
        assertEquals(16, calls.size());assertEquals(16, new HashSet<>(sessions).size());
        assertEquals("C005", calls.getFirst().path("params").path("arguments").path("customer_id").asText());
    }
    @Test void deniedSubjectResourceAndInvalidParametersMakeZeroMcpRequests() {
        for (var input : List.of(request("get_customer_profile", Map.of("customer_id", "C005")),
                request("get_product_detail", Map.of("reference", "P009")),
                request("get_customer_profile", Map.of("customer_id", "host-customer", "endpoint", endpoint.toString())),
                request("prepare_wealth_allocation_context", Map.of("customer_id", "host-customer", "liquidity_ratio", "0.2")),
                request("get_customer_profile", Map.of("customer_id", 1)))) {
            assertEquals(CapabilityResultV1.Status.FAILED, provider().query(input).status());
        }
        session.set(new ManagedProviderFactoryV01.SourceSession("secret", "other", "actor", "host-session", Instant.now().plusSeconds(30)));
        assertEquals(CapabilityResultV1.Status.FAILED, provider().query(request("get_wealth_policy_basis", Map.of())).status());
        assertEquals(0, requests.get());
    }
    @Test void boundGrantRevocationDoesNotReachMcp() {
        String scope = CuratedTools.named("get_wealth_policy_basis").scope();
        var grants = new AtomicReference<>(new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("host-customer"), Set.of("synthetic-evaluation"), Set.of(scope), "host-session", "test", Instant.now().plusSeconds(30)));
        var bound = new BoundFinanceProvider(provider(), grants::get, new CuratedMcpFactoryV01().requirements());
        assertEquals(CapabilityResultV1.Status.COMPLETED, bound.query(request("get_wealth_policy_basis", Map.of())).status());
        int before = requests.get();
        grants.set(new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("host-customer"), Set.of("synthetic-evaluation"), Set.of(), "host-session", "test", Instant.now().plusSeconds(30)));
        assertThrows(SecurityException.class, () -> bound.query(request("get_wealth_policy_basis", Map.of())));
        assertEquals(before, requests.get());
    }
    @Test void unknownIsSourceStateWhileIsErrorFailsTheReadAndNeitherBecomesTask() {
        var result = provider().query(request("fraud_transfer_risk_assess", Map.of("reference", "bene-unknown")));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());assertNull(result.task());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.data().get("sourceResult")).get("decision"));
        isError = true;
        result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals("MCP_IS_ERROR", result.error().sourceCode());assertEquals(true, result.data().get("mcpIsError"));
        assertEquals(2, calls.size());
    }
    @Test void schemaDriftDeniesCallAndResourceMismatchDiscardsResponse() {
        schemaMismatch = true;
        assertEquals(CapabilityResultV1.Status.FAILED, provider().query(request("get_customer_profile", Map.of("customer_id", "host-customer"))).status());
        assertEquals(0, calls.size());assertEquals(1, deletes.get());assertTrue(active.isEmpty());
        schemaMismatch = false;wrongIdentity = true;
        var result = provider().query(request("get_customer_profile", Map.of("customer_id", "host-customer")));
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());assertTrue(result.data().isEmpty());
    }
    @Test void sessionChangeBetweenListAndCallDeniesCall() {
        afterList = () -> session.set(new ManagedProviderFactoryV01.SourceSession("rotated", "host-customer", "actor", "host-session", Instant.now().plusSeconds(30)));
        assertEquals(CapabilityResultV1.Status.FAILED, provider().query(request("get_wealth_policy_basis", Map.of())).status());
        assertEquals(0, calls.size());
        assertEquals(1, deletes.get());assertTrue(active.isEmpty());
    }
    @Test void expiredAuthorityAfterListStillReclaimsItsSessionWithoutCallingTool() {
        afterList = () -> session.set(new ManagedProviderFactoryV01.SourceSession("expired", "host-customer", "actor", "host-session", Instant.now().minusSeconds(1)));
        assertEquals(CapabilityResultV1.Status.FAILED, provider().query(request("get_wealth_policy_basis", Map.of())).status());
        assertEquals(0, calls.size());assertEquals(1, deletes.get());assertTrue(active.isEmpty());
    }
    @Test void revocationAfterToolResponseDiscardsResultButReclaimsSession() {
        afterCall = () -> session.set(null);
        var result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());assertTrue(result.data().isEmpty());
        assertEquals(1, calls.size());assertEquals(1, deletes.get());assertTrue(active.isEmpty());
    }
    @Test void expiryAfterToolResponseDiscardsResultButReclaimsSession() {
        afterCall = () -> session.set(new ManagedProviderFactoryV01.SourceSession("expired", "host-customer", "actor", "host-session", Instant.now().minusSeconds(1)));
        var result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());assertTrue(result.data().isEmpty());
        assertEquals(1, calls.size());assertEquals(1, deletes.get());assertTrue(active.isEmpty());
    }
    @RepeatedTest(25) void sdkSupportsSseAndDoesNotReuseSession() {
        sse = true;var provider = provider();
        for (int i = 0; i < 2; i++) assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request("get_wealth_policy_basis", Map.of())).status());
        assertEquals(2, new HashSet<>(sessions).size());assertEquals(2, deletes.get());
        assertEquals(new HashSet<>(sessions), new HashSet<>(deletedSessions));
        assertTrue(active.isEmpty(), "Both remote sessions must be reclaimed before returning");
    }
    @Test void queryWaitsForDeleteResponseBeforeReturning() throws Exception {
        deleteStarted = new CountDownLatch(1);releaseDelete = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> provider().query(request("get_wealth_policy_basis", Map.of())));
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS), "SDK must issue DELETE");
            assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS),
                    "Query must not return while its remote session is still active");
            assertEquals(1, active.size());assertEquals(0, deletes.get());
            releaseDelete.countDown();
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, deletes.get());assertTrue(active.isEmpty());
        } finally {
            releaseDelete.countDown();executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test void deleteTimeoutCannotBeReportedAsCompletedRead() throws Exception {
        deleteStarted = new CountDownLatch(1);releaseDelete = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> provider().query(request("get_wealth_policy_basis", Map.of())));
            assertTrue(deleteStarted.await(5, TimeUnit.SECONDS));
            assertEquals(CapabilityResultV1.Status.FAILED, result.get(5, TimeUnit.SECONDS).status());
            assertEquals(0, deletes.get());assertEquals(1, active.size());
        } finally {
            releaseDelete.countDown();executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
    @Test void twoSubjectsNeverShareMcpSessionOrCustomerMapping() {
        var first = config();var subjects = new LinkedHashMap<>(first.subjects());
        subjects.put("host-second", new CuratedSourceConfig.Subject("C006", Map.of("get_customer_profile", Set.of("C006"))));
        var source = new CuratedSourceConfig(first.schemaVersion(), endpoint, first.contractVersion(), first.toolSchemaPins(), subjects);
        var provider = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(3), session::get, () -> null, ignored -> {}), source);
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request("get_customer_profile", Map.of("customer_id", "host-customer"))).status());
        session.set(new ManagedProviderFactoryV01.SourceSession("second-secret", "host-second", "actor-second", "session-second", Instant.now().plusSeconds(30)));
        var original = request("get_customer_profile", Map.of());var context = original.context();
        var second = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, original.capabilityId(), original.operation(),
                new FinanceContextV1(context.schemaVersion(), "second", "trace-second", "actor-second", context.principalType(), context.channel(), context.purpose(), "host-second", "session-second", context.authenticationLevel(), context.scopes()),
                Map.of("customer_id", "host-second"), null, null, Map.of(), null);
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(second).status());
        assertEquals(2, new HashSet<>(sessions).size());
        assertEquals(List.of("C005", "C006"), calls.stream().map(c -> c.path("params").path("arguments").path("customer_id").asText()).toList());
        int before = requests.get();assertEquals(CapabilityResultV1.Status.FAILED, provider.query(request("get_customer_profile", Map.of("customer_id", "host-customer"))).status());
        assertEquals(before, requests.get());
    }
    @Test void taskNeverTouchesMcp() {
        assertEquals("TASK_NOT_SUPPORTED", provider().executeTask(request("get_wealth_policy_basis", Map.of())).error().sourceCode());
        assertEquals(0, requests.get());
    }
    @Test void configRequiresExactPinAndSafeEndpoint(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("source.json");byte[] bytes = HarnessJson.mapper().writeValueAsBytes(config());Files.write(file, bytes);
        assertEquals(config(), CuratedSourceConfig.load(file, CuratedSourceConfig.sha(bytes)));
        assertThrows(IllegalArgumentException.class, () -> CuratedSourceConfig.load(file, "0".repeat(64)));
        var config = config();
        assertThrows(IllegalArgumentException.class, () -> new CuratedSourceConfig(config.schemaVersion(), URI.create("http://example.com/mcp"), config.contractVersion(), config.toolSchemaPins(), config.subjects()));
        assertThrows(IllegalArgumentException.class, () -> new CuratedMcpFactoryV01().create(
                new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", endpoint, Duration.ofSeconds(1), session::get, () -> null, ignored -> {}), Map.of()));
    }
}
