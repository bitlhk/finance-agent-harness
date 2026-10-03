package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeRunnerBoundaryTest {
    @Test @Timeout(20) void invokeAndStreamingReachControlledEndpoint() throws Exception {
        run(false, false); run(true, false);
    }
    @Test @Timeout(20) void forceFinishBlocksActualHttpInBothRunnerModes() throws Exception {
        run(false, true); run(true, true);
    }
    @Test @Timeout(20) void noToolNativeCasesDoNotShareHistory() throws Exception {
        var first = run(false, false, "CASE_ONE_9142");
        var second = run(false, false, "CASE_TWO_6158");
        assertEquals(1, first.size()); assertEquals(1, second.size());
        assertTrue(first.getFirst().toString().contains("CASE_ONE_9142"));
        assertTrue(second.getFirst().toString().contains("CASE_TWO_6158"));
        assertFalse(second.getFirst().toString().contains("CASE_ONE_9142"));
        assertFalse(second.getFirst().path("messages").toString().contains("runner-ok"));
    }
    private List<com.fasterxml.jackson.databind.JsonNode> run(boolean streaming, boolean blocked) throws Exception {
        return run(streaming, blocked, "synthetic");
    }
    private List<com.fasterxml.jackson.databind.JsonNode> run(boolean streaming, boolean blocked, String query) throws Exception {
        var calls = new AtomicInteger(); var before = new AtomicInteger(); var after = new AtomicInteger();
        var requests = new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            var request = HarnessJson.mapper().readTree(exchange.getRequestBody());
            requests.add(request);
            boolean wireStream = request.path("stream").asBoolean();
            String body = wireStream
                ? "data: {\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"runner-ok\"},\"finish_reason\":null}]}\n\ndata: {\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                : "{\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"runner-ok\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", wireStream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String session = "qualification-" + UUID.randomUUID();
        try {
            var agent = new ReActAgent(AgentCard.builder().id(session).name("Controlled qualification").build());
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            var config = ReActAgentConfig.builder().maxIterations(2)
                    .promptTemplate(List.of(Map.of("role", "system", "content", "Synthetic probe")))
                    .build().configureModelClient("OpenAI", "synthetic", base, "probe", true);
            config.setModelClientConfig(ModelClientConfig.builder().clientProvider("OpenAI").apiKey("synthetic")
                    .apiBase(base).timeout(5).maxRetries(0).verifySsl(true).build());
            agent.configure(config);
            agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL, context -> {
                before.incrementAndGet();
                if (blocked) context.requestForceFinish(Map.of("error", "AUTHORIZATION_REJECTED"));
            }, 0);
            agent.registerCallback(AgentCallbackEvent.AFTER_MODEL_CALL, context -> after.incrementAndGet(), 0);
            if (streaming) {
                var chunks = Runner.runAgentStreaming(agent, Map.of("query", query), session, null, List.of(StreamMode.OUTPUT));
                var values = new ArrayList<Object>();
                while (chunks.hasNext()) values.add(chunks.next());
                assertFalse(values.isEmpty());
            } else assertNotNull(Runner.runAgent(agent, Map.of("query", query), session, null));
            // Streaming may retry an empty force-finish result and fall back to invoke.
            // Callback count is evidence, not proof of the number of HTTP requests.
            assertTrue(before.get() >= 1);
            assertEquals(blocked ? 0 : 1, calls.get());
            for (var request : requests) {
                assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
                assertTrue(request.path("messages").toString().contains("Synthetic probe"));
            }
            System.out.printf("RUNNER_BOUNDARY streaming=%s blocked=%s before=%d after=%d http=%d%n",
                    streaming, blocked, before.get(), after.get(), calls.get());
        } finally { Runner.release(session); server.stop(0); }
        return List.copyOf(requests);
    }
}
