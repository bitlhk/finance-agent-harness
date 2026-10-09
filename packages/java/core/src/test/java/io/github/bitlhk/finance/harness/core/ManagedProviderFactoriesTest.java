package io.github.bitlhk.finance.harness.core;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedProviderFactoriesTest {
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger identities = new AtomicInteger();
    private final ManagedProviderFactoryV01.Connection connection = new ManagedProviderFactoryV01.Connection(
            "controlled-http", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), () -> {
                identities.incrementAndGet(); throw new AssertionError("Construction must not acquire an identity");
            }, () -> null, ignored -> {});

    ManagedProviderFactoryV01 factory(String id, String kind, Set<String> dependencies, String sourceScope) {
        return factory(id, kind, dependencies, sourceScope, Map.of());
    }
    ManagedProviderFactoryV01 factory(String id, String kind, Set<String> dependencies, String sourceScope, Map<String, String> configuration) {
        return new ManagedProviderFactoryV01() {
            public Map<String, String> configurationDigests() { return configuration; }
            public String providerId() { return id; }
            public String connectionKind() { return kind; }
            public Set<String> dependencies() { return dependencies; }
            public Map<String, Set<String>> requirements() { return Map.of("facts.query", Set.of("facts:read")); }
            public Set<String> sourceScopes(Set<String> caps) { return Set.of(sourceScope); }
            public DescribedFinanceProvider create(Connection value, Map<String, BoundFinanceProvider> installed) {
                assertEquals(dependencies, installed.keySet());
                return new DescribedFinanceProvider() {
                    public ProviderDescriptorV1 descriptor() {
                        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, id, "0.1.0",
                                List.of(new ProviderDescriptorV1.CapabilitySupport("facts.query", CapabilityRequestV1.Operation.QUERY)));
                    }
                    public CapabilityResultV1 query(CapabilityRequestV1 request) {
                        reads.incrementAndGet();
                        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                                request.context().traceId(), CapabilityResultV1.Status.COMPLETED, "Synthetic facts", Map.of(),
                                List.of(), List.of(), id, Instant.now(), null, null);
                    }
                    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new AssertionError("NO_TASK"); }
                };
            }
        };
    }

    BoundFinanceProvider.Grant grant(Set<String> scopes) {
        return new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("subject"), Set.of("purpose"), scopes, "session", "controlled-authority", Instant.now().plusSeconds(60));
    }
    CapabilityRequestV1 request() {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request", "trace", "actor",
                FinanceContextV1.PrincipalType.CUSTOMER, "host", "purpose", "subject", "session",
                FinanceContextV1.AuthenticationLevel.BASIC, Set.of("facts:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, "facts.query", CapabilityRequestV1.Operation.QUERY,
                context, Map.of(), null, null, Map.of(), null);
    }

    @Test void constructsOnlySelectedDependencyClosureWithoutIdentityOrGrant() {
        var registry = new ManagedProviderFactoriesV01(List.of(
                factory("projection", "controlled-http", Set.of("source"), "source:read"),
                factory("source", "controlled-http", Set.of(), "source:read"),
                factory("unselected", "another-connection", Set.of(), "unselected:read")));
        var providers = registry.create(connection, () -> { throw new AssertionError("No grant during construction"); }, Set.of("projection"));
        assertEquals(Set.of("source", "projection"), providers.keySet());
        assertEquals(0, identities.get()); assertEquals(0, reads.get());
    }
    @Test void configurationIdentityCoversSelectedDependencyClosureOnly() {
        var registry = new ManagedProviderFactoriesV01(List.of(
                factory("projection", "controlled-http", Set.of("source"), "source:read"),
                factory("source", "controlled-http", Set.of(), "source:read", Map.of("source", "a".repeat(64))),
                factory("unused", "controlled-http", Set.of(), "unused:read", Map.of("bad", "not-a-digest"))));
        var expected = Map.of("source", Map.of("source", "a".repeat(64)));
        assertEquals(expected, registry.configurationDigests(Set.of("projection")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("projection")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("projection"),
                Map.of("source", Map.of("source", "b".repeat(64)))));
        assertEquals(Set.of("projection", "source"), registry.create(connection, () -> null, Set.of("projection"), expected).keySet());
        assertThrows(IllegalArgumentException.class, () -> registry.configurationDigests(Set.of("unused")));
        assertEquals(0, identities.get()); assertEquals(0, reads.get());
    }
    @Test void currentGrantIsCheckedOnEveryCallNotDerivedFromRequirements() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("source", "controlled-http", Set.of(), "source:read")));
        var current = new java.util.concurrent.atomic.AtomicReference<>(grant(Set.of("facts:read")));
        var provider = registry.create(connection, current::get, Set.of("source")).get("source");
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request()).status());
        current.set(grant(Set.of()));
        assertThrows(SecurityException.class, () -> provider.query(request()));
        assertEquals(1, reads.get());
    }
    @Test void insufficientGrantNeverCallsBusinessProvider() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("source", "controlled-http", Set.of(), "source:read")));
        var provider = registry.create(connection, () -> grant(Set.of()), Set.of("source")).get("source");
        assertThrows(SecurityException.class, () -> provider.query(request())); assertEquals(0, reads.get());
    }
    @Test void sourceRequirementsUseSelectedBindingNotAllFactoriesSupportingSameCapability() {
        var registry = new ManagedProviderFactoriesV01(List.of(
                factory("one", "controlled-http", Set.of(), "one:read"),
                factory("two", "controlled-http", Set.of(), "two:read")));
        assertEquals(Set.of("two:read"), registry.sourceScopes(Map.of("two", Set.of("facts.query"))));
        assertThrows(IllegalArgumentException.class, () -> registry.sourceScopes(Map.of("two", Set.of("missing.query"))));
    }
    @Test void duplicateFactoriesRejected() {
        var source = factory("source", "controlled-http", Set.of(), "source:read");
        assertThrows(IllegalArgumentException.class, () -> new ManagedProviderFactoriesV01(List.of(source, source)));
    }
    @Test void explicitEmptySelectionNeedsNoProviderConnectionOrGrant() {
        var registry = new ManagedProviderFactoriesV01(List.of());
        assertEquals(Map.of(), registry.configurationDigests(Set.of()));
        assertEquals(Set.of(), registry.sourceScopes(Map.of()));
        assertEquals(Map.of(), registry.create(null, () -> { throw new AssertionError("No grant requested"); }, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("missing")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of(),
                Map.of("unused", Map.of("source", "a".repeat(64)))));
        assertEquals(0, identities.get()); assertEquals(0, reads.get());
    }
    @Test void emptySelectionDoesNotInstantiateAvailableFactory() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("one", "unused", Set.of(), "facts:read")));
        assertEquals(Map.of(), registry.create(null, () -> null, Set.of()));
    }
    @Test void missingDependencyRejected() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("one", "controlled-http", Set.of("missing"), "one:read")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("one")));
    }
    @Test void cyclicDependenciesRejected() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("one", "controlled-http", Set.of("two"), "one:read"),
                factory("two", "controlled-http", Set.of("one"), "two:read")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("one")));
    }
    @Test void unsupportedConnectionRejected() {
        var registry = new ManagedProviderFactoriesV01(List.of(factory("one", "other", Set.of(), "one:read")));
        assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, Set.of("one")));
    }
    @Test void credentialsNotRendered() {
        var session = new ManagedProviderFactoryV01.SourceSession("sensitive", "customer", "actor", "session", Instant.now());
        assertEquals("SourceSession[redacted]", session.toString());
    }
}
