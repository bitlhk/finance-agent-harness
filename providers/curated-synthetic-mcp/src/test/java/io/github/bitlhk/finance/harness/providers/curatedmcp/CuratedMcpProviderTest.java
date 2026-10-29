package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.mcp.BoundedHttpClient;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
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
    ExecutorService serverExecutor;
    URI endpoint;
    List<JsonNode> calls = new CopyOnWriteArrayList<>();
    List<String> sessions = new CopyOnWriteArrayList<>();
    List<String> deletedSessions = new CopyOnWriteArrayList<>();
    List<String> attemptedDeletes = new CopyOnWriteArrayList<>();
    Set<String> active = ConcurrentHashMap.newKeySet();
    AtomicInteger requests = new AtomicInteger();
    AtomicInteger deletes = new AtomicInteger();
    volatile CountDownLatch deleteStarted = new CountDownLatch(0);
    volatile CountDownLatch releaseDelete = new CountDownLatch(0);
    AtomicReference<ManagedProviderFactoryV01.SourceSession> session = new AtomicReference<>(
            new ManagedProviderFactoryV01.SourceSession("bank-secret-never-forward", "host-customer", "actor", "host-session", Instant.now().plusSeconds(300)));
    volatile boolean isError, wrongIdentity, schemaMismatch, sse, structuredContent, stateless;
    volatile Runnable afterList = () -> {};
    volatile Runnable afterCall = () -> {};
    @FunctionalInterface interface Attack { boolean respond(String method, com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException; }
    volatile Attack attack = (method, exchange) -> false;
    volatile Attack beforeDelete = (method, exchange) -> false;
    volatile java.util.function.Supplier<String> initializeSessionId = () -> UUID.randomUUID().toString();
    volatile java.util.function.Consumer<Map<String, Object>> mutateResult = ignored -> {};
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverExecutor);
        server.createContext("/mcp", exchange -> {
            requests.incrementAndGet();
            if (stateless) exchange.getResponseHeaders().set("X-Finance-Source-Sha256", "a".repeat(64));
            assertNull(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                if (exchange.getRequestMethod().equals("GET")) {
                    if (!attack.respond("GET", exchange)) exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (exchange.getRequestMethod().equals("DELETE")) {
                    String id = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
                    assertNotNull(id);attemptedDeletes.add(id);
                    deleteStarted.countDown();
                    try {
                        if (!releaseDelete.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("DELETE test gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();throw new java.io.IOException(interrupted);
                    }
                    if (beforeDelete.respond("DELETE", exchange)) return;
                    assertTrue(active.remove(id), "DELETE must target an active SDK-owned session");
                    deletedSessions.add(id);deletes.incrementAndGet();
                    if (attack.respond("DELETE", exchange)) return;
                    exchange.sendResponseHeaders(200, -1);return;
                }
                JsonNode body = HarnessJson.mapper().readTree(exchange.getRequestBody());
                Object result;
                String method = body.path("method").asText();
                if (method.equals("initialize")) {
                    assertNull(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"), "Initialize must not resume a session");
                    if (!stateless) {
                        String id = initializeSessionId.get();active.add(id);sessions.add(id);
                        exchange.getResponseHeaders().set("Mcp-Session-Id", id);
                    }
                    result = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "synthetic-test", "version", "1"));
                } else {
                    if (stateless) assertNull(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                    else assertTrue(active.contains(exchange.getRequestHeaders().getFirst("Mcp-Session-Id")));
                    if (method.equals("notifications/initialized")) { exchange.sendResponseHeaders(202, -1);return; }
                    if (method.equals("tools/list")) {
                        result = Map.of("tools", CuratedTools.ALL.stream().map(t -> Map.of("name", t.name(), "inputSchema", schema(t, schemaMismatch))).toList());
                        afterList.run();
                    } else if (method.equals("tools/call")) {
                        calls.add(body);
                        var tool = CuratedTools.named(body.path("params").path("name").asText());
                        var args = body.path("params").path("arguments");
                        var data = fixture(tool, args, wrongIdentity);
                        mutateResult.accept(data);
                        var response = new LinkedHashMap<String, Object>();
                        response.put("isError", isError);
                        response.put("content", List.of(Map.of("type", "text", "text", HarnessJson.mapper().writeValueAsString(data))));
                        if (structuredContent) response.put("structuredContent", data);
                        result = response;
                        afterCall.run();
                    } else throw new IllegalArgumentException(method);
                }
                if (attack.respond(method, exchange)) return;
                String json = HarnessJson.mapper().writeValueAsString(Map.of("jsonrpc", "2.0", "id", body.get("id"), "result", result));
                byte[] bytes = (sse ? "event: message\ndata: " + json + "\n\n" : json).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", sse ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }
    @AfterEach void stop() throws Exception {
        if (server != null) server.stop(0);
        if (serverExecutor != null) { serverExecutor.shutdownNow();assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    static McpSchema.JsonSchema schema(CuratedTools.Tool tool, boolean changed) {
        var props = new LinkedHashMap<String, Object>();
        if (!tool.selector().isEmpty()) props.put(tool.selector(), Map.of("type", changed ? "integer" : "string"));
        if (tool.name().equals("prepare_wealth_allocation_context")) for (String p : List.of("investable_amount", "investment_horizon_years", "liquidity_ratio")) props.put(p, Map.of("type", "number"));
        return new McpSchema.JsonSchema("object", props, tool.selector().isEmpty() || tool.defaultRef() != null ? List.of() : List.of(tool.selector()), false, null, null);
    }
    static Map<String, Object> fixture(CuratedTools.Tool tool, JsonNode args, boolean wrong) {
        var value = new LinkedHashMap<String, Object>(OutputSamples.value(tool.name(), args.path(tool.selector()).asText()));
        value.put("synthetic", true);value.put("source", tool.source());value.put("snapshotVersion", tool.snapshot());value.put("asOf", "2026-09-05");
        if (tool.name().equals("get_customer_profile") && args.path("customer_id").asText().equals("I001")) value.put("snapshotVersion", "roles-demo-v1");
        String ref = args.path(tool.selector()).asText();
        if (wrong) ref = "unauthorized";
        switch (tool.identityPointer()) {
            case "/reference" -> value.put("reference", ref);
            case "/profile/customerId" -> {
                var profile = new LinkedHashMap<>((Map<String, Object>) value.get("profile"));
                profile.put("customerId", ref);value.put("profile", profile);
            }
            case "/customerId" -> value.put("customerId", ref);
            case "/product/productId" -> {
                var product = new LinkedHashMap<>((Map<String, Object>) value.get("product"));
                product.put("productId", ref);value.put("product", product);
            }
        }
        if (tool.name().equals("prepare_wealth_allocation_context")) value.put("readiness", "READY");
        if (tool.name().equals("fraud_transfer_risk_assess")) {
            value.put("decision", ref.equals("bene-unknown") ? "UNKNOWN" : ref.equals("bene-risk") ? "RISK_HIT" : "NO_KNOWN_HIT");
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
        return provider(Duration.ofSeconds(3));
    }
    CuratedMcpProvider provider(Duration timeout) {
        return new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                timeout, session::get, () -> null, ignored -> {}), config());
    }
    CuratedMcpProvider selectedCustomers() {
        stateless = true;
        var source = new CuratedSourceConfig("curated-synthetic-source.v3", endpoint, "1.0.0", config().toolSchemaPins(),
                Map.of("host-customer", new CuratedSourceConfig.Subject(null,
                        Map.of("get_customer_profile", Set.of("C005", "C006")), "SYNTHETIC_ALLOWLIST")),
                new CuratedSourceConfig.RuntimePolicy("STATELESS_BOUNDED", "a".repeat(64)));
        return new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(3), session::get, () -> null, ignored -> {}), source);
    }
    CapabilityRequestV1 selectedRequest(String id) {
        return request("get_customer_profile", Map.of("customer_id", "host-customer", "synthetic_customer_id", id));
    }
    @Test void selectedCustomersPreserveSourceIdentityAndRejectUnconfiguredResourcesBeforeHttp() {
        var provider = selectedCustomers();
        var grant = new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("host-customer"), Set.of("synthetic-evaluation"), Set.of(CuratedTools.named("get_customer_profile").scope()),
                "host-session", "test", Instant.now().plusSeconds(30));
        var bound = new BoundFinanceProvider(provider, () -> grant, new CuratedMcpFactoryV01().requirements());
        for (String id : List.of("C005", "C006")) {
            var result = bound.query(selectedRequest(id));
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
            assertEquals(id, HarnessJson.mapper().valueToTree(result.data()).at("/sourceResult/profile/customerId").asText());
        }
        assertEquals(List.of("C005", "C006"), calls.stream().map(c -> c.at("/params/arguments/customer_id").asText()).toList());
        int before = requests.get();
        for (String id : List.of("C007", "I001", "host-customer"))
            assertEquals("NOT_AUTHORIZED", bound.query(selectedRequest(id)).error().failureClass());
        assertThrows(SecurityException.class, () -> bound.query(request("get_customer_profile", Map.of("customer_id", "C005", "synthetic_customer_id", "C005"))));
        assertEquals("INVALID_INPUT", provider.query(request("get_customer_profile", Map.of())).error().failureClass());
        assertEquals(before, requests.get());assertTrue(sessions.isEmpty());assertTrue(active.isEmpty());
    }
    @Test void selectedCustomersStillRequireCurrentSessionAndGrant() {
        var provider = selectedCustomers();
        var request = selectedRequest("C005");
        var initial = session.get();
        var invalid = Arrays.asList(null,
                new ManagedProviderFactoryV01.SourceSession("secret", "host-customer", "actor", "host-session", Instant.now().minusSeconds(1)),
                new ManagedProviderFactoryV01.SourceSession("secret", "other", "actor", "host-session", Instant.now().plusSeconds(30)),
                new ManagedProviderFactoryV01.SourceSession("secret", "host-customer", "other", "host-session", Instant.now().plusSeconds(30)),
                new ManagedProviderFactoryV01.SourceSession("secret", "host-customer", "actor", "other", Instant.now().plusSeconds(30)));
        for (var value : invalid) {
            session.set(value);
            assertEquals("NOT_AUTHORIZED", provider.query(request).error().failureClass());
        }
        session.set(initial);
        var grants = new AtomicReference<>(new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("host-customer"), Set.of("synthetic-evaluation"), Set.of(), "host-session", "test", Instant.now().plusSeconds(30)));
        var bound = new BoundFinanceProvider(provider, grants::get, new CuratedMcpFactoryV01().requirements());
        assertThrows(SecurityException.class, () -> bound.query(request));
        assertEquals(0, requests.get());assertTrue(calls.isEmpty());
    }
    @Test void selectedCustomerRevocationBeforeDispatchAndBeforeDeliveryDoesNotLeakResult() {
        var provider = selectedCustomers();var initial = session.get();
        afterList = () -> session.set(null);
        assertEquals("NOT_AUTHORIZED", provider.query(selectedRequest("C005")).error().failureClass());
        assertTrue(calls.isEmpty());
        session.set(initial);afterList = () -> {};afterCall = () -> session.set(null);
        var result = provider.query(selectedRequest("C006"));
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());assertTrue(result.data().isEmpty());
        assertEquals(1, calls.size());assertTrue(active.isEmpty());
    }
    @Test void selectedCustomerCannotAcceptEvenAnotherAllowlistedCustomersResponse() {
        var provider = selectedCustomers();
        mutateResult = data -> {
            var profile = new LinkedHashMap<>((Map<String, Object>) data.get("profile"));
            profile.put("customerId", "C006");data.put("profile", profile);
        };
        var result = provider.query(selectedRequest("C005"));
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());assertTrue(result.data().isEmpty());
        assertEquals(1, calls.size());
    }
    CuratedMcpProvider observed(java.util.function.Supplier<ObservationRun> run,
            java.util.function.Consumer<ManagedProviderFactoryV01.HttpAttempt> attempts) {
        return new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(2), session::get, run, attempts), config());
    }
    @Test void httpEvidenceHasExplicitParentsAndNoCustomerContent() throws Exception {
        var events = new CopyOnWriteArrayList<ObservationEventV01>();
        var attempts = new CopyOnWriteArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var run = new ObservationRun("request", "boot", "instance", e -> { events.add(e); return true; });
        try (var parent = run.bindParent("capability-parent")) {
            assertEquals(CapabilityResultV1.Status.COMPLETED, observed(() -> run, attempts::add)
                    .query(request("get_wealth_policy_basis", Map.of())).status());
        }
        var exchanges = events.stream().filter(e -> e.component().equals("provider-mcp")).toList();
        assertEquals(2, exchanges.size());
        assertTrue(exchanges.stream().allMatch(e -> "capability-parent".equals(e.parentSpanId())));
        var ends = events.stream().filter(e -> e.component().equals("provider-http") && e.phase() == ObservationEventV01.Phase.END).toList();
        assertFalse(ends.isEmpty());
        assertTrue(ends.stream().allMatch(e -> e.parentSpanId().equals(exchanges.getFirst().spanId())));
        assertTrue(ends.stream().anyMatch(e -> "200".equals(e.links().get("httpStatus"))
                && e.dispatch() == ObservationEventV01.Dispatch.SENT));
        assertTrue(attempts.stream().anyMatch(a -> a.method().equals("DELETE") && a.status() == 200));
        assertTrue(attempts.stream().allMatch(a -> a.route().equals("/mcp")));
        String serialized = HarnessJson.mapper().writeValueAsString(events);
        for (String forbidden : List.of("bank-secret", "host-customer", "C005", "rules", "Mcp-Session-Id"))
            assertFalse(serialized.contains(forbidden));
        int count = events.size(); Thread.sleep(50); assertEquals(count, events.size(), "Events must close before Provider returns");
    }
    @Test void observationFailuresDoNotRewriteCompletedReadsOrRetry() {
        var run = new ObservationRun("request", "boot", "instance", e -> { throw new IllegalStateException("SINK_FAILED"); });
        assertEquals(CapabilityResultV1.Status.COMPLETED, observed(() -> run, e -> { throw new IllegalStateException("ATTEMPT_FAILED"); })
                .query(request("get_wealth_policy_basis", Map.of())).status());
        assertTrue(run.droppedEvents() > 0); assertEquals(1, calls.size());
        assertEquals(CapabilityResultV1.Status.COMPLETED, observed(() -> { throw new IllegalStateException("SUPPLIER_FAILED"); }, e -> {})
                .query(request("get_wealth_policy_basis", Map.of())).status());
        assertEquals(2, calls.size());
    }
    @Test void rejectedAuthorityHasNoHttpAttemptOrSyntheticObservation() {
        session.set(null);
        var events = new ArrayList<ObservationEventV01>(); var attempts = new ArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var run = new ObservationRun("request", "boot", "instance", e -> { events.add(e); return true; });
        assertEquals(CapabilityResultV1.Status.FAILED, observed(() -> run, attempts::add)
                .query(request("get_wealth_policy_basis", Map.of())).status());
        assertTrue(events.isEmpty()); assertTrue(attempts.isEmpty()); assertEquals(0, requests.get());
    }
    @Test void connectionFailureDoesNotClaimRequestWasSent() {
        server.stop(0);
        var events = new CopyOnWriteArrayList<ObservationEventV01>();
        var run = new ObservationRun("request", "boot", "instance", e -> { events.add(e); return true; });
        assertEquals(CapabilityResultV1.Status.FAILED, observed(() -> run, e -> fail("No response received"))
                .query(request("get_wealth_policy_basis", Map.of())).status());
        assertTrue(events.stream().anyMatch(e -> e.component().equals("provider-http") && e.phase() == ObservationEventV01.Phase.END));
        assertTrue(events.stream().noneMatch(e -> e.dispatch() == ObservationEventV01.Dispatch.SENT));
        assertTrue(events.stream().noneMatch(e -> e.links().containsKey("httpStatus")));
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
    @Test void sourceAccessDenialIsNotAnOrdinaryUpstreamFailure() {
        for (int status : List.of(401, 403)) for (String phase : List.of("tools/list", "tools/call")) {
            calls.clear();
            attack = (method, exchange) -> {
                if (!phase.equals(method)) return false;
                exchange.sendResponseHeaders(status, -1);
                return true;
            };
            var result = provider().query(request("get_wealth_policy_basis", Map.of()));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("NOT_AUTHORIZED", result.error().failureClass(), status + ":" + phase);
            assertTrue(result.data().isEmpty());
            assertEquals(phase.equals("tools/call") ? 1 : 0, calls.size());
            assertTrue(active.isEmpty(), "Source refusal must not skip owned session cleanup");
        }
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
    @Test void oversizedBodiesFailEverySdkEntryPointWithoutExecutionEvidence() {
        for (String stage : List.of("initialize", "tools/list", "tools/call", "DELETE")) {
            for (String mode : List.of("json-fixed", "json-chunked", "sse-line", "sse-event", "sse-comments", "error-body")) {
                int before = calls.size();
                attack = (method, exchange) -> {
                    if (!method.equals(stage)) return false;
                    String chunk = switch (mode) {
                        case "sse-line" -> "data: " + "x".repeat(4096);
                        case "sse-event" -> "data: " + "x".repeat(4096) + "\n";
                        case "sse-comments" -> ": " + "x".repeat(4096) + "\n";
                        default -> "x".repeat(4096);
                    };
                    exchange.getResponseHeaders().set("Content-Type", mode.startsWith("sse") ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(mode.equals("error-body") ? 500 : 200,
                            mode.equals("json-fixed") ? 16 * 1024 * 1024 : 0);
                    try {
                        byte[] bytes = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        for (int i = 0; i < 4096; i++) { exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush(); }
                    } catch (java.io.IOException cancelled) { /* The client must stop reading before EOF. */ }
                    return true;
                };
                var result = assertTimeoutPreemptively(Duration.ofSeconds(4), () ->
                        provider(Duration.ofMillis(800)).query(request("get_wealth_policy_basis", Map.of())), stage + ":" + mode);
                assertEquals(CapabilityResultV1.Status.FAILED, result.status(), stage + ":" + mode);
                assertTrue(result.data().isEmpty());assertNull(result.task());
                if (stage.equals("initialize") || stage.equals("tools/list")) assertEquals(before, calls.size());
            }
        }
    }
    @Test void backgroundGetOverflowPoisonsTheInvocation() throws Exception {
        var rejected = new CountDownLatch(1);
        attack = (method, exchange) -> {
            if (!method.equals("GET")) return false;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");exchange.sendResponseHeaders(200, 0);
            try {
                byte[] chunk = (":" + "x".repeat(4096) + "\n").getBytes();
                for (int i = 0; i < 4096; i++) { exchange.getResponseBody().write(chunk);exchange.getResponseBody().flush(); }
            } catch (java.io.IOException cancelled) { rejected.countDown(); }
            return true;
        };
        afterList = () -> {
            try { assertTrue(rejected.await(2, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt();throw new AssertionError(error); }
        };
        var result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        assertTrue(rejected.await(2, TimeUnit.SECONDS));assertTrue(active.isEmpty());
    }
    @RepeatedTest(5) void slowBodyDeadlineCancelsSocketAndReclaimsSession() throws Exception {
        var started = new CountDownLatch(1);var disconnected = new CountDownLatch(1);
        installSlowBody(started, disconnected);
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                provider(Duration.ofMillis(500)).query(request("get_wealth_policy_basis", Map.of())));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        assertTrue(disconnected.await(2, TimeUnit.SECONDS));assertEquals(1, deletes.get());assertTrue(active.isEmpty());
    }
    @RepeatedTest(5) void callerInterruptionCancelsSocketAndPreservesInterruptAfterCleanup() throws Exception {
        var started = new CountDownLatch(1);var disconnected = new CountDownLatch(1);
        installSlowBody(started, disconnected);
        var result = new AtomicReference<CapabilityResultV1>();var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            result.set(provider().query(request("get_wealth_policy_basis", Map.of())));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            caller.start();assertTrue(started.await(3, TimeUnit.SECONDS));caller.interrupt();caller.join(3000);
            assertFalse(caller.isAlive());assertTrue(interrupted.get());
            assertEquals(CapabilityResultV1.Status.FAILED, result.get().status());assertTrue(result.get().data().isEmpty());
            assertTrue(disconnected.await(2, TimeUnit.SECONDS));assertEquals(1, deletes.get());assertTrue(active.isEmpty());
        } finally { caller.interrupt();caller.join(4000); }
    }
    private void installSlowBody(CountDownLatch started, CountDownLatch disconnected) {
        attack = (method, exchange) -> {
            if (!method.equals("tools/call")) return false;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");exchange.sendResponseHeaders(200, 0);
            started.countDown();
            try {
                for (int i = 0; i < 300; i++) {
                    exchange.getResponseBody().write(": keepalive\n".getBytes());exchange.getResponseBody().flush();
                    try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt();break; }
                }
            } catch (java.io.IOException cancelled) { disconnected.countDown(); }
            return true;
        };
    }
    @Test void malformedOutputNeverGetsExecutionEvidence() {
        for (boolean structured : List.of(false, true)) {
        structuredContent = structured;
        for (var mutation : List.<java.util.function.Consumer<Map<String, Object>>>of(
                data -> data.remove("policyVersion"), data -> data.put("rules", "not-an-array"),
                data -> data.put("rules", List.of(1)), data -> data.put("synthetic", "true"),
                data -> data.put("decision", "APPROVED"))) {
            mutateResult = mutation;
            var result = provider().query(request("get_wealth_policy_basis", Map.of()));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        }
        }
        assertTrue(active.isEmpty());
    }
    @Test void rejectedInitializeCannotDeleteASessionTheSdkNeverAccepted() {
        attack = (method, exchange) -> {
            if (!method.equals("initialize")) return false;
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, BoundedHttpClient.MAX_BODY_BYTES + 1L);
            return true;
        };
        var result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        assertEquals(1, sessions.size());assertEquals(0, deletes.get());assertEquals(1, active.size());
        assertEquals(0, calls.size());
    }
    @Test void oversizedInitializeWithoutSessionIdCannotInventACleanupTarget() {
        for (boolean chunked : List.of(false, true)) {
            attack = (method, exchange) -> {
                if (!method.equals("initialize")) return false;
                exchange.getResponseHeaders().remove("Mcp-Session-Id");
                oversizedInitialize(exchange, chunked);return true;
            };
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    provider(Duration.ofMillis(500)).query(request("get_wealth_policy_basis", Map.of())));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        }
        assertEquals(2, sessions.size(), "No reinitialize/tool retries");assertEquals(2, active.size());
        assertTrue(calls.isEmpty());assertTrue(attemptedDeletes.isEmpty());
    }
    @Test void validInitializeHeaderCanNameAnExistingSharedSessionSoHeaderOnlyCleanupStaysBlocked() {
        String shared = UUID.randomUUID().toString();active.add(shared);
        assertTrue(shared.length() <= 256 && shared.chars().allMatch(c -> c >= 0x21 && c <= 0x7e));
        initializeSessionId = () -> shared;
        for (boolean chunked : List.of(false, true)) {
            attack = (method, exchange) -> {
                if (!method.equals("initialize")) return false;
                oversizedInitialize(exchange, chunked);return true;
            };
            var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    provider(Duration.ofMillis(500)).query(request("get_wealth_policy_basis", Map.of())));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
            assertEquals(Set.of(shared), active, "An unaccepted response header is not proof of new-session ownership");
        }
        assertEquals(List.of(shared, shared), sessions);assertTrue(attemptedDeletes.isEmpty());assertTrue(calls.isEmpty());
    }
    @Test void sdkDeleteFailureIsBoundedNotRetriedAndDoesNotDeleteOtherSessions() {
        String shared = UUID.randomUUID().toString();active.add(shared);
        beforeDelete = (method, exchange) -> { exchange.sendResponseHeaders(503, -1);return true; };
        var result = assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                provider(Duration.ofMillis(500)).query(request("get_wealth_policy_basis", Map.of())));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        assertEquals(1, sessions.size());assertEquals(1, calls.size());
        assertEquals(List.of(sessions.getFirst()), attemptedDeletes, "No SDK/fallback double DELETE or retry");
        assertEquals(0, deletes.get());assertEquals(Set.of(shared, sessions.getFirst()), active);
    }
    @Test void normalCloseDeletesExactlyThisInvocationSessionOnce() {
        String shared = UUID.randomUUID().toString();active.add(shared);
        for (boolean useSse : List.of(false, true)) {
            sse = useSse;
            var result = provider().query(request("get_wealth_policy_basis", Map.of()));
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
            assertEquals(Set.of(shared), active);
        }
        assertEquals(2, new HashSet<>(sessions).size());assertEquals(sessions, attemptedDeletes);
        assertEquals(sessions, deletedSessions);assertFalse(attemptedDeletes.contains(shared));
    }
    private static void oversizedInitialize(com.sun.net.httpserver.HttpExchange exchange, boolean chunked) throws java.io.IOException {
        exchange.getResponseHeaders().set("Content-Type", chunked ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, chunked ? 0 : BoundedHttpClient.MAX_BODY_BYTES + 1L);
        if (chunked) {
            byte[] bytes = ("data: " + "x".repeat(4096) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try {
                for (int i = 0; i < 1024; i++) { exchange.getResponseBody().write(bytes);exchange.getResponseBody().flush(); }
            } catch (java.io.IOException cancelled) { /* Expected ingress cancellation before a complete SSE event. */ }
        }
    }
    @Test void rejectedDeleteStatusAndEncodedBodiesFailClosed() {
        for (String mode : List.of("delete-status", "encoded", "redirect")) {
            attack = (method, exchange) -> {
                if (!method.equals(mode.equals("delete-status") ? "DELETE" : "tools/call")) return false;
                if (mode.equals("encoded")) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                if (mode.equals("redirect")) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:1/forbidden");
                exchange.sendResponseHeaders(mode.equals("delete-status") ? 500 : mode.equals("redirect") ? 302 : 200, -1);
                return true;
            };
            var result = provider().query(request("get_wealth_policy_basis", Map.of()));
            assertEquals(CapabilityResultV1.Status.FAILED, result.status());assertTrue(result.data().isEmpty());
        }
        assertTrue(active.isEmpty());
    }
    @Test void contradictoryTextAndStructuredContentAreRejected() {
        attack = (method, exchange) -> {
            if (!method.equals("tools/call")) return false;
            var call = calls.getLast();
            var good = fixture(CuratedTools.named("get_wealth_policy_basis"), call.path("params").path("arguments"), false);
            byte[] body = HarnessJson.mapper().writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", call.get("id"), "result",
                    Map.of("isError", false, "structuredContent", good, "content", List.of(Map.of("type", "text", "text", "{\"approved\":true}")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);return true;
        };
        var result = provider().query(request("get_wealth_policy_basis", Map.of()));
        assertEquals("MCP_CONTRACT_REJECTED", result.error().sourceCode());assertTrue(result.data().isEmpty());assertTrue(active.isEmpty());
    }
    @Test void concurrentCallsKeepIngressBudgetsAndSessionsIsolated() throws Exception {
        sse = true;
        try (var workers = Executors.newFixedThreadPool(8)) {
            var provider = provider();var results = new ArrayList<Future<CapabilityResultV1>>();
            for (int i = 0; i < 16; i++) results.add(workers.submit(() -> provider.query(request("get_wealth_policy_basis", Map.of()))));
            for (var result : results) assertEquals(CapabilityResultV1.Status.COMPLETED, result.get(5, TimeUnit.SECONDS).status());
        }
        assertEquals(16, calls.size());assertEquals(16, new HashSet<>(sessions).size());
        assertEquals(16, deletes.get());assertTrue(active.isEmpty());
    }
    @Test void managedSourceModeAndIdentityAreMandatoryAndChangeTheDigest(@TempDir Path directory) throws Exception {
        var legacy = config();
        assertThrows(IllegalArgumentException.class, () -> new CuratedSourceConfig("curated-synthetic-source.v2",
                endpoint, "1.0.0", legacy.toolSchemaPins(), legacy.subjects()));
        assertThrows(IllegalArgumentException.class, () -> new CuratedSourceConfig.RuntimePolicy("STATEFUL", "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new CuratedSourceConfig.RuntimePolicy("STATELESS_BOUNDED", "unknown"));
        var source = new CuratedSourceConfig("curated-synthetic-source.v2", endpoint, "1.0.0",
                legacy.toolSchemaPins(), legacy.subjects(), new CuratedSourceConfig.RuntimePolicy("STATELESS_BOUNDED", "1".repeat(64)));
        var changed = new CuratedSourceConfig(source.schemaVersion(), endpoint, "1.0.0",
                legacy.toolSchemaPins(), legacy.subjects(), new CuratedSourceConfig.RuntimePolicy("STATELESS_BOUNDED", "2".repeat(64)));
        assertNotEquals(legacy.canonicalDigest(), source.canonicalDigest());
        assertNotEquals(source.canonicalDigest(), changed.canonicalDigest());
        Path file = directory.resolve("source-v2.json");
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(source);Files.write(file, bytes);
        assertEquals(source, CuratedSourceConfig.load(file, CuratedSourceConfig.sha(bytes)));
    }
    @Test void configRequiresExactPinAndSafeEndpoint(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("source.json");byte[] bytes = HarnessJson.mapper().writeValueAsBytes(config());Files.write(file, bytes);
        assertEquals(config(), CuratedSourceConfig.load(file, CuratedSourceConfig.sha(bytes)));
        assertThrows(IllegalArgumentException.class, () -> CuratedSourceConfig.load(file, "0".repeat(64)));
        var config = config();
        assertThrows(IllegalArgumentException.class, () -> new CuratedSourceConfig(config.schemaVersion(), URI.create("http://example.com/mcp"), config.contractVersion(), config.toolSchemaPins(), config.subjects()));
        assertThrows(IllegalArgumentException.class, () -> new CuratedMcpFactoryV01(Map.of()).create(
                new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", endpoint, Duration.ofSeconds(1), session::get, () -> null, ignored -> {}), Map.of()));
    }
}
