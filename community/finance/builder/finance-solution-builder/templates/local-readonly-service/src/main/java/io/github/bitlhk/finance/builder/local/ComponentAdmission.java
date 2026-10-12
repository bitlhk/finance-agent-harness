package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.assembly.*;
import java.nio.file.*;
import java.util.*;

/** Checks the generated plan against actual Assembler inputs; never grants permission. */
public final class ComponentAdmission {
    private ComponentAdmission() {}

    static Set<String> queryCapabilities(Path root) throws Exception {
        var definitions = definitions(root);
        if (definitions.values().stream().anyMatch(c -> c.operation() != FinanceCapabilityPackV1.Operation.QUERY))
            throw new SecurityException("READONLY_COMPOSITION_REQUIRED");
        return definitions.keySet();
    }

    static Map<String, FinanceCapabilityPackV1.Capability> definitions(Path root) throws Exception {
        var capabilities = new HashMap<String, FinanceCapabilityPackV1.Capability>();
        var support = HarnessJson.mapper().readTree(root.resolve("host-support.v0.1.json").toFile());
        Set<String> operations = new HashSet<>(); support.path("operations").forEach(v -> operations.add(v.asText()));
        for (var node : lockedPlan(root).path("capabilities")) {
            var cap = HarnessJson.mapper().treeToValue(node, FinanceCapabilityPackV1.Capability.class);
            if (!operations.contains(cap.operation().name()) || capabilities.putIfAbsent(cap.capabilityId(), cap) != null)
                throw new SecurityException("HOST_OPERATION_UNSUPPORTED");
        }
        if (capabilities.isEmpty()) throw new SecurityException("HOST_COMPOSITION_REQUIRED");
        return Map.copyOf(capabilities);
    }

