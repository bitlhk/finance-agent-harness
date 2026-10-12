package io.github.bitlhk.finance.builder.local;

import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;

public class ExecutionArchiveTest {
    @Test public void requestFreezesAgentNameTypeAndInstalledCapabilities() throws Exception {
        Path temp = Files.createTempDirectory("history-header-test");
        try {
            Path candidate = Files.createDirectory(temp.resolve("candidate"));
            Files.writeString(candidate.resolve("service-lock.json"), "{\"capabilities\":[\"balance\",\"transfer\"],\"interface\":{\"configuration\":{\"assistantName\":\"客户金融助手\"}}}");
            var archive = new ExecutionArchive(candidate, "test-build", "test-instance");
            var request = archive.begin(candidate, "session", "subject", 0, "CONVERSATION_TURN");
            request.finish(Outcome.COMPLETED);
            String header = Files.readAllLines(archive.directory.resolve(request.id + ".jsonl")).getFirst();
            assertTrue(header.contains("\"agentName\":\"客户金融助手\""));
            assertTrue(header.contains("\"requestType\":\"CONVERSATION_TURN\""));
            assertTrue(header.contains("\"capabilities\":[\"balance\",\"transfer\"]"));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    @Test public void labelsBindInstallationAndFailureDoesNotRewriteBusinessOutcome() throws Exception {
        Path temp = Files.createTempDirectory("history-label-test");
        try {
            Path candidate = Files.createDirectory(temp.resolve("candidate"));
            Files.writeString(candidate.resolve("service-lock.json"), "{\"capabilities\":[]}");
            var archive = new ExecutionArchive(candidate, "test-build", "test-instance");
            for (boolean fail : new boolean[]{false, true}) {
                var request = archive.begin(candidate, "session", "subject", 0);
                request.labels(fail ? null : java.util.Map.of("schemaVersion", "fixture"));
                request.installation(java.util.Map.of("installation", "fixed"));
                request.finish(Outcome.COMPLETED);
                var rows = Files.readAllLines(archive.directory.resolve(request.id + ".jsonl"));
                assertTrue(rows.stream().anyMatch(line -> line.contains(fail ? "UNAVAILABLE" : "installation-display")));
                assertTrue(rows.getLast().contains("COMPLETED"));
                assertFalse(rows.getLast().contains("false"));
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    @Test public void repeatedIdenticalInstallationIsIdempotentButChangedLockDegrades() throws Exception {
        Path temp = Files.createTempDirectory("history-installation-idempotency");
        try {
            Path candidate = Files.createDirectory(temp.resolve("candidate"));
            Files.writeString(candidate.resolve("service-lock.json"), "{\"capabilities\":[]}");
            var archive = new ExecutionArchive(candidate, "test-build", "test-instance");
            var same = archive.begin(candidate, "session", "subject", 0);
            same.installation(java.util.Map.of("runtime", "fixed"));
            same.installation(java.util.Map.of("runtime", "fixed"));
            same.finish(Outcome.COMPLETED);
            assertEquals("RECORDING", same.receipt().get("archiveStatus"));
            var rows = Files.readAllLines(archive.directory.resolve(same.id + ".jsonl"));
            assertEquals(1, rows.stream().filter(line -> line.contains("installation-lock") && line.contains("\"phase\":\"START\"")).count());
            assertTrue(rows.getLast().contains("\"complete\":true"));

            var changed = archive.begin(candidate, "session", "subject", 0);
            changed.installation(java.util.Map.of("runtime", "first"));
            changed.installation(java.util.Map.of("runtime", "second"));
            changed.finish(Outcome.COMPLETED);
            assertEquals("DEGRADED", changed.receipt().get("archiveStatus"));
            assertEquals("VERSION_RECORD_FAILED", changed.receipt().get("failureCode"));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    @Test(timeout=5000) public void finishDoesNotHoldArchiveMonitorWhileWaitingForRun() throws Exception {
        Path temp = Files.createTempDirectory("history-lock-test");
        try {
            Path candidate = Files.createDirectory(temp.resolve("candidate"));
            Files.writeString(candidate.resolve("service-lock.json"), "{\"capabilities\":[]}");
            var archive = new ExecutionArchive(candidate, "test-build", "test-instance");
            var request = archive.begin(candidate, "test-session", "test-subject", 0);
            var accessible = new CountDownLatch(1);
            Thread finishing = new Thread(() -> request.finish(Outcome.COMPLETED));
            finishing.setDaemon(true);
            Thread probe = new Thread(() -> { synchronized (request) { accessible.countDown(); } });
            probe.setDaemon(true);
            synchronized (request.run) {
                finishing.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
                while (finishing.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1);
                assertEquals(Thread.State.BLOCKED, finishing.getState());
                probe.start();
                assertTrue("finish must not hold the sink monitor while waiting for event emission", accessible.await(1, TimeUnit.SECONDS));
            }
            finishing.join(1000); probe.join(1000);
            assertFalse(finishing.isAlive()); assertFalse(probe.isAlive());
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
