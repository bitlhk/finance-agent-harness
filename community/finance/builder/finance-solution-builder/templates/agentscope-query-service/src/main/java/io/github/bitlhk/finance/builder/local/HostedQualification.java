package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;

/** Pinned evidence for this Host collector, separate from native runtime qualification. */
final class HostedQualification {
    static final Set<String> REQUIRED = Set.of("native-http", "ag-ui-buffering", "history-isolation", "cancel-generation",
            "revoke-before-delivery", "owner-auth", "model-revision", "task-denial", "snapshot-correlation", "snapshot-redaction",
            "snapshot-permission", "snapshot-queue-failure", "snapshot-storage-failure", "snapshot-reference-before-body",
            "archive-parent-terminal", "artifact-admission", "no-oj-runtime", "qualification-denial");
    static void validate(Path candidate, Path evidence, String collector, String identity) throws Exception {
        var proof = HarnessJson.mapper().readTree(evidence.toFile());
        var lock = HarnessJson.mapper().readTree(candidate.resolve("service-lock.json").toFile());
        if (!"finance-agentscope-host-proof.v0.1".equals(proof.path("schemaVersion").asText())
                || !"PASS".equals(proof.path("status").asText())
                || !identity.equals(proof.path("collectorIdentities").path(collector).asText())
                || !lock.path("hostTemplateDigest").equals(proof.path("hostTemplateDigest"))
                || !AgentScopeAdmission.qualification().digest().equals(proof.path("nativeQualification").asText()))
            throw new SecurityException("HOST_QUALIFICATION_IDENTITY_MISMATCH");
        var ids = new HashSet<String>();
        for (var check : proof.path("checks")) {
            String id = check.path("id").asText();
            if (!ids.add(id) || !check.path("passed").isBoolean() || !check.path("passed").asBoolean()
                    || !check.path("evidence").isArray() || check.path("evidence").isEmpty())
                throw new SecurityException("HOST_QUALIFICATION_CHECK_FAILED");
            for (var ref : check.path("evidence")) {
                Path file = evidence.getParent().resolve(ref.path("file").asText()).normalize();
                if (!file.startsWith(evidence.getParent()) || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                        || !file.toRealPath().startsWith(evidence.getParent().toRealPath()) || Files.size(file) > 16_777_216
                        || !ExecutionArchive.hash(Files.readAllBytes(file)).equals(ref.path("sha256").asText()))
                    throw new SecurityException("HOST_QUALIFICATION_RAW_EVIDENCE_MISMATCH");
            }
        }
        if (!ids.containsAll(REQUIRED)) throw new SecurityException("HOST_QUALIFICATION_CHECKS_MISSING");
    }
}
