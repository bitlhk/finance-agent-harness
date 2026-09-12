package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.model_clients.OpenAiCompatibleModelClient;
import com.openjiuwen.core.foundation.llm.schema.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic client-boundary probe; not a production snapshot collector. */
class NativeModelRequestTraceTest {
    @Test void nativeRequestTraceMatchesReceivedJsonForInvokeAndStream() throws Exception {
        probe(false, false);
        probe(true, false);
    }
    @Test void requestSnapshotDoesNotImplySuccessfulResponse() throws Exception {
        probe(false, true);
    }
    private void probe(boolean stream, boolean error) throws Exception {
        var received = new AtomicReference<String>();
        var captured = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = error ? "{\"error\":{\"message\":\"synthetic failure\"}}" : stream
                    ? "data: {\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"synthetic answer\"},\"finish_reason\":null}]}\n\n"
                      + "data: {\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    : "{\"id\":\"s\",\"model\":\"probe\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic answer\"},\"finish_reason\":\"stop\"}]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(error ? 500 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new OpenAiCompatibleModelClient(ModelRequestConfig.builder().modelName("probe")
                    .temperature(0.2).maxTokens(64).build(), ModelClientConfig.builder().clientProvider("OpenAI")
                    .apiKey("synthetic-secret-not-to-record").apiBase("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .timeout(5).maxRetries(0).verifySsl(true).build());
            var messages = List.of(Map.of("role", "system", "content", "synthetic system"),
                    Map.of("role", "user", "content", "synthetic historical turn"),
                    Map.of("role", "assistant", "content", "synthetic historical answer"),
                    Map.of("role", "user", "content", "synthetic current turn"));
            var tools = List.of(Map.of("type", "function", "function", Map.of("name", "balance_probe",
                    "description", "Synthetic balance", "parameters", Map.of("type", "object", "properties", Map.of()))));
            java.util.concurrent.Callable<Object> operation = () -> client.withRequestTraceCallback(value -> {
                try { captured.set(HarnessJson.mapper().writeValueAsString(value)); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }, () -> {
                if (!stream) return client.invoke(messages, tools, null, null, null, null, null, null, null, Map.of());
                var chunks = client.stream(messages, tools, null, null, null, null, null, null, null, Map.of());
                var results = new ArrayList<AssistantMessageChunk>();
                while (chunks.hasNext()) results.add(chunks.next());
                assertFalse(results.isEmpty()); return results;
            });
            if (error) assertThrows(Exception.class, operation::call);
            else assertNotNull(operation.call());
            assertNotNull(captured.get()); assertNotNull(received.get());
            assertEquals(HarnessJson.mapper().readTree(received.get()), HarnessJson.mapper().readTree(captured.get()));
            assertFalse(captured.get().contains("synthetic-secret-not-to-record"));
            assertTrue(captured.get().contains("synthetic historical turn"));
            assertTrue(captured.get().contains("balance_probe"));
        } finally { server.stop(0); }
    }
}
