package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

final class OpenJiuwenIntentProposerTest {
    @Test @Timeout(30) void actualSdkProducesTypedSuggestionWithNoToolsOrCrossTurnHistory() throws Exception {
        try (var fixture = new Fixture("{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"PREPARE\",\"objectRef\":\"synthetic-a\",\"reportRef\":null,\"revision\":null}")) {
            var proposer = fixture.proposer();
            for (String query : List.of("SYNTHETIC_FIRST_TASK", "SYNTHETIC_SECOND_TASK")) {
                var value = proposer.propose(query, Set.of("synthetic-a"), Set.of(ConversationIntent.Kind.PREPARE), null, null, () -> false);
                assertEquals(ConversationIntent.Kind.PREPARE, value.kind());
                assertTrue(value.requiresExecutionConfirmation());
            }
            assertEquals(2, fixture.requests.size(), "Actual fixture HTTP count, not SDK retry configuration");
            for (JsonNode request : fixture.requests) {
                assertTrue(request.path("tools").isMissingNode() || request.path("tools").isEmpty());
                assertTrue(request.path("messages").toString().contains("finance-conversation-intent.v1"));
            }
            assertFalse(fixture.requests.get(1).path("messages").toString().contains("SYNTHETIC_FIRST_TASK"));
        }
    }
    @Test @Timeout(30) void actualModelReplyCannotSupplyAuthority() throws Exception {
        try (var fixture = new Fixture("{\"schema\":\"finance-conversation-intent.v1\",\"kind\":\"PREPARE\",\"objectRef\":\"other-owner\",\"reportRef\":null,\"revision\":null}")) {
            var proposer = fixture.proposer();
            var error = assertThrows(IllegalArgumentException.class, () -> proposer.propose("synthetic query",
                    Set.of("synthetic-a"), Set.of(ConversationIntent.Kind.PREPARE), null, null, () -> false));
            assertEquals("CONVERSATION_INTENT_INVALID", error.getMessage());
            assertEquals(1, fixture.requests.size());
        }
    }
    @Test @Timeout(30) void alreadyCancelledDoesNotSendHttp() throws Exception {
        try (var fixture = new Fixture("{}")) {
            var proposer = fixture.proposer();
            assertThrows(CancellationException.class, () -> proposer.propose("synthetic query", Set.of(), Set.of(), null, null, () -> true));
            assertTrue(fixture.requests.isEmpty());
        }
    }
    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        java.util.function.Consumer<JsonNode> onRequest=ignored->{};
        int chunkCharacters = Integer.MAX_VALUE;
        Fixture(String... replies) throws Exception {
            if(replies.length==0) throw new IllegalArgumentException("FIXTURE_REPLY_REQUIRED");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                JsonNode request = HarnessJson.mapper().readTree(exchange.getRequestBody());
                requests.add(request);
                onRequest.accept(request);
                String reply=replies[Math.min(requests.size()-1,replies.length-1)];
                var frames = new StringBuilder();
                for (int offset = 0; offset < reply.length();) {
                    int end = (int)Math.min((long)reply.length(), (long)offset + chunkCharacters);
                    String chunk = HarnessJson.mapper().writeValueAsString(Map.of("id", "synthetic", "model", "synthetic",
                            "choices", List.of(Map.of("index", 0, "delta", Map.of("role", "assistant", "content", reply.substring(offset,end))))));
                    frames.append("data: ").append(chunk).append("\n\n");
                    offset = end;
                }
                String body = frames + "data: {\"id\":\"synthetic\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
        }
        OpenJiuwenIntentProposer proposer() {
            return new OpenJiuwenIntentProposer(new ManagedScenarioExtensionV01.ModelConnection("OpenAI", "synthetic-only",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic"));
        }
        public void close() { server.stop(0); }
    }
}
