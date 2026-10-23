package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.ConversationFailure;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.ModelSnapshotClient;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ConversationModelBoundaryTest {
    private static final String TEXT = "data: {\"choices\":[{\"delta\":{\"content\":\"仅普通回复\"}}]}\n\n";
    private static final String STOP = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";
    private static final String DONE = "data: [DONE]\n\n";

    @ParameterizedTest @ValueSource(strings={"eof", "length", "tool", "after-stop", "malformed", "http-error"}) @Timeout(30)
    void incompleteOrUnexpectedModelOutputCannotBecomeSuccessfulReply(String failure) throws Exception {
        String body = switch (failure) {
            case "eof" -> TEXT;
            case "length" -> TEXT + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"length\"}]}\n\n" + DONE;
            case "tool" -> "data: {\"choices\":[{\"delta\":{\"content\":\"不得释放\",\"tool_calls\":[{\"index\":0,\"id\":\"fake\",\"type\":\"function\",\"function\":{\"name\":\"forbidden\",\"arguments\":\"{}\"}}]}}]}\n\n" + STOP + DONE;
            case "after-stop" -> TEXT + STOP + TEXT + DONE;
            case "malformed" -> TEXT + "data: {not-json}\n\n";
            default -> "{\"error\":{\"message\":\"PRIVATE_ERROR_MARKER\"}}";
        };
        try (var fixture = new Fixture(failure.equals("http-error") ? 503 : 200, body)) {
            var seen = new ArrayList<String>();
            var error = assertThrows(ConversationFailure.class, () -> fixture.responder().respond(
                    "你好", context(), () -> false, () -> {}, seen::add));
            assertEquals("CONVERSATION_MODEL_STREAM_FAILED", error.getMessage());
            assertEquals("MODEL_STREAM", error.stage());
            assertFalse(error.getMessage().contains("PRIVATE"));
            assertEquals(failure.equals("tool") || failure.equals("http-error") ? List.of() : List.of("仅普通回复"), seen);
            assertEquals(1, fixture.requests.get(), "Failed reply is not automatically retried");
        }
    }

    @ParameterizedTest @ValueSource(strings={"cancel", "revoke", "evidence"}) @Timeout(30)
    void actualObservedStreamClosesOnReleaseFailureWithoutFurtherText(String failure) throws Exception {
        ModelSnapshotClient.register();
        try (var fixture = new Fixture(200, TEXT + TEXT + STOP + DONE)) {
            var events = new ArrayList<Map<String,Object>>();
            var seen = new ArrayList<String>();
            var checks = new AtomicInteger();
            try (var scope = ModelSnapshotClient.scope((call,boundary,state,value) -> {
                if (boundary.equals("MODEL_STREAM_TERMINATION")) events.add(value);
            })) {
                var responder = fixture.responder(ModelSnapshotClient.PROVIDER);
                Exception error = assertThrows(Exception.class, () -> responder.respond("你好", context(),
                        () -> false, () -> {
                            if (checks.incrementAndGet() == 3) {
                                if (failure.equals("cancel")) throw new CancellationException();
                                if (failure.equals("revoke")) throw new SecurityException("REVOKED");
                                throw new IOException("EVIDENCE_FAILED");
                            }
                        }, seen::add));
                Class<? extends Exception> expected = switch (failure) {
                    case "cancel" -> CancellationException.class;
                    case "revoke" -> SecurityException.class;
                    default -> IOException.class;
                };
                assertInstanceOf(expected, error);
            }
            assertEquals(List.of("仅普通回复"), seen);
            assertEquals(1, events.size());
            assertEquals("STREAM_CLOSE", events.getFirst().get("phase"));
            assertEquals(false, events.getFirst().get("iteratorExhaustedObserved"));
            assertEquals(1, fixture.requests.get());
        }
    }

    private static ConversationResponder.Context context() {
        return new ConversationResponder.Context(ConversationResponder.Purpose.GREETING, "查询月度汇总", List.of(), "尚未查询");
    }
    private static final class Fixture implements AutoCloseable {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server;
        Fixture(int status, String response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", status==200 ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(status,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });server.start();
        }
        ConversationResponder responder() { return responder("OpenAI"); }
        ConversationResponder responder(String provider) {
            return new ConversationResponder(new ManagedScenarioExtensionV01.ModelConnection(provider,"synthetic-only",
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic"));
        }
        public void close() { server.stop(0); }
    }
}
