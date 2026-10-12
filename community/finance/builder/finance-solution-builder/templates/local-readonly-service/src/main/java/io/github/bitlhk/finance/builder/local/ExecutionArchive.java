package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** Single-host, bounded append archive. Each request has one writer and an immutable version file. */
final class ExecutionArchive {
    private static final Object ADMISSION_LOCK = new Object();
    static final int EVENT_BYTES = 16_384, LABEL_BYTES = 65_536, MANIFEST_HARD_BYTES = 1_048_576;
    private static final long LEGACY_RESERVATION = 1_048_576L + 3L * LABEL_BYTES + 4096;
    private static final long MAX_RESERVATION = 1_048_576L + 2L * MANIFEST_HARD_BYTES + LABEL_BYTES + 4096;
    final Path directory;
    final String buildId, instanceId, bootId = UUID.randomUUID().toString();
    final long maxRequestBytes, maxTotalBytes, maxManifestBytes, reservationBytes;
    final boolean enabled;
    final ObservationAssessmentV01 observationAssessment;
    ExecutionArchive(Path candidate, String buildId, String instanceId) {
        this(candidate, buildId, instanceId, System.getenv());
    }
    ExecutionArchive(Path candidate, String buildId, String instanceId, Map<String,String> env) {
        this.buildId = buildId; this.instanceId = instanceId;
        directory = Path.of(env.getOrDefault("FH_HISTORY_DIR",
                candidate.resolveSibling("execution-history").toString())).toAbsolutePath().normalize();
        maxRequestBytes = limit(env, "FH_HISTORY_REQUEST_BYTES", 262_144, 4096, 1_048_576);
        maxManifestBytes = limit(env, "FH_HISTORY_MANIFEST_BYTES", 262_144, 65_536, MANIFEST_HARD_BYTES);
        maxTotalBytes = limit(env, "FH_HISTORY_TOTAL_BYTES", 134_217_728, maxRequestBytes, 1_073_741_824);
        reservationBytes = maxRequestBytes + 2 * maxManifestBytes + LABEL_BYTES + 4096;
        enabled = !"false".equals(env.get("FH_HISTORY_ENABLED"));
        observationAssessment = HostObservationAdmission.check(candidate, directory, enabled, env);
        if (enabled) recover();
    }
    private void recover() {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) return;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.jsonl")).limit(1000).toList()) {
                try {
                    if (Files.isSymbolicLink(path) || Files.size(path) > maxRequestBytes) continue;
                    var lines = Files.readAllLines(path);
                    if (lines.isEmpty()) continue;
                    var header = HarnessJson.mapper().readTree(lines.getFirst());
                    boolean terminal = false;
                    for (String line : lines) try {
                        if ("terminal".equals(HarnessJson.mapper().readTree(line).path("recordType").asText())) terminal = true;
                    } catch (Exception partial) { }
                    if (terminal) continue;
                    var process = ProcessHandle.of(header.path("pid").asLong());
                    if (process.isPresent() && process.get().isAlive()) {
                        var start = process.get().info().startInstant();
                        if (start.isEmpty() || start.get().toString().equals(header.path("processStartedAt").asText())) continue;
                    }
                    String id = path.getFileName().toString().replace(".jsonl", "");
                    byte[] data = HarnessJson.mapper().writeValueAsBytes(Map.of("status", "INTERRUPTED",
                            "recoveredAt", Instant.now().toString(), "reason", "PROCESS_ABSENT_OR_REPLACED",
                            "terminationTimeKnown", false));
                    Files.write(directory.resolve(id + ".recovery.json"), data, StandardOpenOption.CREATE_NEW);
                } catch (Exception unavailable) { }
            }
        } catch (Exception unavailable) { }
    }
    private static long limit(Map<String,String> env, String key, long value, long min, long max) {
        try { return Math.min(max, Math.max(min, Long.parseLong(env.getOrDefault(key, "" + value)))); }
        catch (NumberFormatException ignored) { return value; }
    }
    private long reserved(Path file) {
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] head = stream.readNBytes(EVENT_BYTES + 1);
            int newline = 0;
            while (newline < head.length && head[newline] != '\n') newline++;
            if (newline == head.length || newline >= EVENT_BYTES) return MAX_RESERVATION;
            var value = HarnessJson.mapper().readTree(Arrays.copyOf(head, newline));
            // Old writers bounded both manifests at 64 KiB; never charge them new manifest maxima.
            if (!value.has("archiveReservationBytes") && "request".equals(value.path("recordType").asText())
                    && "finance-execution-history.v0.1".equals(value.path("schemaVersion").asText()))
                return LEGACY_RESERVATION;
            var budget = value.path("archiveReservationBytes");
            long bytes = budget.isIntegralNumber() && budget.canConvertToLong() ? budget.asLong() : 0;
            return bytes >= 4096L + 2 * 65_536 + LABEL_BYTES + 4096 && bytes <= MAX_RESERVATION
                    ? bytes : MAX_RESERVATION;
        } catch (Exception unavailable) { return MAX_RESERVATION; }
    }
    static byte[] readManifest(Path file, long maxBytes) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("MANIFEST_UNAVAILABLE");
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > maxBytes) throw new IllegalStateException("MANIFEST_TOO_LARGE");
            var buffer = ByteBuffer.allocate(Math.toIntExact(size) + 1);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            if (buffer.position() != size || channel.size() != size) throw new IllegalStateException("MANIFEST_CHANGED");
            byte[] data = Arrays.copyOf(buffer.array(), buffer.position());
            validateManifest(data);
            return data;
        }
    }
    private static void validateManifest(byte[] data) throws Exception {
        var value = HarnessJson.mapper().reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(data);
        if (value == null || !value.isObject()) throw new IllegalStateException("MANIFEST_INVALID");
    }
    static void writeImmutable(Path file, byte[] data, long maxBytes) throws Exception {
        if (data.length > maxBytes) throw new IllegalStateException("MANIFEST_TOO_LARGE");
        validateManifest(data);
        if (Files.isSymbolicLink(file.getParent())) throw new IllegalStateException("ARCHIVE_LINK_REJECTED");
        Path temporary = Files.createTempFile(file.getParent(), ".manifest-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            if (!Arrays.equals(data, readManifest(temporary, maxBytes))) throw new IllegalStateException("MANIFEST_WRITE_MISMATCH");
            // Publish complete bytes without replacing an existing file or following a target link.
            Files.createLink(file, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }
    Request begin(Path candidate, String session, String subject, long executorWaitNanos) {
        return begin(candidate, session, subject, executorWaitNanos, "UNSPECIFIED");
    }
    Request begin(Path candidate, String session, String subject, long executorWaitNanos, String requestType) {
        return new Request(candidate, session, subject, executorWaitNanos, requestType);
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static String reference(String value) { return hash(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    final class Request implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        final ObservationRun run;
        final ObservationRun.Span root;
        final Path file = directory.resolve(id + ".jsonl");
        private long bytes;
        private long appendNanos, forceNanos, projectionNanos;
        private final boolean profiling = "true".equals(System.getenv("FH_HISTORY_PROFILE"));
        private volatile boolean failed;
        private final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile String failureCode;
        private final java.util.concurrent.atomic.AtomicLong sinkRejectedEvents = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicLong listenerFailures = new java.util.concurrent.atomic.AtomicLong();
        private volatile java.util.function.Consumer<ObservationEventV01> eventListener = ignored -> {};
        void observeEvents(java.util.function.Consumer<ObservationEventV01> listener) {
            eventListener = java.util.Objects.requireNonNull(listener);
        }
        Request(Path candidate, String session, String subject, long executorWaitNanos, String requestType) {
            failed = !enabled;
            if (enabled) try {
                Files.createDirectories(directory);
                if (Files.isSymbolicLink(directory)) throw new IllegalStateException();
                try { Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
                catch (UnsupportedOperationException ignored) { }
                // Reserve a bounded worst-case request budget under a single-host file lock.
                synchronized (ADMISSION_LOCK) {
                try (var quota = FileChannel.open(directory.resolve(".quota.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = quota.lock()) {
                    long reserved = reservationBytes, used = 0;
                    try (var paths = Files.list(directory)) {
                        int requests = 0, entries = 0;
                        for (Path path : (Iterable<Path>) paths::iterator) {
                            if (++entries > 32_000) throw new IllegalStateException("ARCHIVE_QUOTA");
                            if (Files.isSymbolicLink(path)) throw new IllegalStateException("ARCHIVE_LINK_REJECTED");
                            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) used += Files.size(path);
                            if (path.getFileName().toString().endsWith(".jsonl")) {
                                if (++requests > 6000) throw new IllegalStateException("ARCHIVE_QUOTA");
                                reserved += reserved(path);
                            }
                            if (reserved > maxTotalBytes || used + reservationBytes > maxTotalBytes)
                                throw new IllegalStateException("ARCHIVE_QUOTA");
                        }
                    }
                    if (reserved > maxTotalBytes || used + reservationBytes > maxTotalBytes)
                        throw new IllegalStateException("ARCHIVE_QUOTA");
                    Files.createFile(file);
                }
                }
                try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
                catch (UnsupportedOperationException ignored) { }
                byte[] lock = readManifest(candidate.resolve("service-lock.json"), maxManifestBytes);
                var metadata = new LinkedHashMap<String,Object>();
                metadata.put("recordType", "request"); metadata.put("schemaVersion", "finance-execution-history.v0.1");
                metadata.put("requestId", id); metadata.put("buildId", buildId); metadata.put("instanceId", instanceId);
                metadata.put("processBootId", bootId); metadata.put("pid", ProcessHandle.current().pid());
                metadata.put("processStartedAt", ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH).toString());
                metadata.put("ownerRef", System.getenv().getOrDefault("FH_HISTORY_OWNER", "local-operator"));
                metadata.put("sessionRef", reference(session)); metadata.put("subjectRef", reference(subject));
                metadata.put("createdAt", Instant.now().toString()); metadata.put("serviceLockDigest", hash(lock));
                metadata.put("archiveReservationBytes", reservationBytes);
                metadata.put("observationDisplayVersion", ObservationDisplayV01.VERSION);
                metadata.put("executorWaitNanos", Math.max(0, executorWaitNanos));
                metadata.put("executorWaitCoverage", "EXECUTOR_SUBMIT_TO_WORKER_ONLY");
                metadata.put("modelDispatchCoverage", NativeRuntime.DISPATCH_COVERAGE);
                var serviceLock = HarnessJson.mapper().readTree(lock);
                metadata.put("capabilities", serviceLock.path("capabilities"));
                String agentName = serviceLock.path("interface").path("configuration").path("assistantName").asText("").trim();
                if (!agentName.isEmpty() && agentName.length() <= 100) metadata.put("agentName", agentName);
                if (requestType != null && requestType.matches("[A-Z][A-Z0-9_]{1,63}")) metadata.put("requestType", requestType);
                if (observationAssessment.status() != ObservationAssessmentV01.Status.LEGACY_UNASSESSED)
                    metadata.put("observationAssessment", observationAssessment);
                append(metadata);
                writeImmutable(directory.resolve(id + ".service-lock.json"), lock, maxManifestBytes);
            } catch (Exception unavailable) { failed = true; failureCode = "ARCHIVE_UNAVAILABLE_OR_QUOTA"; }
            run = new ObservationRun(id, bootId, instanceId, event -> {
                boolean accepted = append(event);
                if (!accepted) sinkRejectedEvents.incrementAndGet();
                try { eventListener.accept(event); } catch (RuntimeException ignored) { listenerFailures.incrementAndGet(); }
                return accepted;
            });
            root = run.start(null, "host", "request", Map.of());
        }
        private synchronized boolean append(Object event) {
            if (failed) return false;
            long started=profiling?System.nanoTime():0;
            try {
                byte[] data = (HarnessJson.mapper().writeValueAsString(event) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (data.length > EVENT_BYTES || bytes + data.length > maxRequestBytes) {
                    failed = true; failureCode = "REQUEST_EVENT_QUOTA"; return false;
                }
                try (var channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                        LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    long forceStarted=profiling?System.nanoTime():0;
                    channel.force(false);
                    if(profiling)forceNanos+=System.nanoTime()-forceStarted;
                }
                bytes += data.length; return true;
            } catch (Exception unavailable) { failed = true; failureCode = "ARCHIVE_WRITE_FAILED"; return false; }
            finally {if(profiling)appendNanos+=System.nanoTime()-started;}
        }
        private String labelDigest;
        void labels(Object labels) {
            if (failed) return;
            try {
                if (labels == null) throw new IllegalStateException("DISPLAY_UNAVAILABLE");
                byte[] data = HarnessJson.mapper().writeValueAsBytes(labels);
                writeImmutable(directory.resolve(id + ".labels.json"), data, LABEL_BYTES);
                labelDigest = hash(data);
                append(Map.of("recordType", "observation-labels", "requestId", id,
                        "state", "RECORDED", "digest", hash(data)));
            } catch (Exception unavailable) {
                append(Map.of("recordType", "observation-labels", "requestId", id, "state", "UNAVAILABLE"));
            }
        }
        synchronized void installation(Object lock) {
            if (failed) return;
            try {
                byte[] data = HarnessJson.mapper().writeValueAsBytes(lock);
                Path target = directory.resolve(id + ".runtime-lock.json");
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(readManifest(target, maxManifestBytes), data))
                        throw new IllegalStateException("RUNTIME_LOCK_CHANGED_WITHIN_REQUEST");
                    return;
                }
                writeImmutable(target, data, maxManifestBytes);
                if (labelDigest != null) append(Map.of("recordType", "installation-display", "requestId", id,
                        "displayDigest", labelDigest, "installationDigest", hash(data)));
                try (var span = run.start(root.id(), "host", "installation-lock", Map.of("runtimeLockDigest", hash(data)))) {
                    span.finish(Outcome.COMPLETED, Map.of());
                }
            } catch (Exception unavailable) { failed = true; failureCode = "VERSION_RECORD_FAILED"; }
        }
        void modelContext(ModelConnections.Connection model, String clientRunId) {
            append(Map.of("recordType", "model-context", "modelRef", model.ref(), "modelName", model.name(),
                    "configVersion", model.version(), "mode", System.getenv().getOrDefault("FH_MODEL_MODE", "LIVE"),
                    "clientRunRef", reference(clientRunId)));
        }
        Path snapshotDirectory() { return directory.resolve("model-snapshots"); }
        boolean snapshotMetadata(Map<String,Object> metadata) { return append(metadata); }
        synchronized void snapshotProjection(long nanos) {if(profiling)projectionNanos+=nanos;}
        void finish(Outcome outcome) {
            if (!finished.compareAndSet(false, true)) return;
            // Do not hold the archive monitor while emitting: callbacks acquire run then archive.
            root.finish(outcome, Map.of());
            if(profiling)append(Map.of("recordType","diagnostic-timing","appendNanos",appendNanos,
                "forceNanos",forceNanos,"projectionNanos",projectionNanos,
                "scope","THROUGH_ROOT_END_EXCLUDES_THIS_RECORD_AND_TERMINAL_ASYNC_BODY_IO"));
            append(Map.of("recordType", "terminal", "status", outcome.name(), "at", Instant.now().toString(),
                    "droppedEvents", run.droppedEvents(), "sinkRejectedEvents", sinkRejectedEvents.get(),
                    "listenerFailures", listenerFailures.get(), "complete", !failed && run.droppedEvents() == 0));
        }
        Map<String,Object> receipt() {
            return Map.of("requestId", id, "archiveStatus", enabled ? failed ? "DEGRADED" : "RECORDING" : "DISABLED",
                    "droppedEvents", run.droppedEvents(), "sinkRejectedEvents", sinkRejectedEvents.get(),
                    "listenerFailures", listenerFailures.get(), "failureCode", failureCode == null ? "NONE" : failureCode);
        }
        @Override public void close() { finish(Outcome.INCOMPLETE); }
    }
}
