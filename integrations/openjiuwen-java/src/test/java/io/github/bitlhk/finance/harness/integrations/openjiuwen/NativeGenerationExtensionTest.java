package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeGenerationExtensionTest {
    @Test void frozenModeReachesActualHttpThroughNativeRunnerAndSnapshotClient() throws Exception {
        var sent = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            sent.add(HarnessJson.mapper().readTree(exchange.getRequestBody()));
            byte[] body = """
                {"id":"controlled","object":"chat.completion","model":"controlled","choices":[
                {"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Controlled public answer."}}],
                "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        String session = UUID.randomUUID().toString();
        try {
            ModelSnapshotClient.register();
            var config = ReActAgentConfig.builder().maxIterations(2).promptTemplate(List.of(
                    Map.of("role", "system", "content", "Return the controlled answer."))).build()
                    .configureModelClient(ModelSnapshotClient.PROVIDER, "fixture-only",
                            "http://127.0.0.1:" + server.getAddress().getPort(), "controlled", true);
            config.setModelClientConfig(ModelClientConfig.builder().clientProvider(ModelSnapshotClient.PROVIDER)
                    .apiKey("fixture-only").apiBase("http://127.0.0.1:" + server.getAddress().getPort())
                    .timeout(10).maxRetries(0).verifySsl(true).build());
            config.getModelConfigObj().setExtraField("thinking", Map.of("type", "disabled"));
            var agent = new ReActAgent(AgentCard.builder().id(session).name("Controlled probe").build());
            agent.configure(config);
            var captures = new ArrayList<Map<String, Object>>();
            try (var scope = ModelSnapshotClient.scope((call, boundary, state, value) -> {
                if (boundary.equals("CLIENT_REQUEST_PREPARED")) captures.add(Map.copyOf(value));
            })) {
                Runner.runAgent(agent, Map.of("query", "Return the controlled public answer."), session, null);
            }
            assertEquals(1, sent.size());
            assertEquals("disabled", sent.getFirst().path("thinking").path("type").asText());
            assertEquals(Map.of("type", "disabled"), captures.getFirst().get("thinking"));
        } finally { Runner.release(session); server.stop(0); }
    }
}
