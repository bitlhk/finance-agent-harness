package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Existing Host observation qualification envelope, pinned by the operator, never by a solution upload. */
public final class RuntimeQualification {
    public static final String VERSION = "finance-observation-qualification.v0.1";
    public record Evidence(String file, String sha256) {}
    public record Collector(String collectorId, String identity, Set<String> semantics, boolean passed, List<Evidence> evidence) {}
    public record Report(String schemaVersion, List<Collector> collectors) {}
    public record Check(String id, boolean passed, List<Evidence> evidence) {}
    public record Proof(String schemaVersion, String family, String sdkVersion, String status,
                        ObservationCapabilitiesV01 collectorDeclaration, List<RuntimeInventory.Artifact> artifacts, List<Check> checks) {}
    public static final Set<String> REQUIRED_CHECKS = Set.of("native-query", "revoke-before-return", "tool-denial",
            "provider-error", "isolation-parent", "schema-denial", "mode-denial", "required-observation", "lock-integrity",
            "model-single-attempt", "cancel-late", "sink-degradation", "profile-admission", "qualification-admission",
            "compound", "multi-turn", "native-continuation", "missing-data", "diagnostic-boundaries", "redirect-denial", "bank-auth-poison", "terminal-cancel-cleanup", "terminal-delivery-cleanup", "declaration-admission");
    public static final Set<String> TASK_CHECKS = Set.of("task-native-confirmation", "task-repeated-confirmation",
            "task-unknown-recovery", "task-revoke-before-return", "task-owner-isolation", "task-persistent-restart",
            "task-parameter-freeze", "task-cancel-late", "task-storage-failure", "task-http-bank");
    public static final Set<String> METHOD_CHECKS = Set.of("method-native", "method-isolation", "method-revocation",
            "method-unknown-tool", "method-cancel", "method-model-error", "method-parameters", "method-admission",
            "method-qualification", "method-observation", "method-sink-degradation", "method-case-workspace");
    private final List<RuntimeInventory.Artifact> artifacts;
    private final String collectorDigest, digest, source;
    private final boolean taskQualified;
    private final boolean methodOnly;

    RuntimeQualification(List<RuntimeInventory.Artifact> artifacts, String collectorDigest, String digest, String source) {
        this(artifacts, collectorDigest, digest, source, false, false);
    }
    RuntimeQualification(List<RuntimeInventory.Artifact> artifacts, String collectorDigest, String digest, String source,
                         boolean taskQualified, boolean methodOnly) {
        this.artifacts = List.copyOf(artifacts); this.collectorDigest = collectorDigest; this.digest = digest; this.source = source;
        this.taskQualified = taskQualified;
        this.methodOnly = methodOnly;
    }

