package io.github.bitlhk.finance.harness.providers.curatedmcp;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;

/** Counts wire body bytes before the SDK's line/JSON subscribers can buffer them. */
final class BoundedHttpClient extends HttpClient {
    static final int MAX_BODY_BYTES = 256 * 1024;
    private final HttpClient delegate;
    private final Duration timeout;
    private final boolean requireStateless;
    private final String sourceIdentity;
    private final McpHttpObservation observation;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "curated-mcp-http-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Exchange<?>> active = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile boolean stopping;

    private BoundedHttpClient(HttpClient delegate, Duration timeout, boolean requireStateless, String sourceIdentity, McpHttpObservation observation) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.requireStateless = requireStateless;
        this.sourceIdentity = sourceIdentity;
        this.observation = observation;
    }

    void checkHealthy() {
        var cause = failure.get();
        if (cause instanceof SecurityException denied) throw denied;
        if (cause != null) throw new IllegalStateException("CURATED_HTTP_INGRESS_FAILED", cause);
    }

    void stopReads() {
        stopping = true;
        active.stream().filter(e -> !e.cleanup).forEach(e -> e.abort(new CancellationException("CURATED_READ_CLOSED"), false));
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        var exchange = new Exchange<T>(request.method().equals("DELETE"));
        active.add(exchange);
        if (stopping && !exchange.cleanup) {
            exchange.abort(new CancellationException("CURATED_READ_CLOSED"), false);
            return exchange.result;
        }
        if (!exchange.beginObservation(request.method())) return exchange.result;
        try {
            exchange.deadline = timer.schedule(() -> exchange.abort(new HttpTimeoutException("CURATED_HTTP_DEADLINE"), true),
                    timeout.toNanos(), TimeUnit.NANOSECONDS);
            var future = delegate.sendAsync(request, info -> {
                if (exchange.observation != null) exchange.observation.headers(info.statusCode());
                if (sourceIdentity != null && !info.headers().allValues("X-Finance-Source-Sha256").equals(List.of(sourceIdentity)))
                    throw new IllegalArgumentException("CURATED_SOURCE_IDENTITY_MISMATCH");
                if (info.statusCode() == 401 || info.statusCode() == 403) {
                    // Preserve the trusted source's denial across the SDK's async exception wrappers.
                    var denied = new SecurityException("CURATED_SOURCE_ACCESS_DENIED");
                    failure.compareAndSet(null, denied);
                    throw denied;
                }
                if (requireStateless && info.headers().firstValue("Mcp-Session-Id").isPresent())
                    throw new IllegalArgumentException("CURATED_STATEFUL_SOURCE_REJECTED");
                if (info.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_BODY_BYTES)
                    throw new IllegalArgumentException("CURATED_HTTP_BODY_TOO_LARGE");
                if (!info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity"))
                    throw new IllegalArgumentException("CURATED_HTTP_ENCODING_UNSUPPORTED");
                // DELETE status is not checked by SDK 0.17.2 createDelete().
                if (exchange.cleanup && (info.statusCode() < 200 || info.statusCode() >= 300))
                    throw new IllegalArgumentException("CURATED_HTTP_DELETE_REJECTED");
                var subscriber = new LimitedSubscriber<>(handler.apply(info), MAX_BODY_BYTES,
                        error -> exchange.abort(error, true));
                exchange.subscriber = subscriber;
                if (exchange.result.isDone()) subscriber.cancel();
                return subscriber;
            });
            exchange.upstream = future;
            if (exchange.result.isDone()) future.cancel(true);
            future.whenComplete((response, error) -> {
                if (error != null) exchange.abort(error, true);
                else exchange.complete(response);
            });
        } catch (RuntimeException error) { exchange.abort(error, true); }
        return exchange.result;
    }

    private final class Exchange<T> {
        final boolean cleanup;
        final CompletableFuture<HttpResponse<T>> result = new CompletableFuture<>();
        volatile CompletableFuture<?> upstream;
        volatile LimitedSubscriber<T> subscriber;
        volatile ScheduledFuture<?> deadline;
        volatile McpHttpObservation.Attempt observation;
        private boolean ending;
        Exchange(boolean cleanup) {
            this.cleanup = cleanup;
            result.whenComplete((value, error) -> {
                active.remove(this);
                if (deadline != null) deadline.cancel(false);
                if (result.isCancelled()) {
                    if (observation != null) observation.finish(new CancellationException("CURATED_HTTP_CANCELLED"));
                    if (subscriber != null) subscriber.cancel();
                    if (upstream != null) upstream.cancel(true);
                }
            });
        }
        synchronized boolean beginObservation(String method) {
            if (ending || result.isDone()) return false;
            if (BoundedHttpClient.this.observation != null)
                observation = BoundedHttpClient.this.observation.start(method);
            return true;
        }
        private synchronized boolean claimEnd() {
            if (ending || result.isDone()) return false;
            ending = true;
            return true;
        }
        void complete(HttpResponse<T> response) {
            if (!claimEnd()) return;
            if (observation != null) observation.finish(null);
            result.complete(response);
        }
        void abort(Throwable error, boolean record) {
            if (!claimEnd()) return;
            if (record) failure.compareAndSet(null, error);
            if (observation != null) observation.finish(error);
            // Cancellation may re-enter a body subscriber; never hold the Exchange monitor here.
            result.completeExceptionally(error);
            if (subscriber != null) subscriber.cancel();
            if (upstream != null) upstream.cancel(true);
        }
    }

    static final class LimitedSubscriber<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> downstream;
        private final long limit;
        private final java.util.function.Consumer<Throwable> failure;
        private Flow.Subscription upstream;
        private long received;
        private boolean done;
        LimitedSubscriber(HttpResponse.BodySubscriber<T> downstream, long limit, java.util.function.Consumer<Throwable> failure) {
            this.downstream = downstream;
            this.limit = limit;
            this.failure = failure;
        }
        @Override public CompletionStage<T> getBody() { return downstream.getBody(); }
        @Override public synchronized void onSubscribe(Flow.Subscription subscription) {
            if (done || upstream != null) { subscription.cancel(); return; }
            upstream = subscription;
            downstream.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { subscription.request(n); }
                @Override public void cancel() { LimitedSubscriber.this.cancel(); }
            });
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            long count = 0;
            for (var buffer : buffers) {
                count += buffer.remaining();
                if (count > limit - received) {
                    var error = new IOException("CURATED_HTTP_BODY_TOO_LARGE");
                    done = true;
                    upstream.cancel();
                    failure.accept(error);
                    downstream.onError(error);
                    return;
                }
            }
            received += count;
            downstream.onNext(buffers);
        }
        @Override public synchronized void onError(Throwable error) {
            if (!done) { done = true; downstream.onError(error); }
        }
        @Override public synchronized void onComplete() {
            if (!done) { done = true; downstream.onComplete(); }
        }
        synchronized void cancel() {
            done = true;
            if (upstream != null) upstream.cancel();
        }
    }

    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        // Server push is deliberately unsupported; all accepted responses use the same bound.
        return sendAsync(request, handler);
    }
    @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        var future = sendAsync(request, handler);
        try { return future.get(); }
        catch (InterruptedException error) { future.cancel(true); throw error; }
        catch (ExecutionException error) { throw new IOException("CURATED_HTTP_FAILED", error.getCause()); }
    }
    @Override public void close() {
        stopReads();
        active.forEach(e -> e.abort(new CancellationException("CURATED_HTTP_CLOSED"), false));
        timer.shutdownNow();
        delegate.shutdownNow();
    }
    @Override public void shutdown() { close(); }
    @Override public void shutdownNow() { close(); }
    @Override public boolean isTerminated() { return delegate.isTerminated() && timer.isTerminated(); }
    @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return delegate.awaitTermination(duration); }
    @Override public Optional<CookieHandler> cookieHandler() { return delegate.cookieHandler(); }
    @Override public Optional<Duration> connectTimeout() { return delegate.connectTimeout(); }
    @Override public Redirect followRedirects() { return delegate.followRedirects(); }
    @Override public Optional<ProxySelector> proxy() { return delegate.proxy(); }
    @Override public SSLContext sslContext() { return delegate.sslContext(); }
    @Override public SSLParameters sslParameters() { return delegate.sslParameters(); }
    @Override public Optional<Authenticator> authenticator() { return delegate.authenticator(); }
    @Override public Version version() { return delegate.version(); }
    @Override public Optional<Executor> executor() { return delegate.executor(); }

    /** Official SDK clientBuilder extension, delegating configuration to the JDK. */
    static final class Builder implements HttpClient.Builder, AutoCloseable {
        private final HttpClient.Builder delegate = HttpClient.newBuilder().followRedirects(Redirect.NEVER).version(Version.HTTP_1_1);
        private final Duration timeout;
        private final boolean requireStateless;
        private final String sourceIdentity;
        private McpHttpObservation observation;
        private BoundedHttpClient client;
        Builder(Duration timeout) { this(timeout, false); }
        Builder(Duration timeout, boolean requireStateless) {
            this(timeout, requireStateless, null);
        }
        Builder(Duration timeout, boolean requireStateless, String sourceIdentity) {
            this.timeout = timeout; this.requireStateless = requireStateless;
            if (sourceIdentity != null && (!requireStateless || !sourceIdentity.matches("[a-f0-9]{64}")))
                throw new IllegalArgumentException("CURATED_SOURCE_IDENTITY_INVALID");
            this.sourceIdentity = sourceIdentity;
        }
        BoundedHttpClient client() { return Objects.requireNonNull(client); }
        Builder observations(McpHttpObservation value) { observation = value; return this; }
        @Override public HttpClient build() {
            if (client != null) throw new IllegalStateException("CURATED_HTTP_CLIENT_ALREADY_BUILT");
            client = new BoundedHttpClient(delegate.build(), timeout, requireStateless, sourceIdentity, observation);
            return client;
        }
        @Override public void close() { if (client != null) client.close(); }
        @Override public Builder cookieHandler(CookieHandler value) { delegate.cookieHandler(value); return this; }
        @Override public Builder connectTimeout(Duration value) { delegate.connectTimeout(value); return this; }
        @Override public Builder sslContext(SSLContext value) { delegate.sslContext(value); return this; }
        @Override public Builder sslParameters(SSLParameters value) { delegate.sslParameters(value); return this; }
        @Override public Builder executor(Executor value) { delegate.executor(value); return this; }
        @Override public Builder followRedirects(Redirect value) {
            if (value != Redirect.NEVER) throw new IllegalArgumentException("CURATED_REDIRECT_DENIED");
            delegate.followRedirects(value); return this;
        }
        @Override public Builder version(Version value) { delegate.version(value); return this; }
        @Override public Builder priority(int value) { delegate.priority(value); return this; }
        @Override public Builder proxy(ProxySelector value) { delegate.proxy(value); return this; }
        @Override public Builder authenticator(Authenticator value) { delegate.authenticator(value); return this; }
        @Override public Builder localAddress(InetAddress value) { delegate.localAddress(value); return this; }
    }
}
