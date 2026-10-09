package io.github.bitlhk.finance.harness.providers.curatedmcp;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedHttpClientTest {
    @Test void statelessPolicyRejectsSessionHeadersBeforeSdkAndDoesNotGuessCleanupOwnership() throws Exception {
        var calls = new AtomicInteger();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            calls.incrementAndGet();
            exchange.getResponseHeaders().add("Mcp-Session-Id", "a-shared-session");
            exchange.sendResponseHeaders(200, 2);
            try (var body = exchange.getResponseBody()) { body.write("{}".getBytes()); }
        });
        server.start();
        try (var builder = new BoundedHttpClient.Builder(java.time.Duration.ofSeconds(2), true)) {
            var client = builder.build();var consumed = new AtomicBoolean();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/mcp")).POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
            assertThrows(java.io.IOException.class, () -> client.send(request, info -> {
                consumed.set(true);return HttpResponse.BodySubscribers.ofString(java.nio.charset.StandardCharsets.UTF_8);
            }));
            assertFalse(consumed.get());assertEquals(1, calls.get());
            assertThrows(IllegalStateException.class, () -> builder.client().checkHealthy());
        } finally { server.stop(0); }
    }
    private static final class Probe implements HttpResponse.BodySubscriber<Integer> {
        int bytes, errors, completions;
        final CompletableFuture<Integer> result = new CompletableFuture<>();
        @Override public CompletionStage<Integer> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
        @Override public void onNext(List<ByteBuffer> buffers) { buffers.forEach(b -> bytes += b.remaining()); }
        @Override public void onError(Throwable error) { errors++;result.completeExceptionally(error); }
        @Override public void onComplete() { completions++;result.complete(bytes); }
    }
    @Test void exactByteBoundaryIsAllowedButNextByteNeverReachesSdkConsumer() {
        var probe = new Probe();var cancelled = new AtomicBoolean();var failures = new AtomicInteger();
        var subscriber = new BoundedHttpClient.LimitedSubscriber<>(probe, 8, error -> failures.incrementAndGet());
        subscriber.onSubscribe(subscription(cancelled));
        ByteBuffer positioned = ByteBuffer.wrap(new byte[10]);positioned.position(6);
        subscriber.onNext(List.of(positioned, ByteBuffer.wrap(new byte[4])));
        assertEquals(8, probe.bytes);assertFalse(cancelled.get());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[100])));
        subscriber.onComplete();subscriber.onError(new RuntimeException());
        assertTrue(cancelled.get());assertEquals(8, probe.bytes);assertEquals(1, failures.get());
        assertEquals(1, probe.errors);assertEquals(0, probe.completions);
    }
    @Test void anOversizedBatchIsRejectedBeforeAnyBytesAreForwarded() {
        var probe = new Probe();var cancelled = new AtomicBoolean();
        var subscriber = new BoundedHttpClient.LimitedSubscriber<>(probe, 8, error -> {});
        subscriber.onSubscribe(subscription(cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5]), ByteBuffer.wrap(new byte[4])));
        assertTrue(cancelled.get());assertEquals(0, probe.bytes);assertEquals(1, probe.errors);
    }
    @Test void utf8IsBoundedByBytesNotCharactersAndNoParsingIsNeeded() {
        var probe = new Probe();var cancelled = new AtomicBoolean();
        var subscriber = new BoundedHttpClient.LimitedSubscriber<>(probe, 4, error -> {});
        subscriber.onSubscribe(subscription(cancelled));
        subscriber.onNext(List.of(ByteBuffer.wrap("\u4e2d\u6587".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertTrue(cancelled.get());assertEquals(0, probe.bytes);
    }
    @Test void exactLimitCompletesNormallyAndCancelBeforeSubscribeStopsUpstream() {
        var probe = new Probe();var cancelled = new AtomicBoolean();
        var subscriber = new BoundedHttpClient.LimitedSubscriber<>(probe, 8, error -> fail(error));
        subscriber.onSubscribe(subscription(cancelled));subscriber.onNext(List.of(ByteBuffer.wrap(new byte[8])));subscriber.onComplete();
        assertEquals(8, probe.result.join());assertEquals(1, probe.completions);assertFalse(cancelled.get());
        var closed = new BoundedHttpClient.LimitedSubscriber<>(new Probe(), 8, error -> fail(error));
        closed.cancel();closed.onSubscribe(subscription(cancelled));assertTrue(cancelled.get());
    }
    private static Flow.Subscription subscription(AtomicBoolean cancelled) {
        return new Flow.Subscription() {
            @Override public void request(long n) { assertTrue(n > 0); }
            @Override public void cancel() { cancelled.set(true); }
        };
    }
}
