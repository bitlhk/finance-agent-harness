package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.providers.curatedmcp.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the native Runner and registered tools, not a local simulation of the Agent loop. */
@EnabledIfSystemProperty(named = "curated.sourceConfig", matches = ".+")
class CuratedNativeQueryTest {
    @ParameterizedTest @ValueSource(strings = {"customer", "insurance", "research", "audit", "transfer-risk", "post-loan"})
    @Timeout(60) void allPackToolsUseActualSource(String suffix) throws Exception { run(suffix, false); }
    @RepeatedTest(3) @Timeout(60) void researchContinuationCarriesBothFacts() throws Exception { run("research", false); }
    @Test @Timeout(30) void revokedSourceResultCannotEnterNextModelHttp() throws Exception { run("research", true); }

    private void run(String suffix, boolean revoke) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("../..").normalize();
        Path output = Path.of(System.getProperty("curated.evidenceDirectory")); Files.createDirectories(output);
        var pack = new FinanceCapabilityPackLoader().load(root.resolve("capability-packs/curated-synthetic-" + suffix + "/pack.yaml"));
        var tools = pack.capabilities().stream().map(c -> CuratedTools.named(c.toolName().substring("curated_".length()))).toList();
        var config = CuratedSourceConfig.load(Path.of(System.getProperty("curated.sourceConfig")), System.getProperty("curated.sourceConfigSha256"));
        var expected = HarnessJson.mapper().readTree(Files.readAllBytes(Path.of(System.getProperty("curated.expectedFacts"))));
        String principal = "curated-actor", subject = "curated-subject", session = "curated-" + UUID.randomUUID();
        var requests = new CopyOnWriteArrayList<String>();
        var events = new CopyOnWriteArrayList<ObservationEventV01>();
        var results = new CopyOnWriteArrayList<CapabilityResultV1>();
        var run = new ObservationRun("request-" + session, "isolated-boot", "curated-oj-test", e -> { events.add(e); return true; });
        var scopes = tools.stream().map(CuratedTools.Tool::scope).collect(java.util.stream.Collectors.toSet());
        var grant = new AtomicReference<>(new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of(subject), Set.of("synthetic-evaluation"), scopes, session, "isolated-test", Instant.now().plusSeconds(300)));
        var sourceSession = new ManagedProviderFactoryV01.SourceSession("synthetic-not-forwarded", subject, principal, session, Instant.now().plusSeconds(300));
        var source = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(5), () -> sourceSession, () -> run, ignored -> {}), config);
        var bound = new BoundFinanceProvider(source, grant::get, new CuratedMcpFactoryV01().requirements());
        var observed = new ObservedFinanceProvider(bound, CuratedMcpProvider.ID, () -> run);
        FinanceProvider capture = new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                var result = observed.query(request); results.add(result);
                if (revoke) grant.set(null);
                return result;
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new AssertionError("QUERY_ONLY"); }
        };
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int index = requests.size(); requests.add(raw);
            Map<String, Object> message;
            if (index < tools.size()) {
                var tool = tools.get(index);
                var params = Map.of(tool.selector(), tool.selector().equals("customer_id") ? subject : tool.defaultRef());
                message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "curated-call-" + index, "type", "function",
                        "function", Map.of("name", tool.toolName(), "arguments", HarnessJson.mapper().writeValueAsString(params)))));
            } else message = Map.of("role", "assistant", "content", "Read completed; preserve source gaps and decisions.");
            byte[] body = HarnessJson.mapper().writeValueAsBytes(Map.of("id", "controlled", "model", "controlled",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", index < tools.size() ? "tool_calls" : "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        OpenJiuwenCapabilityInstaller.Installation installation = null;
        var adapter = new OpenJiuwenObservationAdapter();
        String status = "FAIL", failure = "";
        Object answer = null;
        server.start();
        try {
            var agent = new ReActAgent(AgentCard.builder().id(session).name("Isolated curated native probe").build());
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var model = ReActAgentConfig.builder().maxIterations(tools.size() + 2)
                    .promptTemplate(List.of(Map.of("role", "system", "content", "Synthetic controlled test only."))).build()
                    .configureModelClient("OpenAI", "fixture-key", base, "controlled", true);
            model.setModelClientConfig(ModelClientConfig.builder().clientProvider("OpenAI").apiKey("fixture-key").apiBase(base)
                    .timeout(5).maxRetries(0).verifySsl(true).build());
            agent.configure(model);
            var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults(principal, subject, FinanceContextV1.PrincipalType.CUSTOMER,
                    "isolated", "synthetic-evaluation", FinanceContextV1.AuthenticationLevel.STRONG, scopes,
                    () -> grant.get() == null ? Set.of() : grant.get().scopes());
            var bindings = new LinkedHashMap<String, OpenJiuwenCapabilityInstaller.ProviderBinding>();
            pack.capabilities().forEach(c -> bindings.put(c.capabilityId(),
                    new OpenJiuwenCapabilityInstaller.ProviderBinding(CuratedMcpProvider.ID, CuratedMcpProvider.VERSION, capture)));
            installation = new OpenJiuwenCapabilityInstaller().install(agent, bindings, defaults, pack, true,
                    new TaskCheckpointStore.Memory(), session);
            assertEquals(tools.stream().map(CuratedTools.Tool::toolName).collect(java.util.stream.Collectors.toSet()), installation.toolNames());
            agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL, context -> {
                if (grant.get() == null) context.requestForceFinish(Map.of("error", "AUTHORIZATION_REJECTED"));
            }, 0);
            adapter.attach(run, "host-parent");
            for (var event : List.of(AgentCallbackEvent.BEFORE_MODEL_CALL, AgentCallbackEvent.AFTER_MODEL_CALL, AgentCallbackEvent.ON_MODEL_EXCEPTION,
                    AgentCallbackEvent.BEFORE_TOOL_CALL, AgentCallbackEvent.AFTER_TOOL_CALL, AgentCallbackEvent.ON_TOOL_EXCEPTION))
                agent.registerCallback(event, adapter::observe, 100);
            answer = Runner.runAgent(agent, Map.of("query", "Read the declared synthetic resources."), session, null);
            assertEquals(revoke ? 1 : tools.size(), results.size());
            assertEquals(revoke ? 1 : tools.size() + 1, requests.size());
            for (int i = 0; i < results.size(); i++) {
                var result = results.get(i); var tool = tools.get(i);
                assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), result.toString());
                assertEquals(expected.path(tool.name()), HarnessJson.mapper().valueToTree(result.data().get("sourceResult")));
                assertEquals(config.toolSchemaPins().get(tool.name()), ((Map<?, ?>) result.data().get("mcpExecution")).get("schemaSha256"));
                assertEquals(config.runtimePolicy().sourceIdentitySha256(), ((Map<?, ?>) result.data().get("sourceIdentity")).get("sha256"));
            }
            if (!revoke) {
                assertTrue(requests.getLast().contains("sourceResult"));
                if (suffix.equals("research")) {
                    assertTrue(requests.getLast().contains("DOC001"));
                    assertTrue(requests.getLast().contains("consensus"));
                }
            }
            var nativeSpans = events.stream().filter(e -> e.component().equals("runtime-tool")).map(ObservationEventV01::spanId).toList();
            var capabilityEvents = events.stream().filter(e -> e.component().equals("capability")).toList();
            assertFalse(capabilityEvents.isEmpty());
            assertTrue(capabilityEvents.stream().allMatch(e -> nativeSpans.contains(e.parentSpanId())));
            assertTrue(events.stream().anyMatch(e -> e.component().equals("provider-http") && "200".equals(e.links().get("httpStatus"))));
            assertEquals(0, run.droppedEvents()); status = "PASS";
        } catch (Exception | AssertionError error) { failure = error.toString(); throw error; }
        finally {
            adapter.detach();
            var row = new LinkedHashMap<String, Object>();
            row.put("status", status); row.put("failure", failure); row.put("test", suffix + ":revoked=" + revoke);
            row.put("modelHttp", requests); row.put("sourceResults", results); row.put("observations", events); row.put("answer", answer);
            row.put("sourceConfigDigest", System.getProperty("curated.sourceConfigSha256"));
            row.put("installation", installation == null ? null : installation.receipt());
            row.put("scope", "NATIVE_CURATED_COVERAGE_NOT_QUALIFICATION"); row.put("realModel", false);
            try { Files.write(output.resolve("openjiuwen-" + UUID.randomUUID() + ".json"), HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(row)); }
            finally {
                if (installation != null) installation.close();
                Runner.release(session); server.stop(0); workers.shutdownNow();
            }
        }
    }
}
