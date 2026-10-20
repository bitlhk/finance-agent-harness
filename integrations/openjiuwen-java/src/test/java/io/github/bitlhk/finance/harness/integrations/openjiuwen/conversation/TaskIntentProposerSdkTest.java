package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class TaskIntentProposerSdkTest {
    @Test @Timeout(30)
    void compositeMaterialsUseOneInstalledActionWithoutToolsOrAuthority() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(HarnessJson.mapper().readTree(exchange.getRequestBody()));
            String reply = """
                    {"schema":"finance-task-intent.v1","kind":"EXECUTE","actionRef":"AUDIT","objectRef":null}
                    """;
            String chunk = HarnessJson.mapper().writeValueAsString(Map.of("id", "synthetic",
                    "choices", List.of(Map.of("index", 0, "delta", Map.of("content", reply)))));
            byte[] body = ("data: " + chunk
                    + "\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"
                    + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var proposer = new TaskIntentProposer(new ManagedScenarioExtensionV01.ModelConnection(
                    "OpenAI", "synthetic-only", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "synthetic"));
            var context = HarnessJson.mapper().readTree("""
                    {"availableActions":[{"ref":"AUDIT","description":"核验已冻结的财报与申请用途合同材料"}],
                     "authorizedObjects":[],"materialsPresent":true,"pendingRequest":null}
                    """);
            var result = proposer.propose("请核验我刚冻结的财报和合同金额。", context);
            assertEquals("EXECUTE", result.path("kind").asText());
            assertEquals("AUDIT", result.path("actionRef").asText());
            assertTrue(result.path("objectRef").isNull());
            assertEquals(1, requests.size(), "Observed local fixture HTTP count, not SDK retry setting");
            var request = requests.getFirst();
            assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
            String messages = request.path("messages").toString();
            assertTrue(messages.contains("动作按availableActions中的ref区分"));
            assertTrue(messages.contains("不同动作或不同对象的歧义仍需CLARIFY"));
            assertTrue(messages.contains("EXECUTE仍需另行确认"));
            assertTrue(messages.contains("核验已冻结的财报与申请用途合同材料"));
            assertThrows(IllegalArgumentException.class, () -> TaskIntentProposer.validate(
                    result.toString().replace("\"AUDIT\"", "\"TRANSFER\""), context));
        } finally {
            server.stop(0);
        }
    }
}
