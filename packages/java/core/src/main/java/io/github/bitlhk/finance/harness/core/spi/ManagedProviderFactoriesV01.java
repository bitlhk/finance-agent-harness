package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider;
import java.util.*;
import java.util.function.Supplier;

/** Small installation helper over a trusted classpath, not a business router or permission authority. */
public final class ManagedProviderFactoriesV01 {
    private final Map<String, ManagedProviderFactoryV01> factories;

    public ManagedProviderFactoriesV01(Collection<ManagedProviderFactoryV01> installed) {
        var values = new LinkedHashMap<String, ManagedProviderFactoryV01>();
        for (var factory : installed) {
            if (factory.providerId() == null || factory.providerId().isBlank()
                    || factory.requirements().isEmpty()
                    || values.putIfAbsent(factory.providerId(), factory) != null)
                throw new IllegalArgumentException("PROVIDER_FACTORY_INVALID_OR_DUPLICATE");
        }
        factories = Collections.unmodifiableMap(values);
    }

    public static ManagedProviderFactoriesV01 installed() {
        return new ManagedProviderFactoriesV01(ServiceLoader.load(ManagedProviderFactoryV01.class)
                .stream().map(ServiceLoader.Provider::get).toList());
    }

    public Set<String> sourceScopes(Map<String, Set<String>> selectedByProvider) {
        var result = new HashSet<String>();
        selectedByProvider.forEach((id, selected) -> {
            var factory = factories.get(id);
            if (factory == null || !factory.requirements().keySet().containsAll(selected))
                throw new IllegalArgumentException("PROVIDER_FACTORY_CAPABILITY_UNAVAILABLE");
            result.addAll(factory.sourceScopes(Set.copyOf(selected)));
        });
        return Set.copyOf(result);
    }

    public Map<String, FinanceProvider> create(ManagedProviderFactoryV01.Connection connection,
                                              Supplier<BoundFinanceProvider.Grant> grants,
                                              Set<String> selectedProviders) {
        return create(connection, grants, selectedProviders, Map.of());
    }

    public Map<String, Map<String, String>> configurationDigests(Set<String> selectedProviders) {
        var result = new LinkedHashMap<String, Map<String, String>>();
        closure(selectedProviders).forEach((id, factory) -> {
            var digests = Map.copyOf(factory.configurationDigests());
            digests.forEach((key, digest) -> {
                if (!key.matches("[a-z][a-z0-9-]{0,63}") || !digest.matches("[a-f0-9]{64}"))
                    throw new IllegalArgumentException("PROVIDER_CONFIGURATION_IDENTITY_INVALID");
            });
            if (!digests.isEmpty()) result.put(id, digests);
        });
        return Map.copyOf(result);
    }

    public Map<String, FinanceProvider> create(ManagedProviderFactoryV01.Connection connection,
                                              Supplier<BoundFinanceProvider.Grant> grants,
                                              Set<String> selectedProviders,
                                              Map<String, Map<String, String>> expectedConfiguration) {
        var bindings = new LinkedHashMap<String, Binding>();
        closure(selectedProviders).keySet().forEach(id -> bindings.put(id, new Binding(connection, grants)));
        return construct(bindings, selectedProviders, expectedConfiguration);
    }

    /** Host-owned binding: source sessions and execution grants are never shared implicitly. */
    public record Binding(ManagedProviderFactoryV01.Connection connection,
                          Supplier<BoundFinanceProvider.Grant> grants) {
        public Binding { Objects.requireNonNull(connection); Objects.requireNonNull(grants); }
        @Override public String toString() { return "Binding[redacted]"; }
    }

    /** Candidate opt-in path. The reviewed Host must lock the complete dependency closure. */
    public Map<String, FinanceProvider> createBound(Map<String, Binding> bindings,
            Set<String> selectedProviders, Map<String, Map<String, String>> expectedConfiguration,
            Map<String, String> expectedConnectionDigests) {
        bindings = Map.copyOf(bindings);
        if (!bindings.keySet().equals(closure(selectedProviders).keySet()))
            throw new IllegalArgumentException("PROVIDER_CONNECTION_BINDING_SET_MISMATCH");
        var actual = new LinkedHashMap<String, String>();
        bindings.forEach((id, binding) -> actual.put(id, connectionDigest(binding.connection())));
        if (!actual.equals(expectedConnectionDigests))
            throw new IllegalArgumentException("PROVIDER_CONNECTION_LOCK_MISMATCH");
        return construct(bindings, selectedProviders, expectedConfiguration);
    }

