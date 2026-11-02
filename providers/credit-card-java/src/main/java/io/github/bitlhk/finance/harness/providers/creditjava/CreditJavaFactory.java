package io.github.bitlhk.finance.harness.providers.creditjava;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Installation-time authority and source binding; asset metadata cannot instantiate authority. */
public final class CreditJavaFactory implements ManagedProviderFactoryV01 {
    public static final String ID = "credit-card-java", SCOPE = "credit-card:read";
    public static final String LIMIT = "credit.card.limit-risk.query", BENEFITS = "credit.card.benefits.query";
    private final URI endpoint;
    private final Supplier<BoundFinanceProvider.Grant> grants;
    private final Function<CapabilityRequestV1, Binding> bindings;
    private final Predicate<Map<String,String>> audit;

    /** Immutable host-resolved snapshot and absolute deadline, never model-controlled URLs. */
    public record Binding(Map<String,Object> source, Instant deadline) {
        public Binding {
            source = Map.copyOf(source);
            Objects.requireNonNull(deadline);
        }
    }
    public CreditJavaFactory() { endpoint = null; grants = null; bindings = null; audit = null; }
    public CreditJavaFactory(URI endpoint, Supplier<BoundFinanceProvider.Grant> grants,
            Function<CapabilityRequestV1, Binding> bindings, Predicate<Map<String,String>> audit) {
        requireEndpoint(endpoint);
        this.endpoint = endpoint; this.grants = Objects.requireNonNull(grants);
        this.bindings = Objects.requireNonNull(bindings); this.audit = Objects.requireNonNull(audit);
    }
    static void requireEndpoint(URI endpoint) {
        if (endpoint == null || !"http".equals(endpoint.getScheme())
                || !"127.0.0.1".equals(endpoint.getHost()) || endpoint.getPort() < 1
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !(endpoint.getPath().isEmpty() || endpoint.getPath().equals("/")))
            throw new IllegalArgumentException("CREDIT_LOOPBACK_ENDPOINT_REQUIRED");
    }
    public String providerId() { return ID; }
    public String connectionKind() { return "credit-card-java.v1"; }
    public Map<String,Set<String>> requirements() {
        return Map.of(LIMIT, Set.of(SCOPE), BENEFITS, Set.of(SCOPE));
    }
    public DescribedFinanceProvider create(Connection connection, Map<String,BoundFinanceProvider> dependencies) {
        if (endpoint == null) throw new SecurityException("CREDIT_HOST_BINDING_REQUIRED");
        if (!dependencies.isEmpty() || !connection.kind().equals(connectionKind())
                || !connection.endpoint().equals(endpoint))
            throw new IllegalArgumentException("CREDIT_CONNECTION_MISMATCH");
        return new CreditJavaProvider(connection, grants, bindings, audit);
    }
}
