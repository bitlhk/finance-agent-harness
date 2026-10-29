package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class ConversationResponderSdkTest {
    @Test @Timeout(30)
    void realSdkDeliversTextBeforeTheFixtureFinishesItsStream() throws Exception {
        var received = new CountDownLatch(1);
        var receivedBeforeFinish = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好。\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { receivedBeforeFinish.set(received.await(2, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                output.write(("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"我可以说明已装配能力。\"}}]}\n\n"
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var responder = new ConversationResponder(new ManagedScenarioExtensionV01.ModelConnection(
                    "OpenAI", "synthetic-only", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "synthetic"));
            var seen = new ArrayList<String>();
            String answer = responder.respond("你是谁", new ConversationResponder.Context(
                    ConversationResponder.Purpose.CAPABILITIES, "回访准备", List.of(), "尚未执行"),
                    () -> false, () -> {}, text -> { seen.add(text); received.countDown(); });
            assertTrue(receivedBeforeFinish.get(), "SDK must not buffer the entire answer before first release");
            assertEquals("你好。", seen.getFirst());
            assertEquals("你好。我可以说明已装配能力。", answer);
        } finally {
            server.stop(0);
        }
    }

    @Test @Timeout(30)
    void realSdkUsesToolFreeModelAndPrecheckFailureMakesNoHttpRequest() throws Exception {
        var requests = new CopyOnWriteArrayList<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(HarnessJson.mapper().readTree(exchange.getRequestBody()));
            String chunk = HarnessJson.mapper().writeValueAsString(Map.of("id", "synthetic",
                    "choices", List.of(Map.of("index", 0, "delta",
                            Map.of("content", "我是客户经理助手，可以协助准备回访。")))));
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
            var responder = new ConversationResponder(new ManagedScenarioExtensionV01.ModelConnection(
                    "OpenAI", "synthetic-only", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "synthetic"));
            var context = new ConversationResponder.Context(ConversationResponder.Purpose.CAPABILITIES,
                    "准备客户回访；不能购买或修改CRM", List.of(), "尚未执行业务");
            assertThrows(SecurityException.class, () -> responder.respond("你能干啥", context, () -> false,
                    () -> { throw new SecurityException("REJECTED"); }, text -> fail("Must not release")));
            assertTrue(requests.isEmpty());
            var seen = new ArrayList<String>();
            var checks = new AtomicInteger();
            String answer = responder.respond("你能干啥", context, () -> false, checks::incrementAndGet, seen::add);
            assertEquals("我是客户经理助手，可以协助准备回访。", answer);
            assertFalse(seen.isEmpty());
            assertEquals(answer, seen.getLast());
            assertTrue(checks.get() >= 3);
            assertEquals(1, requests.size(), "Local observed HTTP, not inferred from SDK retry config");
            var request = requests.getFirst();
            assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
            assertTrue(request.path("stream").asBoolean());
            String messages = request.path("messages").toString();
            assertTrue(messages.contains("你能干啥"));
            assertTrue(messages.contains("不能购买或修改CRM"));
            assertTrue(messages.contains("不得索取身份证号、密码、验证码"));
            assertTrue(messages.contains("不能因只给出月份而再次丢失查询目标"));
            assertFalse(messages.contains("synthetic-only"), "Credential is not model context");
        } finally {
            server.stop(0);
        }
    }
}
