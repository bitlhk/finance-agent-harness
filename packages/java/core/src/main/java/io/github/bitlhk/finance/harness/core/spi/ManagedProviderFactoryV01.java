package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Candidate construction SPI for preinstalled, reviewed artifacts, never asset-supplied class names. */
public interface ManagedProviderFactoryV01 {
    String providerId();
    String connectionKind();
    Map<String, Set<String>> requirements();
    default Set<String> dependencies() { return Set.of(); }
    default Set<String> sourceScopes(Set<String> selectedCapabilities) { return Set.of(); }
    /** Local, validated configuration bytes only; no paths, secrets, grants or remote calls. */
    default Map<String, String> configurationDigests() { return Map.of(); }
    DescribedFinanceProvider create(Connection connection, Map<String, BoundFinanceProvider> dependencies);

    /** Credentials come from the host's current authority, never from model or component metadata. */
    record SourceSession(String credential, String subjectRef, String principalId,
                         String hostSessionRef, Instant expiresAt) {
        public SourceSession {
            for (String value : List.of(credential, subjectRef, principalId, hostSessionRef))
                if (value.isBlank()) throw new IllegalArgumentException("SOURCE_SESSION_INCOMPLETE");
            Objects.requireNonNull(expiresAt);
        }
        @Override public String toString() { return "SourceSession[redacted]"; }
    }

    /** No response bodies, headers or customer selectors in transport observations. */
    record HttpAttempt(String method, String route, int status, Instant observedAt) { }

    record Connection(String kind, URI endpoint, Duration timeout,
                      Supplier<SourceSession> currentSession,
                      Supplier<ObservationRun> observation, Consumer<HttpAttempt> attempts) {
        public Connection {
            Objects.requireNonNull(kind); Objects.requireNonNull(endpoint); Objects.requireNonNull(timeout);
            Objects.requireNonNull(currentSession); Objects.requireNonNull(observation); Objects.requireNonNull(attempts);
            if (kind.isBlank() || timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("PROVIDER_CONNECTION_INVALID");
        }
        @Override public String toString() { return "Connection[kind=" + kind + ", endpoint=redacted]"; }
    }
}
