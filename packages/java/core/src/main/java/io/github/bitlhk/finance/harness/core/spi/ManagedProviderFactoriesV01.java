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
        if (!configurationDigests(selectedProviders).equals(expectedConfiguration))
            throw new IllegalArgumentException("PROVIDER_CONFIGURATION_LOCK_MISMATCH");
        var pending = closure(selectedProviders);
        var bound = new LinkedHashMap<String, BoundFinanceProvider>();
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (var entry : new ArrayList<>(pending.entrySet())) {
                var factory = entry.getValue();
                if (!factory.connectionKind().equals(connection.kind()))
                    throw new IllegalArgumentException("PROVIDER_FACTORY_CONNECTION_UNSUPPORTED");
                if (!bound.keySet().containsAll(factory.dependencies())) continue;
                var dependencies = new LinkedHashMap<String, BoundFinanceProvider>();
                factory.dependencies().forEach(id -> dependencies.put(id, bound.get(id)));
                var source = factory.create(connection, Map.copyOf(dependencies));
                if (!factory.providerId().equals(source.descriptor().providerId()))
                    throw new IllegalArgumentException("PROVIDER_FACTORY_IDENTITY_MISMATCH");
                var supported = new HashSet<String>();
                source.descriptor().capabilities().forEach(cap -> supported.add(cap.capabilityId()));
                if (!supported.equals(factory.requirements().keySet()))
                    throw new IllegalArgumentException("PROVIDER_FACTORY_REQUIREMENTS_MISMATCH");
                bound.put(entry.getKey(), new BoundFinanceProvider(source, grants, factory.requirements()));
                pending.remove(entry.getKey()); progressed = true;
            }
            if (!progressed) throw new IllegalArgumentException("PROVIDER_FACTORY_DEPENDENCY_UNAVAILABLE");
        }
        var observed = new LinkedHashMap<String, FinanceProvider>();
        bound.forEach((id, provider) -> observed.put(id, new ObservedFinanceProvider(provider, id, connection.observation())));
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