    public static RuntimeQualification load(Path file, String trustedDigest) throws IOException {
        checkedFile(file, 65536);
        if (trustedDigest == null || !trustedDigest.matches("sha256:[a-f0-9]{64}")
                || !trustedDigest.equals(AssetDigests.sha256(file))) throw new SecurityException("QUALIFICATION_DIGEST_MISMATCH");
        Report report = HarnessJson.mapper().readValue(Files.readAllBytes(file), Report.class);
        if (!VERSION.equals(report.schemaVersion()) || report.collectors() == null || report.collectors().size() != 1)
            throw new SecurityException("QUALIFICATION_UNVERIFIED");
        Collector collector = report.collectors().getFirst();
        if (collector.evidence() == null || collector.evidence().size() != 1)
            throw new SecurityException("COLLECTOR_QUALIFICATION_INCOMPLETE");
        Path root = file.toRealPath().getParent();
        Proof proof = HarnessJson.mapper().readValue(Files.readAllBytes(evidence(root, collector.evidence().getFirst())), Proof.class);
        boolean method = "agentscope-method-qualification-evidence.v0.1".equals(proof.schemaVersion());
        var declaration = method ? AgentScopeObservationCollector.methodDeclaration(true) : AgentScopeObservationCollector.declaration(true);
        var semantics = declaration.capabilities().stream().map(ObservationCapabilitiesV01.Capability::semanticId).collect(java.util.stream.Collectors.toSet());
        if (!declaration.collectorId().equals(collector.collectorId()) || !collector.passed() || !semantics.equals(collector.semantics())
                || collector.evidence() == null || collector.evidence().size() != 1) throw new SecurityException("COLLECTOR_QUALIFICATION_INCOMPLETE");
        boolean task = "agentscope-task-qualification-evidence.v0.1".equals(proof.schemaVersion());
        if (!(method || task || "agentscope-query-qualification-evidence.v0.1".equals(proof.schemaVersion())) || !"agentscope".equals(proof.family())
                || !"2.0.3".equals(proof.sdkVersion()) || !"PASS".equals(proof.status()) || proof.collectorDeclaration() == null
                || !declaration.identityDigest().equals(proof.collectorDeclaration().identityDigest())
                || !identity(proof.artifacts(), declaration.identityDigest()).equals(collector.identity()))
            throw new SecurityException("QUALIFICATION_IDENTITY_MISMATCH");
        Set<String> checks = new HashSet<>();
        for (Check check : proof.checks()) {
            if (!checks.add(check.id()) || !check.passed() || check.evidence() == null || check.evidence().isEmpty())
                throw new SecurityException("QUALIFICATION_CHECK_FAILED");
            Set<Path> distinct = new HashSet<>();
            for (Evidence ref : check.evidence()) {
                Path raw = evidence(root, ref);
                if (!distinct.add(raw)) throw new SecurityException("QUALIFICATION_DUPLICATE_PROOF");
                var test = HarnessJson.mapper().readTree(Files.readAllBytes(raw));
                boolean covered = false;
                for (var id : test.path("checkIds")) if (check.id().equals(id.asText())) covered = true;
                if (!covered || !"PASS".equals(test.path("status").asText()) || test.path("test").asText().isBlank())
                    throw new SecurityException("QUALIFICATION_CHECK_EVIDENCE_FAILED");
            }
            if (Set.of("compound", "multi-turn", "missing-data").contains(check.id()) && distinct.size() != 3)
                throw new SecurityException("QUALIFICATION_REPETITIONS_MISSING");
        }
        if (!checks.containsAll(method ? METHOD_CHECKS : REQUIRED_CHECKS)) throw new SecurityException("QUALIFICATION_CHECKS_MISSING");
        if (task && !checks.containsAll(TASK_CHECKS)) throw new SecurityException("TASK_QUALIFICATION_CHECKS_MISSING");
        if (method && proof.artifacts().stream().anyMatch(a -> a.role().startsWith("PROVIDER:")))
            throw new SecurityException("METHOD_QUALIFICATION_MUST_NOT_INCLUDE_PROVIDER");
        return new RuntimeQualification(proof.artifacts(), declaration.identityDigest(), trustedDigest, file.toUri().toString(), task, method);
    }

    private static void checkedFile(Path file, long limit) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > limit) throw new SecurityException("QUALIFICATION_FILE_INVALID");
    }
    private static Path evidence(Path root, Evidence ref) throws IOException {
        if (ref == null || ref.file() == null || ref.file().isBlank() || ref.sha256() == null) throw new SecurityException("QUALIFICATION_EVIDENCE_INVALID");
        Path path = root.resolve(ref.file()).normalize(); checkedFile(path, 16777216);
        if (!ref.sha256().matches("[a-f0-9]{64}") || !path.startsWith(root) || !path.toRealPath().startsWith(root) || !ref.sha256().equals(AssetDigests.sha256(path).substring(7)))
            throw new SecurityException("QUALIFICATION_EVIDENCE_MISMATCH");
        return path;
    }
    public static String identity(List<RuntimeInventory.Artifact> artifacts, String collectorDigest) {
        try {
            byte[] bytes = HarnessJson.mapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(Map.of("collectorDeclaration", collectorDigest, "artifacts", comparable(artifacts)));
            return "sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Map<String, String> comparable(List<RuntimeInventory.Artifact> artifacts) {
        Map<String, String> result = new TreeMap<>();
        for (var item : artifacts) if (result.putIfAbsent(item.role(), item.coordinate() + "|" + item.version() + "|" + item.digest()) != null)
            throw new SecurityException("QUALIFICATION_DUPLICATE_ARTIFACT");
        return result;
    }
    public void requireCurrent(List<RuntimeInventory.Artifact> actual, String declaration) {
        if (!Objects.equals(collectorDigest, declaration)) throw new SecurityException("COLLECTOR_IDENTITY_MISMATCH");
        if (!comparable(artifacts).equals(comparable(actual))) throw new SecurityException("QUALIFICATION_ARTIFACT_MISMATCH");
    }
    public String digest() { return digest; }
    public void requireMode(boolean actualMethodOnly) {
        if (methodOnly != actualMethodOnly) throw new SecurityException("QUALIFICATION_EXECUTION_MODE_MISMATCH");
    }
    public void requireTask() {
        if (!taskQualified) throw new SecurityException("TASK_RUNTIME_QUALIFICATION_REQUIRED");
    }
    public String source() { return source; }
}
