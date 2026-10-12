package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Trusted local Host qualification input, not an uploaded asset or an authorization grant. */
final class CollectorQualification {
    record Checked(String identity, List<String> evidence) {
        boolean verified() { return !evidence.isEmpty(); }
    }
    static String identity(Path candidate, String collector) throws Exception {
        var mapper = HarnessJson.mapper();
        var lock = mapper.readTree(candidate.resolve("service-lock.json").toFile());
        var parts = new TreeMap<String,Object>();
        parts.put("collector", collector);
        parts.put("runtime", lock.path("runtime"));
        parts.put("hostTemplateDigest", lock.path("hostTemplateDigest"));
        parts.put("sharedHostSources", lock.path("sharedHostSources"));
        Path hostArtifact = Path.of(LocalAgentHost.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(hostArtifact)) {
            String relative = candidate.toAbsolutePath().normalize().relativize(hostArtifact.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (relative.startsWith("../") || !lock.path("files").has(relative))
                throw new IllegalStateException("COLLECTOR_HOST_ARTIFACT_NOT_LOCKED");
            parts.put("hostArtifactContents", jarContents(hostArtifact, lock.path("files").path(relative).asText()));
        }
        var dependencies = new TreeMap<String,String>();
        lock.path("files").fields().forEachRemaining(entry -> {
            if ((entry.getKey().startsWith("target/dependency/") && entry.getKey().endsWith(".jar"))
                    || entry.getKey().equals("python-dependencies.json")) {
                try {
                    String digest = entry.getValue().asText();
                    if (entry.getKey().startsWith("target/dependency/finance-harness-")
                            || entry.getKey().startsWith("target/dependency/finance-provider-"))
                        digest = jarContents(candidate.resolve(entry.getKey()), digest);
                    dependencies.put(entry.getKey(), digest);
                } catch (Exception failure) {
                    throw new IllegalStateException("COLLECTOR_DEPENDENCY_INVALID", failure);
                }
            }
        });
        parts.put("lockedDependencies", dependencies);
        var classes = collector.equals("local-host-archive")
                ? List.of(ExecutionArchive.class, ExecutionArchive.Request.class, HostObservationAdmission.class,
                    io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider.class)
                : List.of(ModelSnapshots.class, SnapshotStorage.class, SnapshotContentFilter.class, HostObservationAdmission.class);
        var anchors = new ArrayList<Class<?>>(classes);
        anchors.addAll(List.of(LocalAgentHost.class, NativeConversation.class, NativeRuntime.class,
                BankAccess.class, ModelConnections.class, ModelConnections.Generation.class,
                ModelConnections.GenerationBinding.class, TerminalSession.class, ComponentAdmission.class));
        for (var type : anchors) {
            try (var stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IllegalStateException("COLLECTOR_ARTIFACT_UNAVAILABLE");
                parts.put(type.getName(), ExecutionArchive.hash(stream.readAllBytes()));
            }
        }
        // Python hooks are part of the collector identity, not interchangeable with Java hooks.
        if (lock.path("runtime").path("language").asText().equals("python")) {
            for (String name : List.of("finance_python/agent.py", "finance_python/snapshots.py", "finance_python/bridge.py")) {
                Path path = candidate.resolve(name);
                String digest = ExecutionArchive.hash(Files.readAllBytes(path));
                if (!digest.equals(lock.path("files").path(name).asText()))
                    throw new IllegalStateException("COLLECTOR_ARTIFACT_MISMATCH");
                parts.put(name, digest);
            }
        }
        return ExecutionArchive.hash(mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsBytes(parts));
    }
    private static String jarContents(Path file, String expected) throws Exception {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || !ExecutionArchive.hash(Files.readAllBytes(file)).equals(expected))
            throw new IllegalStateException("COLLECTOR_DEPENDENCY_MISMATCH");
        var entries = new TreeMap<String,String>();
        try (var jar = new JarFile(file.toFile())) {
            var names = jar.entries();
            while (names.hasMoreElements()) {
                var entry = names.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name.equalsIgnoreCase("META-INF/MANIFEST.MF")
                        || name.startsWith("META-INF/maven/")) continue;
                try (var stream = jar.getInputStream(entry)) {
                    if (entries.putIfAbsent(name, ExecutionArchive.hash(stream.readAllBytes())) != null)
                        throw new IllegalStateException("COLLECTOR_DEPENDENCY_DUPLICATE_ENTRY");
                }
            }
        }
        return ExecutionArchive.hash(HarnessJson.mapper().writeValueAsBytes(entries));
    }
    static Checked check(Path candidate, String collector, String semantic, Map<String,String> env) throws Exception {
        String identity = identity(candidate, collector);
        String configured = env.get("FH_OBSERVATION_QUALIFICATION_FILE");
        if (configured == null) return new Checked(identity, List.of());
        Path path = Path.of(configured).toAbsolutePath();
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path) || Files.size(path) > 65536)
            throw new IllegalStateException("QUALIFICATION_FILE_INVALID");
        byte[] bytes = Files.readAllBytes(path);
        if (!ExecutionArchive.hash(bytes).equals(env.get("FH_OBSERVATION_QUALIFICATION_SHA256")))
            throw new IllegalStateException("QUALIFICATION_DIGEST_MISMATCH");
        var value = HarnessJson.mapper().readTree(bytes);
        if (!value.path("schemaVersion").asText().equals("finance-observation-qualification.v0.1")
                || !value.path("collectors").isArray()) throw new IllegalStateException("QUALIFICATION_VERSION_INVALID");
        List<String> evidence = new ArrayList<>();
        for (var entry : value.path("collectors")) {
            if (!collector.equals(entry.path("collectorId").asText()) || !identity.equals(entry.path("identity").asText())) continue;
            boolean covered = false;
            for (var name : entry.path("semantics")) if (semantic.equals(name.asText())) covered = true;
            if (!covered || !entry.path("passed").asBoolean(false)) continue;
            for (var ref : entry.path("evidence")) {
                String relative = ref.path("file").asText();
                Path file = path.getParent().resolve(relative).normalize();
                if (relative.isBlank() || !file.startsWith(path.getParent()) || Files.isSymbolicLink(file)
                        || !Files.isRegularFile(file) || Files.size(file) > 16_777_216
                        || !file.toRealPath().startsWith(path.getParent().toRealPath()))
                    throw new IllegalStateException("QUALIFICATION_EVIDENCE_INVALID");
                String digest = ExecutionArchive.hash(Files.readAllBytes(file));
                if (!digest.equals(ref.path("sha256").asText()))
                    throw new IllegalStateException("QUALIFICATION_EVIDENCE_MISMATCH");
                NativeRuntime.validateCollectorEvidence(candidate, file, collector, identity);
                evidence.add("sha256:" + digest);
            }
        }
        return new Checked(identity, evidence.stream().distinct().sorted().toList());
    }
    public static void main(String[] args) throws Exception {
        Path candidate = Path.of(args[0]);
        System.out.println(HarnessJson.mapper().writeValueAsString(Map.of(
                "local-host-archive", identity(candidate, "local-host-archive"),
                "local-client-snapshot", identity(candidate, "local-client-snapshot"))));
    }
}
