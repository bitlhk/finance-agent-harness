package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** SDK boundary probe only: this does not admit a method-only Harness installation. */
class MethodOnlySdkProbeTest {
    @Test @Timeout(30) void emptyToolkitSendsMethodAndKeepsCasesSeparate() throws Exception {
        try (var model = new ControlledModel(ControlledModel.text("first-result"), ControlledModel.text("second-result"));
             var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var transport = JdkHttpTransport.builder().client(client).build();
            try {
                var nativeModel = OpenAIChatModel.builder().apiKey("isolated-sdk-probe")
                        .baseUrl(model.uri().toString()).modelName("controlled").stream(false)
                        .httpTransport(transport).build();
                for (int i = 0; i < 2; i++) {
                    var toolkit = new Toolkit();
                    var agent = ReActAgent.builder().name("method-only-probe")
                            .sysPrompt("METHOD_ONLY_7381: explain only the supplied case; no external data is available.")
                            .toolkit(toolkit).model(nativeModel).maxIters(2).maxRetries(1)
                            .enablePendingToolRecovery(false)
                            .generateOptions(GenerateOptions.builder().stream(false).endpointPath("/chat/completions").build()).build();
                    assertTrue(agent.getToolkit().getToolNames().isEmpty());
                    var context = RuntimeContext.builder().sessionId("case-" + i).userId("controlled-owner").build();
                    var reply = agent.call(i == 0 ? "CASE_ONE_9142" : "CASE_TWO_6158", context).block(Duration.ofSeconds(10));
                    assertNotNull(reply);
                    assertEquals(i == 0 ? "first-result" : "second-result", reply.getTextContent());
                }
                assertEquals(2, model.requests.size());
                for (var row : model.requests) {
                    var body = HarnessJson.mapper().readTree(row.get("rawBody").toString());
                    assertTrue(body.path("tools").isMissingNode() || body.path("tools").isEmpty());
                    assertTrue(body.path("messages").toString().contains("METHOD_ONLY_7381"));
                    assertFalse(body.path("stream").asBoolean());
                }
                assertFalse(model.requests.get(1).get("rawBody").toString().contains("CASE_ONE_9142"));
                assertFalse(model.requests.get(1).get("rawBody").toString().contains("first-result"));
            } finally { transport.close(); }
        }
    }
}
