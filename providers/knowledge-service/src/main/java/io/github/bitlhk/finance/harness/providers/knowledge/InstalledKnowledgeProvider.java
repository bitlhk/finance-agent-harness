package io.github.bitlhk.finance.harness.providers.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.*;

/** Component-owned installed binding. No authority is read from the build artifact. */
public final class InstalledKnowledgeProvider {
    private InstalledKnowledgeProvider() { }

    public static Map<String, FinanceProvider> prepare(Path root) {
        return prepare(root, Map.of());
    }

    /** Peers must be actual Host-prepared adapters, never IDs supplied by a request. */
    public static Map<String, FinanceProvider> prepare(Path root, Map<String, FinanceProvider> peers) {
        return create(root, () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); },
            () -> { throw new SecurityException("PLAN_ONLY_NO_GRANT"); }, () -> null,
            ignored -> { throw new IllegalStateException("PLAN_ONLY_NO_HTTP"); }, peers);
    }

    public static Map<String, FinanceProvider> create(Path root,
            Supplier<ManagedProviderFactoryV01.SourceSession> sessions,
            Supplier<BoundFinanceProvider.Grant> grants, Supplier<ObservationRun> observation,
            Consumer<ManagedProviderFactoryV01.HttpAttempt> attempts) {
        return create(root, sessions, grants, observation, attempts, Map.of());
    }

    /** Returns only the knowledge adapter; global installation still checks the combined registry. */
    public static Map<String, FinanceProvider> create(Path root,
            Supplier<ManagedProviderFactoryV01.SourceSession> sessions,
            Supplier<BoundFinanceProvider.Grant> grants, Supplier<ObservationRun> observation,
            Consumer<ManagedProviderFactoryV01.HttpAttempt> attempts, Map<String, FinanceProvider> peers) {
        try {
            root = root.toAbsolutePath().normalize();
            JsonNode lock = read(root.resolve("service-lock.json"), 2_000_000);
            JsonNode plan = locked(root, lock, "component-plan.json", 2_000_000);
            JsonNode bindings = locked(root, lock, "provider-bindings.json", 65536);
            fields(bindings, Set.of("schemaVersion", "providers"));
            if (!"finance-host-provider-bindings.v0.1".equals(bindings.path("schemaVersion").asText()))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            composition(lock, plan, bindings, Map.copyOf(peers));
            JsonNode selected = bindings.path("providers").path(KnowledgeProvider.ID);
            fields(selected, Set.of("connectionRef", "kind", "endpoint", "timeoutMillis", "configuration"));
            JsonNode reference = plan.path("providerConnections").path(KnowledgeProvider.ID);
            fields(reference, Set.of("connectionRef", "kind"));
            if (!selected.path("kind").equals(reference.path("kind"))
                    || !selected.path("connectionRef").equals(reference.path("connectionRef"))
                    || !KnowledgeFactoryV01.KIND.equals(reference.path("kind").asText())
                    || !selected.path("timeoutMillis").isIntegralNumber()
                    || !selected.path("timeoutMillis").canConvertToInt()
                    || selected.path("timeoutMillis").intValue() < 1 || selected.path("timeoutMillis").intValue() > 60000)
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            fields(selected.path("configuration"), Set.of("knowledge-binding"));
            JsonNode config = selected.path("configuration").path("knowledge-binding");
            fields(config, Set.of("path", "sha256"));
            String relative = "provider-configuration/knowledge-service/knowledge-binding.json";
            if (!relative.equals(config.path("path").asText())
                    || !config.path("sha256").equals(lock.path("files").path(relative)))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            locked(root, lock, relative, 8192);
            var binding = KnowledgeBinding.load(root.resolve(relative).toString(), config.path("sha256").asText());
            var registry = new ManagedProviderFactoriesV01(List.of(new KnowledgeFactoryV01(binding)));
            var connection = new ManagedProviderFactoryV01.Connection(KnowledgeFactoryV01.KIND,
                URI.create(selected.path("endpoint").asText()), Duration.ofMillis(selected.path("timeoutMillis").intValue()),
                sessions, observation, attempts);
            String ref = reference.path("connectionRef").asText();
            return registry.createReferenced(Map.of(KnowledgeProvider.ID,
                new ManagedProviderFactoriesV01.ConnectionReference(ref, connection.kind())),
                Map.of(ref, connection), Map.of(KnowledgeProvider.ID, grants), Set.of(KnowledgeProvider.ID),
                Map.of(KnowledgeProvider.ID, Map.of("binding-sha256", config.path("sha256").asText())),
                Map.of(KnowledgeProvider.ID, ManagedProviderFactoriesV01.connectionDigest(connection)));
        } catch (RuntimeException invalid) { throw invalid; }
        catch (Exception invalid) { throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_UNAVAILABLE"); }
    }

    private static void composition(JsonNode lock, JsonNode plan, JsonNode bindings,
                                    Map<String, FinanceProvider> peers) {
        if (peers.containsKey(KnowledgeProvider.ID) || !lock.path("capabilities").isArray())
            throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
        var capabilities = new HashSet<String>();
        for (var value : lock.path("capabilities")) {
            if (!value.isTextual() || value.asText().isBlank() || !capabilities.add(value.asText()))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
        }
        fields(plan.path("capabilityBindings"), capabilities);
        if (!KnowledgeProvider.ID.equals(plan.path("capabilityBindings").path(KnowledgeProvider.SEARCH).asText()))
            throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
        var providers = new HashSet<>(peers.keySet());
        providers.add(KnowledgeProvider.ID);
        fields(plan.path("providerConnections"), providers);
        fields(bindings.path("providers"), providers);
        var used = new HashSet<String>();
        for (String capability : capabilities) {
            if (capability.startsWith("knowledge.") && !KnowledgeProvider.SEARCH.equals(capability))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            JsonNode target = plan.path("capabilityBindings").path(capability);
            if (!target.isTextual()) throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            String provider = target.asText();
            used.add(provider);
            if (KnowledgeProvider.ID.equals(provider)) {
                if (!KnowledgeProvider.SEARCH.equals(capability))
                    throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            } else if (!(peers.get(provider) instanceof DescribedFinanceProvider described)
                    || !provider.equals(described.descriptor().providerId())
                    || !described.descriptor().supports(capability, CapabilityRequestV1.Operation.QUERY)) {
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            }
        }
        if (!used.equals(providers)) throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
        var connections = new HashMap<String, JsonNode>();
        for (String provider : providers) {
            var reference = plan.path("providerConnections").path(provider);
            var selected = bindings.path("providers").path(provider);
            fields(reference, Set.of("connectionRef", "kind"));
            fields(selected, Set.of("connectionRef", "kind", "endpoint", "timeoutMillis", "configuration"));
            if (!reference.path("connectionRef").isTextual()
                    || !reference.path("connectionRef").asText().matches("[a-z][a-z0-9-]{0,63}")
                    || !reference.path("kind").isTextual() || reference.path("kind").asText().isBlank()
                    || !selected.path("connectionRef").equals(reference.path("connectionRef"))
                    || !selected.path("kind").equals(reference.path("kind")))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            var previous = connections.putIfAbsent(reference.path("connectionRef").asText(), selected);
            if (previous != null && (!previous.path("kind").equals(selected.path("kind"))
                    || !previous.path("endpoint").equals(selected.path("endpoint"))
                    || !previous.path("timeoutMillis").equals(selected.path("timeoutMillis"))))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
        }
    }

    private static JsonNode locked(Path root, JsonNode lock, String name, int limit) throws Exception {
        Path file = root.resolve(name);
        if (!file.toRealPath().startsWith(root.toRealPath()) || Files.isSymbolicLink(file))
            throw new IllegalArgumentException("KNOWLEDGE_CONFIGURATION_NOT_LOCKED");
        byte[] raw = bytes(file, limit);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if (!sha.equals(lock.path("files").path(name).asText()))
            throw new IllegalArgumentException("KNOWLEDGE_CONFIGURATION_NOT_LOCKED");
        return KnowledgeBinding.JSON.readTree(raw);
    }
    private static byte[] bytes(Path file, int limit) throws Exception {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > limit)
            throw new IllegalArgumentException("KNOWLEDGE_CONFIGURATION_INVALID");
        try (var stream = Files.newInputStream(file)) {
            byte[] raw = stream.readNBytes(limit+1);
            if (raw.length > limit) throw new IllegalArgumentException("KNOWLEDGE_CONFIGURATION_INVALID");
            return raw;
        }
    }
    private static JsonNode read(Path file, int limit) throws Exception {
        return KnowledgeBinding.JSON.readTree(bytes(file, limit));
    }
    private static void fields(JsonNode node, Set<String> expected) {
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        if (!node.isObject() || !actual.equals(expected))
            throw new IllegalArgumentException("KNOWLEDGE_CONFIGURATION_INVALID");
    }
}