    /** No credentials or authorization claims in the reproducible connection identity. */
    public static String connectionDigest(ManagedProviderFactoryV01.Connection connection) {
        var endpoint = connection.endpoint();
        if (endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || endpoint.getHost() == null || !Set.of("http", "https").contains(endpoint.getScheme()))
            throw new IllegalArgumentException("PROVIDER_CONNECTION_ENDPOINT_INVALID");
        try {
            var value = new TreeMap<String, String>();
            value.put("schemaVersion", "finance-provider-connection.v1");
            value.put("kind", connection.kind());
            value.put("endpoint", endpoint.toASCIIString());
            value.put("timeout", connection.timeout().toString());
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                    io.github.bitlhk.finance.harness.core.HarnessJson.mapper().writeValueAsBytes(value)));
        } catch (Exception failure) { throw new IllegalArgumentException("PROVIDER_CONNECTION_IDENTITY_INVALID"); }
    }

    private Map<String, FinanceProvider> construct(Map<String, Binding> bindings,
            Set<String> selectedProviders, Map<String, Map<String, String>> expectedConfiguration) {
        if (!configurationDigests(selectedProviders).equals(expectedConfiguration))
            throw new IllegalArgumentException("PROVIDER_CONFIGURATION_LOCK_MISMATCH");
        var pending = closure(selectedProviders);
        // Validate every connection before constructing even the first Provider.
        pending.forEach((id, factory) -> {
            if (!factory.connectionKind().equals(bindings.get(id).connection().kind()))
                throw new IllegalArgumentException("PROVIDER_FACTORY_CONNECTION_UNSUPPORTED");
        });
        var bound = new LinkedHashMap<String, BoundFinanceProvider>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var entry : new ArrayList<>(pending.entrySet())) {
                var factory = entry.getValue();
                var binding = bindings.get(entry.getKey());
                if (!bound.keySet().containsAll(factory.dependencies())) continue;
                var dependencies = new LinkedHashMap<String, BoundFinanceProvider>();
                factory.dependencies().forEach(id -> dependencies.put(id, bound.get(id)));
                var source = factory.create(binding.connection(), Map.copyOf(dependencies));
                if (!factory.providerId().equals(source.descriptor().providerId()))
                    throw new IllegalArgumentException("PROVIDER_FACTORY_IDENTITY_MISMATCH");
                var supported = new HashSet<String>();
                source.descriptor().capabilities().forEach(cap -> supported.add(cap.capabilityId()));
                if (!supported.equals(factory.requirements().keySet()))
                    throw new IllegalArgumentException("PROVIDER_FACTORY_REQUIREMENTS_MISMATCH");
                bound.put(entry.getKey(), new BoundFinanceProvider(source, binding.grants(), factory.requirements()));
                pending.remove(entry.getKey()); progressed = true;
            }
            if (!progressed) throw new IllegalArgumentException("PROVIDER_FACTORY_DEPENDENCY_UNAVAILABLE");
        }
        var observed = new LinkedHashMap<String, FinanceProvider>();
        bound.forEach((id, provider) -> observed.put(id, new ObservedFinanceProvider(provider, id, bindings.get(id).connection().observation())));
        return Map.copyOf(observed);
    }

    private LinkedHashMap<String, ManagedProviderFactoryV01> closure(Set<String> selectedProviders) {
        var pending = new LinkedHashMap<String, ManagedProviderFactoryV01>();
        var remaining = new ArrayDeque<>(selectedProviders);
        while (!remaining.isEmpty()) {
            String id = remaining.removeFirst();
            if (pending.containsKey(id)) continue;
            var factory = factories.get(id);
            if (factory == null) throw new IllegalArgumentException("PROVIDER_FACTORY_DEPENDENCY_UNAVAILABLE");
            pending.put(id, factory); remaining.addAll(factory.dependencies());
        }
        return pending;
    }
}
