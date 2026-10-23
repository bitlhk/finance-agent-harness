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
            assertTrue(messages.contains("也不能当成单个缺对象的EXECUTE"));
            assertTrue(messages.contains("未知名称不可近似匹配为已授权对象"));
            assertTrue(messages.contains("EXECUTE仍需另行确认"));
            assertTrue(messages.contains("untrusted-user-context.v1"));
            assertTrue(messages.contains("仅补充月份、金额等参数"));
            assertTrue(messages.contains("身份和授权由服务端核验"));
            assertTrue(messages.contains("核验已冻结的财报与申请用途合同材料"));
            // Wire contract only: semantic classification needs the frozen real-model cases.
            String policy = request.path("messages").get(0).path("content").asText();
            assertEquals("system", request.path("messages").get(0).path("role").asText());
            String[] stages = {"1. 当前意图", "2. 动作范围", "3. 对象补充", "4. 动作选择", "5. 输出引用"};
            int previous = -1;
            for (String stage : stages) {
                int index = policy.indexOf(stage);
                assertTrue(index > previous, "Missing or out-of-order decision stage: " + stage);
                previous = index;
            }
            assertTrue(policy.contains("不要将执行前置条件当成意图歧义"));
            assertTrue(policy.contains("同一ref中的多个材料或核验项目不是多个动作"));
            assertFalse(policy.contains("其余kind两个引用字段均为null"),
                    "Reference rules must name the kinds instead of depending on paragraph order");
            assertThrows(IllegalArgumentException.class, () -> TaskIntentProposer.validate(
                    result.toString().replace("\"AUDIT\"", "\"TRANSFER\""), context));
        } finally {
            server.stop(0);
        }
    }
}
