package io.github.bitlhk.finance.harness.integrations.agentscope;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Closed Host-owned configuration: no retry, fallback, stream, headers or arbitrary SDK options. */
public final class ModelConnection {
    private final URI baseUri;
    private final String model, secret;
    private final Duration timeout;
    public ModelConnection(URI baseUri, String model, String apiKey, Duration timeout) {
        Objects.requireNonNull(baseUri); Objects.requireNonNull(timeout);
        boolean loopback = "127.0.0.1".equals(baseUri.getHost()) || "localhost".equals(baseUri.getHost());
        if ((!"https".equals(baseUri.getScheme()) && !(loopback && "http".equals(baseUri.getScheme())))
                || baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
                || model == null || model.isBlank() || apiKey == null || apiKey.isBlank()
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("INVALID_MANAGED_MODEL_CONNECTION");
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", "")); this.model = model; secret = apiKey; this.timeout = timeout;
    }
    public URI baseUri() { return baseUri; }
    public String model() { return model; }
    String apiKey() { return secret; }
    public Duration timeout() { return timeout; }
    @Override public String toString() { return "ModelConnection[model=" + model + ", credentials=redacted]"; }
}
