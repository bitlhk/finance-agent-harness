package io.github.bitlhk.finance.harness.integrations.agentscope;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Closed Host-owned configuration: no retry, fallback, stream, headers or arbitrary SDK options. */
public final class ModelConnection {
    private final URI baseUri;
    private final String model, secret;
    private final Duration timeout;
    public record Generation(double temperature, double topP, int maxTokens) {
        public Generation {
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2
                    || !Double.isFinite(topP) || topP <= 0 || topP > 1 || maxTokens < 1 || maxTokens > 32768)
                throw new IllegalArgumentException("INVALID_MODEL_GENERATION_PARAMETERS");
        }
    }
    private final Generation generation;
    public ModelConnection(URI baseUri, String model, String apiKey, Duration timeout) {
        this(baseUri, model, apiKey, timeout, null);
    }
    public ModelConnection(URI baseUri, String model, String apiKey, Duration timeout, Generation generation) {
        Objects.requireNonNull(baseUri); Objects.requireNonNull(timeout);
        boolean loopback = "127.0.0.1".equals(baseUri.getHost()) || "localhost".equals(baseUri.getHost());
        if ((!"https".equals(baseUri.getScheme()) && !(loopback && "http".equals(baseUri.getScheme())))
                || baseUri.getUserInfo() != null || baseUri.getQuery() != null || baseUri.getFragment() != null
                || model == null || model.isBlank() || apiKey == null || apiKey.isBlank()
                || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(120)) > 0)
            throw new IllegalArgumentException("INVALID_MANAGED_MODEL_CONNECTION");
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", "")); this.model = model; secret = apiKey; this.timeout = timeout;
        this.generation = generation;
    }
    public URI baseUri() { return baseUri; }
    public String model() { return model; }
    String apiKey() { return secret; }
    public Duration timeout() { return timeout; }
    public Generation generation() { return generation; }
    @Override public String toString() { return "ModelConnection[model=" + model + ", credentials=redacted]"; }
}
