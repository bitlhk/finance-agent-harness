package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Controlled transport assertions, not model-quality or raw-response certification. */
class RuntimeToolChainBoundaryTest {
    @Test @Timeout(30) void bothRunnerModesCarryActualToolResultToNextModelCall() throws Exception {
        run(false, false); run(true, false);
    }
    @Test @Timeout(30) void revocationAfterToolResultBlocksNextHttpInBothModes() throws Exception {
        run(false, true); run(true, true);
    }

    private void run(boolean streaming, boolean revoke) throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        var queries = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(request);
            boolean wireStream = HarnessJson.mapper().readTree(request).path("stream").asBoolean();
            boolean first = requests.size() == 1;
            Map<String, Object> message = first
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "synthetic-call-1",
                            "type", "function", "function", Map.of("name", "account_balance_query", "arguments", "{}"))))
                    : Map.of("role", "assistant", "content", "synthetic final answer");
            String body;
            if (wireStream) {
                if (first) message = Map.of("role", "assistant", "tool_calls", List.of(Map.of("index", 0,
                        "id", "synthetic-call-1", "type", "function",
                        "function", Map.of("name", "account_balance_query", "arguments", "{}"))));
                body = "data: " + HarnessJson.mapper().writeValueAsString(Map.of("id", "s", "model", "probe",
                        "choices", List.of(Map.of("index", 0, "delta", message)))) + "\n\n"
                        + "data: " + HarnessJson.mapper().writeValueAsString(Map.of("id", "s", "model", "probe",
                        "choices", List.of(Map.of("index", 0, "delta", Map.of(), "finish_reason", first ? "tool_calls" : "stop"))))
                        + "\n\ndata: [DONE]\n\n";
            } else body = HarnessJson.mapper().writeValueAsString(Map.of("id", "s", "model", "probe",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", first ? "tool_calls" : "stop"))));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", wireStream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String session = "tool-qualification-" + UUID.randomUUID();
        OpenJiuwenCapabilityInstaller.Installation installation = null;
        try {
            var agent = new ReActAgent(AgentCard.builder().id(session).name("Synthetic tool qualification").build());
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var config = ReActAgentConfig.builder().maxIterations(3)
                    .promptTemplate(List.of(Map.of("role", "system", "content", "Synthetic test only")))
                    .build().configureModelClient("OpenAI", "synthetic", base, "probe", true);
            config.setModelClientConfig(ModelClientConfig.builder().clientProvider("OpenAI").apiKey("synthetic")
                    .apiBase(base).timeout(5).maxRetries(0).verifySsl(true).build());
            agent.configure(config);
            FinanceProvider provider = new FinanceProvider() {
                public CapabilityResultV1 query(CapabilityRequestV1 request) {
                    queries.incrementAndGet();
                    return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                            CapabilityResultV1.Status.COMPLETED, "synthetic source", Map.of("availableBalance", "12345.67", "currency", "CNY"),
                            List.of(), List.of(), "synthetic-bank", Instant.parse("2026-09-12T00:00:00Z"), null, null);
                }
                public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                    throw new AssertionError("Read-only installation must not execute a task");
                }
            };
            var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults("synthetic-customer", "synthetic-customer",
                    FinanceContextV1.PrincipalType.CUSTOMER, session, "RETAIL_BANKING",
                    FinanceContextV1.AuthenticationLevel.STRONG, Set.of("account:read"));
            Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
            while (root != null && !Files.isDirectory(root.resolve("capability-packs"))) root = root.getParent();
            assertNotNull(root);
            installation = new OpenJiuwenCapabilityInstaller().install(agent, provider, "synthetic-bank", defaults,
                    new FinanceCapabilityPackLoader().load(root.resolve("capability-packs/account-balance-readonly/pack.yaml")), true);
            agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL, context -> {
                if (revoke && queries.get() > 0) context.requestForceFinish(Map.of("error", "AUTHORIZATION_REJECTED"));
            }, 0);
            if (streaming) {
                var chunks = Runner.runAgentStreaming(agent, Map.of("query", "synthetic balance"), session, null, List.of(StreamMode.OUTPUT));
                while (chunks.hasNext()) chunks.next();
            } else Runner.runAgent(agent, Map.of("query", "synthetic balance"), session, null);
            assertEquals(1, queries.get(), "provider query count");
            assertEquals(revoke ? 1 : 2, requests.size(), "actual model HTTP count");
            if (!revoke) {
                assertTrue(requests.get(1).contains("12345.67"));
                assertTrue(requests.get(1).contains("synthetic-call-1"));
                assertTrue(requests.get(1).contains("synthetic-bank"));
            }
            System.out.printf("TOOL_CHAIN streaming=%s revoked=%s queries=%d http=%d%n", streaming, revoke, queries.get(), requests.size());
        } finally {
            if (installation != null) installation.close();
            Runner.release(session); server.stop(0);
        }
    }
}
