package io.github.bitlhk.finance.harness.providers.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
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
        return create(root, () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); },
            () -> { throw new SecurityException("PLAN_ONLY_NO_GRANT"); }, () -> null,
            ignored -> { throw new IllegalStateException("PLAN_ONLY_NO_HTTP"); });
    }

    public static Map<String, FinanceProvider> create(Path root,
            Supplier<ManagedProviderFactoryV01.SourceSession> sessions,
            Supplier<BoundFinanceProvider.Grant> grants, Supplier<ObservationRun> observation,
            Consumer<ManagedProviderFactoryV01.HttpAttempt> attempts) {
        try {
            root = root.toAbsolutePath().normalize();
            JsonNode lock = read(root.resolve("service-lock.json"), 2_000_000);
            JsonNode plan = locked(root, lock, "component-plan.json", 2_000_000);
            var capabilities = new HashSet<String>();
            lock.path("capabilities").forEach(c -> capabilities.add(c.asText()));
            if (!capabilities.equals(Set.of(KnowledgeProvider.SEARCH))
                    || plan.path("capabilityBindings").size() != 1
                    || !KnowledgeProvider.ID.equals(plan.path("capabilityBindings").path(KnowledgeProvider.SEARCH).asText()))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            JsonNode bindings = locked(root, lock, "provider-bindings.json", 65536);
            fields(bindings, Set.of("schemaVersion", "providers"));
            if (!"finance-host-provider-bindings.v0.1".equals(bindings.path("schemaVersion").asText()))
                throw new IllegalArgumentException("KNOWLEDGE_INSTALLATION_MISMATCH");
            fields(bindings.path("providers"), Set.of(KnowledgeProvider.ID));
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

