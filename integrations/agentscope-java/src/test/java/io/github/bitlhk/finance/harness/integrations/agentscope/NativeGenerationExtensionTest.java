package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** SDK extension probe only; it does not qualify a Host or authorize a Provider. */
class NativeGenerationExtensionTest {
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(30)
    void jsonModeIsSentBeforeAndAfterNativeToolCall(boolean jsonMode) throws Exception {
        try (var model = new ControlledModel(ControlledModel.tool("fixture_read", Map.of()),
                ControlledModel.text("{\"marker\":\"SYNTHETIC_ONLY_381\"}"))) {
            var calls = new AtomicInteger();
            var toolkit = new Toolkit();
            toolkit.registerAgentTool(new AgentTool() {
                public String getName() { return "fixture_read"; }
                public String getDescription() { return "Read the synthetic test marker."; }
                public Map<String, Object> getParameters() { return Map.of("type", "object", "properties", Map.of()); }
                public Mono<ToolResultBlock> callAsync(ToolCallParam ignored) {
                    calls.incrementAndGet();
                    return Mono.just(ToolResultBlock.of(TextBlock.builder().text("SYNTHETIC_ONLY_381").build()));
                }
            });
            var options = GenerateOptions.builder().stream(false).endpointPath("/chat/completions")
                    .executionConfig(ExecutionConfig.builder().maxAttempts(1).timeout(Duration.ofSeconds(10)).build());
            if (jsonMode) options.additionalBodyParam("response_format", Map.of("type", "json_object"));
            var client = OpenAIChatModel.builder().apiKey("synthetic-only").baseUrl(model.uri().toString())
                    .modelName("controlled").stream(false).build();
            var agent = ReActAgent.builder().name("extension-probe").sysPrompt("Read the tool and return JSON.")
                    .model(client).toolkit(toolkit).generateOptions(options.build()).maxIters(3).build();
            var answer = agent.call("Read the marker.").block(Duration.ofSeconds(20));
            assertNotNull(answer);
            assertEquals("SYNTHETIC_ONLY_381", HarnessJson.mapper().readTree(answer.getTextContent()).path("marker").asText());
            assertEquals(1, calls.get());
            assertEquals(2, model.requests.size());
            for (var sent : model.requests) {
                var request = HarnessJson.mapper().readTree(sent.get("rawBody").toString());
                assertEquals(jsonMode, request.has("response_format"));
                if (jsonMode) assertEquals("json_object", request.path("response_format").path("type").asText());
                assertTrue(request.path("tools").size() > 0);
            }
        }
    }
}
