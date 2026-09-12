package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.schema.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelSnapshotClientTest {
    @Test void concurrentScopesAreIsolatedAndResponseExcludesReasoning() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"answer\",\"reasoning_content\":\"HIDDEN_SYNTHETIC\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<List<String>>>();
            for (String marker : List.of("OWNER_A_MARKER", "OWNER_B_MARKER")) futures.add(pool.submit(() -> {
                var records = new ArrayList<String>();
                var client = client(server.getAddress().getPort());
                try (var scope = ModelSnapshotClient.scope((call, boundary, state, value) -> {
                    try { records.add(boundary + HarnessJson.mapper().writeValueAsString(value)); }
                    catch (Exception failed) { throw new IllegalStateException(); }
                })) { invoke(client, marker); }
                assertEquals(2, records.size());
                invoke(client, "NO_SCOPE");
                assertEquals(2, records.size());
                return records;
            }));
            var a = futures.get(0).get(10, TimeUnit.SECONDS).toString();
            var b = futures.get(1).get(10, TimeUnit.SECONDS).toString();
            assertTrue(a.contains("OWNER_A_MARKER")); assertFalse(a.contains("OWNER_B_MARKER"));
            assertTrue(b.contains("OWNER_B_MARKER")); assertFalse(b.contains("OWNER_A_MARKER"));
            assertFalse(a.contains("HIDDEN_SYNTHETIC")); assertFalse(b.contains("HIDDEN_SYNTHETIC"));
            try (var scope = ModelSnapshotClient.scope((c, boundary, state, value) -> { throw new IllegalStateException("PRIVATE_ERROR"); })) {
                assertNotNull(invoke(client(server.getAddress().getPort()), "OBSERVER_FAIL"));
            }
        } finally { server.stop(0); }
    }
    private ModelSnapshotClient client(int port) {
        return new ModelSnapshotClient(ModelRequestConfig.builder().modelName("probe").build(),
                ModelClientConfig.builder().clientProvider("OpenAI").apiKey("synthetic-secret")
                        .apiBase("http://127.0.0.1:" + port + "/v1").timeout(5).maxRetries(0).build());
    }
    private AssistantMessage invoke(ModelSnapshotClient client, String marker) throws Exception {
        return client.invoke(List.of(Map.of("role", "user", "content", marker)), List.of(), null, null,
                null, null, null, null, null, Map.of());
    }
}
