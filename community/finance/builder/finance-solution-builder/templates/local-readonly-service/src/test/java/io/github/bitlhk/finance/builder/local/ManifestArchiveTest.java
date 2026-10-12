package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;

public class ManifestArchiveTest {
    private byte[] json(int size) {
        String prefix = "{\"capabilities\":[],\"padding\":\"", suffix = "\"}";
        return (prefix + "x".repeat(size - prefix.length() - suffix.length()) + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private Path candidate(Path temp, int size) throws Exception {
        Path result = Files.createDirectory(temp.resolve("candidate"));
        Files.write(result.resolve("service-lock.json"), json(size));
        return result;
    }
    private void cleanup(Path temp) throws Exception {
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
    @Test public void exact65849ByteManifestIsCompleteAndDigestBound() throws Exception {
        Path temp = Files.createTempDirectory("manifest-65849");
        try {
            Path candidate = candidate(temp, 65_849);
            var archive = new ExecutionArchive(candidate, "build", "instance", Map.of());
            var request = archive.begin(candidate, "session", "subject", 0);
            request.labels(Map.of("label", "fixed"));
            request.installation(Map.of("runtime", "python", "padding", "x".repeat(65_849)));
            request.finish(Outcome.COMPLETED);
            assertEquals("RECORDING", request.receipt().get("archiveStatus"));
            var rows = Files.readAllLines(archive.directory.resolve(request.id + ".jsonl"));
            var header = HarnessJson.mapper().readTree(rows.getFirst());
            byte[] persisted = Files.readAllBytes(archive.directory.resolve(request.id + ".service-lock.json"));
            assertArrayEquals(json(65_849), persisted);
            assertEquals(ExecutionArchive.hash(persisted), header.path("serviceLockDigest").asText());
            assertEquals(archive.reservationBytes, header.path("archiveReservationBytes").asLong());
            assertTrue(HarnessJson.mapper().readTree(rows.getLast()).path("complete").asBoolean());
            assertTrue(rows.stream().anyMatch(s -> s.contains("installation-display")));
        } finally { cleanup(temp); }
    }
    @Test public void configuredLimitsRemainFiniteAndSeparateFromEventsAndLabels() throws Exception {
        Path temp = Files.createTempDirectory("manifest-limits");
        try {
            Path candidate = candidate(temp, 65_849);
            var low = new ExecutionArchive(candidate, "build", "instance", Map.of("FH_HISTORY_MANIFEST_BYTES", "-1"));
            assertEquals(65_536, low.maxManifestBytes);
            assertEquals("DEGRADED", low.begin(candidate, "s", "p", 0).receipt().get("archiveStatus"));
            var high = new ExecutionArchive(candidate, "build", "instance", Map.of("FH_HISTORY_MANIFEST_BYTES", "999999999"));
            assertEquals(1_048_576, high.maxManifestBytes);
            var normal = new ExecutionArchive(candidate, "build", "instance", Map.of("FH_HISTORY_MANIFEST_BYTES", "invalid"));
            assertEquals(262_144, normal.maxManifestBytes);
            var request = normal.begin(candidate, "s", "p", 0);
            request.labels(Map.of("padding", "x".repeat(65_536)));
            request.finish(Outcome.COMPLETED);
            var rows = Files.readAllLines(normal.directory.resolve(request.id + ".jsonl"));
            assertTrue(rows.stream().anyMatch(s -> s.contains("UNAVAILABLE")));
            assertTrue(HarnessJson.mapper().readTree(rows.getLast()).path("complete").asBoolean());
            var event = normal.begin(candidate, "s", "p", 0);
            assertFalse(event.snapshotMetadata(Map.of("padding", "x".repeat(16_384))));
            assertEquals("REQUEST_EVENT_QUOTA", event.receipt().get("failureCode"));
        } finally { cleanup(temp); }
    }
    @Test public void oversizedTruncatedAndNonObjectManifestsAreRejectedWithoutPublication() throws Exception {
        Path temp = Files.createTempDirectory("manifest-invalid");
        try {
            Path file = temp.resolve("manifest.json");
            for (byte[] bytes : List.of(json(1_048_577), "{\"partial\":".getBytes(), "{}{}".getBytes(), "[]".getBytes())) {
                Files.write(file, bytes);
                assertThrows(Exception.class, () -> ExecutionArchive.readManifest(file, 1_048_576));
                assertThrows(Exception.class, () -> ExecutionArchive.writeImmutable(temp.resolve("published.json"), bytes, 1_048_576));
                assertFalse(Files.exists(temp.resolve("published.json")));
            }
            Files.write(file, json(1_048_576));
            assertEquals(1_048_576, ExecutionArchive.readManifest(file, 1_048_576).length);
            ExecutionArchive.writeImmutable(temp.resolve("published.json"), json(1_048_576), 1_048_576);
            assertArrayEquals(json(1_048_576), Files.readAllBytes(temp.resolve("published.json")));
        } finally { cleanup(temp); }
    }
    @Test public void immutablePublicationNeverOverwritesAndRejectsLinksAndIoFailure() throws Exception {
        Path temp = Files.createTempDirectory("manifest-io");
        try {
            Path file = temp.resolve("published.json");
            ExecutionArchive.writeImmutable(file, json(65_849), 262_144);
            assertThrows(FileAlreadyExistsException.class, () -> ExecutionArchive.writeImmutable(file, json(100), 262_144));
            assertArrayEquals(json(65_849), Files.readAllBytes(file));
            Path link = temp.resolve("link.json");
            Files.createSymbolicLink(link, file);
            assertThrows(Exception.class, () -> ExecutionArchive.readManifest(link, 262_144));
            assertThrows(Exception.class, () -> ExecutionArchive.writeImmutable(link, json(100), 262_144));
            assertThrows(Exception.class, () -> ExecutionArchive.writeImmutable(file.resolve("child"), json(100), 262_144));
            try (var files = Files.list(temp)) { assertFalse(files.anyMatch(p -> p.getFileName().toString().startsWith(".manifest-"))); }
        } finally { cleanup(temp); }
    }
    @Test public void diagnosticListenerFailureCannotDropThePrimaryTrace() throws Exception {
        Path temp = Files.createTempDirectory("manifest-listener");
        try {
            Path candidate = candidate(temp, 100);
            var archive = new ExecutionArchive(candidate, "build", "instance", Map.of());
            var request = archive.begin(candidate, "session", "subject", 0);
            request.observeEvents(event -> { throw new IllegalStateException("diagnostic-only"); });
            try (var span = request.run.start(request.root.id(), "runtime-tool", "task", Map.of("capabilityId", "synthetic"))) {
                span.finish(Outcome.RETURNED, Map.of());
            }
            request.finish(Outcome.COMPLETED);
            assertEquals(0L, request.run.droppedEvents());
            assertEquals(0L, request.receipt().get("sinkRejectedEvents"));
            assertEquals(3L, request.receipt().get("listenerFailures"));
            var rows = Files.readAllLines(archive.directory.resolve(request.id + ".jsonl"));
            assertTrue(rows.stream().anyMatch(row -> row.contains("runtime-tool") && row.contains("\"phase\":\"END\"")));
            assertTrue(HarnessJson.mapper().readTree(rows.getLast()).path("complete").asBoolean());
        } finally { cleanup(temp); }
    }

    @Test public void diskFailureDegradesObservationWithoutChangingBusinessOutcome() throws Exception {
        Path temp = Files.createTempDirectory("manifest-disk");
        try {
            Path candidate = candidate(temp, 65_849);
            var archive = new ExecutionArchive(candidate, "build", "instance", Map.of());
            var request = archive.begin(candidate, "s", "p", 0);
            // A filesystem fault at the exact destination, not a change to the business result.
            Files.createDirectory(archive.directory.resolve(request.id + ".runtime-lock.json"));
            request.installation(Map.of("runtime", "python"));
            request.finish(Outcome.COMPLETED);
            assertEquals("DEGRADED", request.receipt().get("archiveStatus"));
            assertEquals("VERSION_RECORD_FAILED", request.receipt().get("failureCode"));
            assertFalse(Files.readString(archive.directory.resolve(request.id + ".jsonl")).contains("\"complete\":true"));
        } finally { cleanup(temp); }
    }
    @Test public void mixedManifestSettingsCannotUnderreserveEarlierRequests() throws Exception {
        Path temp = Files.createTempDirectory("manifest-quota");
        try {
            Path candidate = candidate(temp, 100);
            var large = new ExecutionArchive(candidate, "b", "i", Map.of("FH_HISTORY_MANIFEST_BYTES", "1048576"));
            var first = large.begin(candidate, "s", "p", 0);
            assertEquals("RECORDING", first.receipt().get("archiveStatus"));
            var small = new ExecutionArchive(candidate, "b", "i", Map.of("FH_HISTORY_MANIFEST_BYTES", "65536",
                    "FH_HISTORY_TOTAL_BYTES", "2500000"));
            assertTrue(large.reservationBytes + small.reservationBytes > small.maxTotalBytes);
            assertEquals("DEGRADED", small.begin(candidate, "s", "p", 0).receipt().get("archiveStatus"));
        } finally { cleanup(temp); }
    }
    @Test public void legacyHistoryWithoutReservationIsRetainedAndCountedWithNewHistory() throws Exception {
        Path temp = Files.createTempDirectory("manifest-legacy-quota");
        try {
            Path candidate = candidate(temp, 100);
            Path history = Files.createDirectory(temp.resolve("execution-history"));
            Path legacy = history.resolve(UUID.randomUUID() + ".jsonl");
            byte[] original = ("{\"recordType\":\"request\",\"schemaVersion\":\"finance-execution-history.v0.1\"}\n"
                    + "{\"recordType\":\"terminal\",\"complete\":true}\n").getBytes();
            Files.write(legacy, original);
            for (int i = 1; i < 62; i++) Files.write(history.resolve(UUID.randomUUID() + ".jsonl"), original);
            var normal = new ExecutionArchive(candidate, "b", "i", Map.of());
            var next = normal.begin(candidate, "s", "p", 0);
            next.finish(Outcome.COMPLETED);
            assertEquals("RECORDING", next.receipt().get("archiveStatus"));
            assertArrayEquals(original, Files.readAllBytes(legacy));
            var tight = new ExecutionArchive(candidate, "b", "i", Map.of("FH_HISTORY_TOTAL_BYTES", "78000000"));
            assertEquals("DEGRADED", tight.begin(candidate, "s", "p", 0).receipt().get("archiveStatus"));
            assertArrayEquals(original, Files.readAllBytes(legacy));
            try (var paths = Files.list(history)) {
                assertEquals(63, paths.filter(p -> p.toString().endsWith(".jsonl")).count());
            }
        } finally { cleanup(temp); }
    }
}