    static Map<String, Set<String>> selectedProviders(Path root) {
        try {
            var selected = new HashMap<String, Set<String>>();
            lockedPlan(root).path("capabilityBindings").fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank())
                    throw new SecurityException("COMPONENT_PROVIDER_PLAN_INVALID");
                selected.computeIfAbsent(entry.getValue().asText(), ignored -> new HashSet<>()).add(entry.getKey());
            });
            var result = new HashMap<String, Set<String>>();
            selected.forEach((id, caps) -> result.put(id, Set.copyOf(caps)));
            return Map.copyOf(result);
        } catch (Exception invalid) { throw new SecurityException("COMPONENT_PLAN_INVALID", invalid); }
    }

    static Set<String> requiredScopes(Path root) {
        try {
            var plan = lockedPlan(root);
            var scopes = new HashSet<String>();
            for (var cap : plan.path("capabilities"))
                scopes.addAll(HarnessJson.mapper().treeToValue(cap, FinanceCapabilityPackV1.Capability.class).requiredScopes());
            return Set.copyOf(scopes);
        } catch (Exception invalid) { throw new SecurityException("COMPONENT_PLAN_INVALID"); }
    }

    private static JsonNode lockedPlan(Path root) throws Exception {
        var mapper = HarnessJson.mapper();
        var lock = mapper.readTree(root.resolve("service-lock.json").toFile());
        for (String name : List.of("component-plan.json", "host-support.v0.1.json")) {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 2_000_000
                    || !file.toRealPath().startsWith(root.toRealPath())
                    || !ExecutionArchive.hash(Files.readAllBytes(file)).equals(lock.path("files").path(name).asText()))
                throw new SecurityException("COMPONENT_PLAN_NOT_LOCKED");
        }
        var plan = mapper.readTree(root.resolve("component-plan.json").toFile());
        var support = mapper.readTree(root.resolve("host-support.v0.1.json").toFile());
        String hostDigest = lock.has("javaBridge") ? lock.path("javaBridge").path("hostTemplateDigest").asText()
                : lock.path("hostTemplateDigest").asText();
        if (lock.has("runtimeBinding") || lock.has("managedTemplateDigest") || plan.has("runtimeBinding")) {
            // Managed composition and collector inventory are different identities.
            hostDigest = lock.path("managedTemplateDigest").asText();
            if (!hostDigest.matches("sha256:[0-9a-f]{64}")
                    || !hostDigest.equals(lock.path("runtimeBinding").path("managedTemplate").path("digest").asText())
                    || !hostDigest.equals(plan.path("runtimeBinding").path("managedTemplate").path("digest").asText()))
                throw new SecurityException("COMPONENT_HOST_SUPPORT_MISMATCH");
        }
        if (!"finance-local-component-plan.v0.1".equals(plan.path("schemaVersion").asText())
                || !plan.path("runtime").equals(support.path("runtime"))
                || !plan.path("managedTemplate").path("digest").asText().equals(hostDigest)
                || !("sha256:" + ExecutionArchive.hash(Files.readAllBytes(root.resolve("host-support.v0.1.json"))))
                    .equals(plan.path("managedTemplate").path("supportDigest").asText()))
            throw new SecurityException("COMPONENT_HOST_SUPPORT_MISMATCH");
        return plan;
    }

    static void validate(Path root, Map<String, Object> snapshot) throws Exception {
        JsonNode observed = HarnessJson.mapper().valueToTree(snapshot);
        var definitions = new ArrayList<JsonNode>();
        for (var item : observed.path("capabilities")) definitions.add(item.path("definition"));
        validate(root, snapshot, definitions);
    }
    static void validate(Path root, Map<String, Object> snapshot, Collection<?> definitions) throws Exception {
        var plan = lockedPlan(root);
        var expected = new HashMap<String, FinanceCapabilityPackV1.Capability>();
        for (var node : plan.path("capabilities")) {
            var cap = HarnessJson.mapper().treeToValue(node, FinanceCapabilityPackV1.Capability.class);
            if (expected.putIfAbsent(cap.capabilityId(), cap) != null)
                throw new SecurityException("COMPONENT_CAPABILITY_DUPLICATE");
        }
        var actual = new HashMap<String, FinanceCapabilityPackV1.Capability>();
        JsonNode observed = HarnessJson.mapper().valueToTree(snapshot);
        for (var item : definitions) {
            var cap = HarnessJson.mapper().convertValue(item, FinanceCapabilityPackV1.Capability.class);
            if (actual.putIfAbsent(cap.capabilityId(), cap) != null)
                throw new SecurityException("COMPONENT_CAPABILITY_DUPLICATE");
        }
        if (expected.isEmpty() || !actual.equals(expected))
            throw new SecurityException("COMPONENT_CAPABILITY_PLAN_MISMATCH");
        for (String coordinate : List.of("templateRef", "integrationRef")) {
            var planned = plan.path("runtime");
            var resolved = plan.path("resolvedRuntime");
            var expectedCoordinate = resolved.has(coordinate) ? resolved.path(coordinate) : planned.path(coordinate);
            if (!expectedCoordinate.isTextual()
                    || !expectedCoordinate.equals(observed.path("bridgeRuntime").path(coordinate)))
                throw new SecurityException("COMPONENT_RUNTIME_PLAN_MISMATCH");
        }
        var actualBindings = new HashMap<String, String>();
        var providerVersions = new HashMap<String, String>();
        for (var provider : plan.path("providerRefs"))
            providerVersions.put(provider.path("id").asText(), provider.path("version").asText());
        var packVersions = new HashMap<String, String>();
        for (var pack : plan.path("capabilityPackRefs"))
            packVersions.put(pack.path("id").asText(), pack.path("version").asText());
        for (var cap : observed.path("capabilities")) {
            actualBindings.put(cap.path("definition").path("capabilityId").asText(), cap.path("providerId").asText());
            if (!Objects.equals(providerVersions.get(cap.path("providerId").asText()), cap.path("providerVersion").asText())
                    || !Objects.equals(packVersions.get(cap.path("packId").asText()), cap.path("packVersion").asText()))
                throw new SecurityException("COMPONENT_IMPLEMENTATION_VERSION_MISMATCH");
        }
        if (!HarnessJson.mapper().valueToTree(actualBindings).equals(plan.path("capabilityBindings")))
            throw new SecurityException("COMPONENT_PROVIDER_PLAN_MISMATCH");
        Set<String> declaredMethods = new HashSet<>(), actualMethods = new HashSet<>();
        for (var method : plan.path("domainPackRefs"))
            declaredMethods.add(method.path("id").asText() + "@" + method.path("version").asText());
        for (var method : observed.path("methods"))
            actualMethods.add(method.path("packId").asText() + "@" + method.path("version").asText());
        if (!declaredMethods.equals(actualMethods)) throw new SecurityException("COMPONENT_METHOD_PLAN_MISMATCH");
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        HarnessJson.mapper().writeValue(root.resolve("component-installation-plan.json").toFile(), NativeRuntime.plan(root));
    }
}
