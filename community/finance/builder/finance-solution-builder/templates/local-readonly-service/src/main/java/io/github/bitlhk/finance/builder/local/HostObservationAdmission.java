package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

/** Shared storage admission; runtime installation stays in its own adapter. */
final class HostObservationAdmission {
    static ObservationAssessmentV01 check(Path candidate, Path directory, boolean enabled) {
        return check(candidate, directory, enabled, System.getenv());
    }

    static ObservationAssessmentV01 check(Path candidate, Path directory, boolean enabled, Map<String,String> env) {
        Path referenceFile = candidate.resolve("observation-reference.json");
        if (!Files.exists(referenceFile))
            return ObservationAssessmentV01.assess(null, List.of(), Set.of());
        try {
            var mapper = HarnessJson.mapper();
            var lock = mapper.readTree(candidate.resolve("service-lock.json").toFile());
            verifyLocked(candidate, referenceFile, lock);
            var reference = mapper.readValue(referenceFile.toFile(), Reference.class);
            Path asset = candidate.resolve("assets").resolve(reference.source().substring("repo://".length())).normalize();
            if (!asset.startsWith(candidate.resolve("assets"))) throw new SecurityException("OBSERVATION_ASSET_PATH");
            verifyLocked(candidate, asset, lock);
            byte[] bytes = Files.readAllBytes(asset);
            if (!("sha256:" + ExecutionArchive.hash(bytes)).equals(reference.digest()))
                throw new SecurityException("OBSERVATION_DIGEST_MISMATCH");
            var requirements = mapper.readValue(bytes, ObservationRequirementsV01.class);
            if (!reference.id().equals(requirements.id()) || !reference.version().equals(requirements.version()))
                throw new SecurityException("OBSERVATION_REFERENCE_MISMATCH");
            Set<String> capabilities = new HashSet<>();
            lock.path("capabilities").forEach(value -> capabilities.add(value.asText()));
            Availability archive = enabled ? probe(directory) : Availability.DISABLED;
            boolean authorized = Arrays.asList(env.getOrDefault("FH_SNAPSHOT_CAPTURE_OWNERS", "").split(","))
                    .contains(env.getOrDefault("FH_HISTORY_OWNER", "local-operator"));
            Availability snapshots = !"true".equals(env.get("FH_SNAPSHOTS_ENABLED")) ? Availability.DISABLED
                    : !enabled || !authorized ? Availability.UNKNOWN
                    : SnapshotStorage.available(directory.resolve("model-snapshots"), env)
                        ? Availability.ENABLED : Availability.FAILED;
            var historyQualification = CollectorQualification.check(candidate, "local-host-archive", "execution.history", env);
            var resultQualification = CollectorQualification.check(candidate, "local-host-archive", "capability.result", env);
            var snapshotQualification = CollectorQualification.check(candidate, "local-client-snapshot", "model.client.snapshot", env);
            var collectors = List.of(new ObservationCapabilitiesV01(ObservationCapabilitiesV01.VERSION,
                    "local-host-archive", historyQualification.identity(), List.of(new Capability("execution.history", "0.1", Boundary.HOST,
                    Set.of("requestId", "outcome"), Support.SUPPORTED,
                    historyQualification.verified() ? Verification.VERIFIED : Verification.NOT_CHECKED,
                    historyQualification.evidence(), archive, Authorization.NOT_REQUIRED),
                    new Capability("capability.result", "0.1", Boundary.PROVIDER,
                    Set.of("capabilityId", "providerId", "harnessRequestId", "businessStatus", "at"),
                    Support.SUPPORTED, resultQualification.verified() ? Verification.VERIFIED : Verification.NOT_CHECKED,
                    resultQualification.evidence(), archive, Authorization.NOT_REQUIRED))),
                    new ObservationCapabilitiesV01(ObservationCapabilitiesV01.VERSION, "local-client-snapshot", snapshotQualification.identity(),
                    List.of(new Capability("model.client.snapshot", "0.1", Boundary.MODEL_CLIENT,
                    Set.of("messages", "tools", "parameters", "response"), Support.SUPPORTED,
                    snapshotQualification.verified() ? Verification.VERIFIED : Verification.NOT_CHECKED, snapshotQualification.evidence(),
                    snapshots,
                    authorized ? Authorization.AUTHORIZED : Authorization.NOT_AUTHORIZED))));
            // Snapshot and archive stores are checked independently; no customer body is collected here.
            var assessment = ObservationAssessmentV01.assess(requirements, collectors, capabilities);
            assessment.requireReady();
            return assessment;
        } catch (IllegalStateException | SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("OBSERVATION_ADMISSION_INVALID", failure);
        }
    }

    private static void verifyLocked(Path candidate, Path file, com.fasterxml.jackson.databind.JsonNode lock) throws Exception {
        String relative = candidate.relativize(file).toString().replace('\\', '/');
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 65_536
                || !file.toRealPath().startsWith(candidate.toRealPath())
                || !ExecutionArchive.hash(Files.readAllBytes(file)).equals(lock.path("files").path(relative).asText()))
            throw new SecurityException("OBSERVATION_ASSET_NOT_LOCKED");
    }

    private static Availability probe(Path directory) {
        Path probe = null;
        try {
            if (Files.isSymbolicLink(directory)) return Availability.FAILED;
            Files.createDirectories(directory);
            probe = Files.createTempFile(directory, ".observation-probe-", ".tmp");
            try (var channel = FileChannel.open(probe, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                channel.write(ByteBuffer.wrap(new byte[]{0}));
                channel.force(true);
            }
            return Availability.ENABLED;
        } catch (Exception unavailable) { return Availability.FAILED; }
        finally { if (probe != null) try { Files.deleteIfExists(probe); } catch (Exception unavailable) { } }
    }
}
