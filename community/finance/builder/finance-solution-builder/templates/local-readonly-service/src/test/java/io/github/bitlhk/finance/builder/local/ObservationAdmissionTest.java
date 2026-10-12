package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

public class ObservationAdmissionTest {
    private ObservationAssessmentV01 check(Path root, Path history, boolean enabled, Map<String,String> supplied) throws Exception {
        var env = new HashMap<>(supplied);
        Path evidence = root.resolve("controlled-evidence.json");
        Files.writeString(evidence, "{\"scope\":\"unit-test-fixture-not-real-runtime-acceptance\"}");
        var entries = new ArrayList<Map<String,Object>>();
        for (String collector : List.of("local-host-archive", "local-client-snapshot")) {
            entries.add(Map.of("collectorId", collector, "identity", CollectorQualification.identity(root, collector),
                "semantics", List.of("execution.history", "capability.result", "model.client.snapshot"), "passed", true,
                "evidence", List.of(Map.of("file", evidence.getFileName().toString(),
                    "sha256", ExecutionArchive.hash(Files.readAllBytes(evidence))))));
        }
        Path qualification = root.resolve("qualification.json");
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(Map.of("schemaVersion", "finance-observation-qualification.v0.1",
                "collectors", entries));
        Files.write(qualification, bytes);
        env.put("FH_OBSERVATION_QUALIFICATION_FILE", qualification.toString());
        env.put("FH_OBSERVATION_QUALIFICATION_SHA256", ExecutionArchive.hash(bytes));
        return ObservationAdmission.check(root, history, enabled, env);
    }
    @Test public void changingEnvironmentDoesNotChangeFrozenDefinitions() throws Exception {
        var mapper = HarnessJson.mapper();
        var first = mapper.readTree("{\"observationAssessment\":{\"status\":\"READY\",\"items\":[{\"readiness\":\"READY\",\"capture\":\"NOT_COLLECTED\",\"descriptor\":{\"semanticVersion\":\"0.1\",\"availability\":\"ENABLED\",\"authorization\":\"AUTHORIZED\"}}]}}");
        var second = first.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) second.at("/observationAssessment/items/0/descriptor"))
                .put("availability", "DISABLED").put("authorization", "NOT_AUTHORIZED");
        assertEquals(ObservationAdmission.stablePlan(first), ObservationAdmission.stablePlan(second));
        ((com.fasterxml.jackson.databind.node.ObjectNode) second.at("/observationAssessment/items/0/descriptor"))
                .put("semanticVersion", "9");
        assertNotEquals(ObservationAdmission.stablePlan(first), ObservationAdmission.stablePlan(second));
    }
    private Path candidate(Path parent, Obligation obligation, Content content, String semantic) throws Exception {
        Path root = Files.createDirectory(parent.resolve("candidate"));
        Files.createDirectory(root.resolve("assets"));
        var mapper = HarnessJson.mapper();
        var requirements = new ObservationRequirementsV01(VERSION, "test-observation", "0.1",
                List.of(new Requirement(semantic, "0.1", obligation,
                        semantic.equals("execution.history") ? Boundary.HOST : Boundary.MODEL_CLIENT,
                        content, semantic.equals("execution.history") ? Set.of("requestId", "outcome") : Set.of("messages"), Set.of())));
        byte[] body = mapper.writeValueAsBytes(requirements);
        Files.write(root.resolve("assets/observation.json"), body);
        byte[] reference = mapper.writeValueAsBytes(new Reference("test-observation", "0.1",
                "repo://observation.json", "sha256:" + ExecutionArchive.hash(body)));
        Files.write(root.resolve("observation-reference.json"), reference);
        mapper.writeValue(root.resolve("service-lock.json").toFile(), Map.of("capabilities", List.of("account.balance.query"),
                "files", Map.of("assets/observation.json", ExecutionArchive.hash(body),
                        "observation-reference.json", ExecutionArchive.hash(reference))));
        return root;
    }
    @Test public void requiredDisabledRejectsWithoutCreatingArchive() throws Exception {
        Path temp = Files.createTempDirectory("observation-disabled");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        assertThrows(IllegalStateException.class, () -> check(root, temp.resolve("history"), false, Map.of()));
        assertFalse(Files.exists(temp.resolve("history")));
    }
    @Test public void actualWritableHistoryPassesButDoesNotClaimCaptured() throws Exception {
        Path temp = Files.createTempDirectory("observation-write");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        var report = check(root, temp.resolve("history"), true, Map.of());
        assertEquals(ObservationAssessmentV01.Status.READY, report.status());
        assertEquals(ObservationAssessmentV01.Capture.NOT_COLLECTED, report.items().getFirst().capture());
        try (var files = Files.list(temp.resolve("history"))) { assertEquals(0, files.count()); }
    }
    @Test public void requiredUnwritableRejectsOptionalDoesNot() throws Exception {
        for (var obligation : List.of(Obligation.REQUIRED, Obligation.OPTIONAL)) {
            Path temp = Files.createTempDirectory("observation-write-denied");
            Path root = candidate(temp, obligation, Content.METADATA, "execution.history");
            Path notDirectory = Files.writeString(temp.resolve("history"), "occupied");
            if (obligation == Obligation.REQUIRED)
                assertThrows(IllegalStateException.class, () -> check(root, notDirectory, true, Map.of()));
            else assertEquals(ObservationAssessmentV01.Status.OPTIONAL_GAPS,
                    check(root, notDirectory, true, Map.of()).status());
        }
    }
    @Test public void digestTamperRejectsBeforeAnyProbe() throws Exception {
        Path temp = Files.createTempDirectory("observation-tamper");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        Files.writeString(root.resolve("assets/observation.json"), "{}");
        assertThrows(SecurityException.class, () -> ObservationAdmission.check(root, temp.resolve("history"), true, Map.of()));
        assertFalse(Files.exists(temp.resolve("history")));
    }
    @Test public void requiredBodyNeedsOwnerAuthorization() throws Exception {
        Path temp = Files.createTempDirectory("observation-body");
        Path root = candidate(temp, Obligation.REQUIRED, Content.DIAGNOSTIC_BODY, "model.client.snapshot");
        var failure = assertThrows(ObservationAssessmentV01.UnmetRequirements.class,
                () -> check(root, temp.resolve("history"), true, Map.of("FH_SNAPSHOTS_ENABLED", "true")));
        assertEquals(ObservationAssessmentV01.Readiness.NOT_AUTHORIZED, failure.assessment().items().getFirst().readiness());
    }
    @Test public void authorizedSnapshotUsesIndependentStorageAndPreservesHistory() throws Exception {
        Path temp = Files.createTempDirectory("observation-snapshot-ready");
        Path root = candidate(temp, Obligation.REQUIRED, Content.DIAGNOSTIC_BODY, "model.client.snapshot");
        Path history = Files.createDirectory(temp.resolve("history"));
        var env = Map.of("FH_SNAPSHOTS_ENABLED", "true", "FH_HISTORY_OWNER", "tester",
                "FH_SNAPSHOT_CAPTURE_OWNERS", "tester", "FH_SNAPSHOT_MAX_FILES", "1");
        assertEquals(ObservationAssessmentV01.Status.READY,
                check(root, history, true, env).status());
        Path bodies = history.resolve("model-snapshots");
        Path retained = Files.writeString(bodies.resolve("retained.json"), "retained-evidence");
        var rejected = assertThrows(ObservationAssessmentV01.UnmetRequirements.class,
                () -> check(root, history, true, env));
        assertEquals(ObservationAssessmentV01.Readiness.FAILED, rejected.assessment().items().getFirst().readiness());
        assertEquals("retained-evidence", Files.readString(retained));
    }
    @Test public void writableHistoryCannotCertifyBlockedSnapshotDirectory() throws Exception {
        Path temp = Files.createTempDirectory("observation-snapshot-blocked");
        Path root = candidate(temp, Obligation.REQUIRED, Content.DIAGNOSTIC_BODY, "model.client.snapshot");
        Path history = Files.createDirectory(temp.resolve("history"));
        Files.writeString(history.resolve("model-snapshots"), "not-a-directory");
        assertThrows(ObservationAssessmentV01.UnmetRequirements.class,
                () -> check(root, history, true,
                        Map.of("FH_SNAPSHOTS_ENABLED", "true", "FH_SNAPSHOT_CAPTURE_OWNERS", "local-operator")));
    }
    @Test public void legacyWithoutDeclarationRemainsUnassessed() throws Exception {
        Path root = Files.createTempDirectory("observation-legacy");
        var report = ObservationAdmission.check(root, root.resolve("history"), false, Map.of());
        assertEquals(ObservationAssessmentV01.Status.LEGACY_UNASSESSED, report.status());
        assertFalse(Files.exists(root.resolve("history")));
    }
    @Test public void missingQualificationDoesNotInheritVerifiedFromCodeLabel() throws Exception {
        Path temp = Files.createTempDirectory("observation-unqualified");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        var failure = assertThrows(ObservationAssessmentV01.UnmetRequirements.class,
                () -> ObservationAdmission.check(root, temp.resolve("history"), true, Map.of()));
        assertEquals(ObservationAssessmentV01.Readiness.UNVERIFIED, failure.assessment().items().getFirst().readiness());
    }
    @Test public void qualificationDigestAndEvidenceAreBothChecked() throws Exception {
        Path temp = Files.createTempDirectory("observation-qualification");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        check(root, temp.resolve("history"), true, Map.of());
        Path qualification = root.resolve("qualification.json");
        var env = Map.of("FH_OBSERVATION_QUALIFICATION_FILE", qualification.toString(),
                "FH_OBSERVATION_QUALIFICATION_SHA256", ExecutionArchive.hash(Files.readAllBytes(qualification)));
        Files.writeString(root.resolve("controlled-evidence.json"), "changed");
        assertThrows(IllegalStateException.class,
                () -> ObservationAdmission.check(root, temp.resolve("history"), true, env));
        Files.writeString(qualification, "{}");
        assertThrows(IllegalStateException.class,
                () -> ObservationAdmission.check(root, temp.resolve("history"), true, env));
    }
    @Test public void changedRuntimeIdentityDoesNotReuseQualification() throws Exception {
        Path temp = Files.createTempDirectory("observation-version-bound");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        check(root, temp.resolve("history"), true, Map.of());
        Path qualification = root.resolve("qualification.json");
        var env = Map.of("FH_OBSERVATION_QUALIFICATION_FILE", qualification.toString(),
                "FH_OBSERVATION_QUALIFICATION_SHA256", ExecutionArchive.hash(Files.readAllBytes(qualification)));
        var mapper = HarnessJson.mapper();
        var lock = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(root.resolve("service-lock.json").toFile());
        lock.putObject("runtime").put("language", "java").put("integrationRef", "changed-version");
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        var failure = assertThrows(ObservationAssessmentV01.UnmetRequirements.class,
                () -> ObservationAdmission.check(root, temp.resolve("history"), true, env));
        assertEquals(ObservationAssessmentV01.Readiness.UNVERIFIED, failure.assessment().items().getFirst().readiness());
    }
    @Test public void unrelatedBuilderRevisionDoesNotInvalidateCollectorQualification() throws Exception {
        Path temp = Files.createTempDirectory("observation-builder-revision");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        var mapper = HarnessJson.mapper();
        var lock = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(root.resolve("service-lock.json").toFile());
        lock.putObject("sources").putObject("builder").put("commit", "before");
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        String before = CollectorQualification.identity(root, "local-host-archive");
        ((com.fasterxml.jackson.databind.node.ObjectNode) lock.path("sources").path("builder")).put("commit", "after");
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        assertEquals(before, CollectorQualification.identity(root, "local-host-archive"));
    }
    @Test public void repackedProjectJarKeepsIdentityButChangedContentDoesNot() throws Exception {
        Path temp = Files.createTempDirectory("observation-repacked-jar");
        Path root = candidate(temp, Obligation.REQUIRED, Content.METADATA, "execution.history");
        Path jar = Files.createDirectories(root.resolve("target/dependency"))
                .resolve("finance-harness-core-0.1.0-SNAPSHOT.jar");
        var mapper = HarnessJson.mapper();
        var lock = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(root.resolve("service-lock.json").toFile());
        var files = (com.fasterxml.jackson.databind.node.ObjectNode) lock.path("files");
        String name = "target/dependency/" + jar.getFileName();
        writeJar(jar, "same", 1_000L);
        files.put(name, ExecutionArchive.hash(Files.readAllBytes(jar)));
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        String before = CollectorQualification.identity(root, "local-host-archive");
        writeJar(jar, "same", 2_000L);
        files.put(name, ExecutionArchive.hash(Files.readAllBytes(jar)));
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        assertEquals(before, CollectorQualification.identity(root, "local-host-archive"));
        writeJar(jar, "changed", 2_000L);
        assertThrows(IllegalStateException.class, () -> CollectorQualification.identity(root, "local-host-archive"));
        files.put(name, ExecutionArchive.hash(Files.readAllBytes(jar)));
        mapper.writeValue(root.resolve("service-lock.json").toFile(), lock);
        assertNotEquals(before, CollectorQualification.identity(root, "local-host-archive"));
    }
    private static void writeJar(Path jar, String content, long time) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            var entry = new JarEntry("io/example/Collector.class");
            entry.setTime(time);
            output.putNextEntry(entry);
            output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }
}
