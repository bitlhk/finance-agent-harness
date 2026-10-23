package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class ConversationWhitespaceSdkTest {
    @Test @Timeout(30)
    void preservesStandaloneNewlinesBetweenMarkdownItemsAndParagraphs() throws Exception {
        assertWireText(List.of("可以查看：", "\n\n", "- 月度汇总", "\n", "- 分类分析", "\n\n", "尚未查询。"));
    }

    @Test @Timeout(30)
    void preservesSpacesTabsAndRepeatedIdenticalChunks() throws Exception {
        assertWireText(List.of("monthly", " ", "income", "\t", "and", " ", "expense", "\n", "\n"));
    }

    private static void assertWireText(List<String> fragments) throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var request = HarnessJson.mapper().readTree(exchange.getRequestBody());
            requests.incrementAndGet();
            assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
            assertTrue(request.path("stream").asBoolean());
            var body = new StringBuilder();
            for (String fragment : fragments) body.append("data: ").append(HarnessJson.mapper().writeValueAsString(
                    Map.of("choices", List.of(Map.of("index", 0, "delta", Map.of("content", fragment)))))).append("\n\n");
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var responder = new ConversationResponder(new ManagedScenarioExtensionV01.ModelConnection(
                    "OpenAI", "synthetic-only", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic"));
            var snapshots = new ArrayList<String>();
            var checks = new AtomicInteger();
            String answer = responder.respond("你能做什么", new ConversationResponder.Context(
                    ConversationResponder.Purpose.CAPABILITIES, "月度汇总与分类分析", List.of(), "尚未查询"),
                    () -> false, checks::incrementAndGet, snapshots::add);
            assertEquals(String.join("", fragments), answer, "SDK transport must preserve exact text, including whitespace-only deltas");
            assertEquals(answer, snapshots.getLast());
            assertEquals(1, requests.get(), "No second model call to repair formatting");
            assertTrue(checks.get() >= snapshots.size() + 2, "Pre-call and final authority/evidence checks remain required");
        } finally { server.stop(0); }
    }
}
