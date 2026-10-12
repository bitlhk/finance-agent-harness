package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.assembly.ModelSnapshotReceiver;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Optional single-host body store, distinct from timing evidence. Only sanitized bytes enter the queue. */
final class ModelSnapshots implements ModelSnapshotReceiver {
    private static final ThreadPoolExecutor WRITER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> { var t = new Thread(task, "snapshot-writer"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final ExecutionArchive.Request request;
    private final Supplier<String> modelSpan;
    private final List<String> secrets;
    private final Path directory;
    private final String owner;
    private final boolean enabled;
    private final java.util.function.Consumer<Runnable> writer;
    static boolean allowed() {
        return "true".equals(System.getenv("FH_SNAPSHOTS_ENABLED"))
                && Arrays.asList(System.getenv().getOrDefault("FH_SNAPSHOT_CAPTURE_OWNERS", "").split(","))
                .contains(System.getenv().getOrDefault("FH_HISTORY_OWNER", "local-operator"));
    }
    static boolean flush() {
        return drain(WRITER,allowed()?SnapshotStorage.Policy.from(System.getenv()).flushMillis():2000);
    }
    static boolean drain(java.util.concurrent.ExecutorService writer,int millis) {
        writer.shutdown();
        try { return writer.awaitTermination(millis,TimeUnit.MILLISECONDS); }
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    ModelSnapshots(ExecutionArchive.Request request, Supplier<String> modelSpan, List<String> secrets) {
        this(request, modelSpan, secrets, allowed(), WRITER::execute);
    }
    ModelSnapshots(ExecutionArchive.Request request, Supplier<String> modelSpan, List<String> secrets,
                   boolean enabled, java.util.function.Consumer<Runnable> writer) {
        this.request = request; this.modelSpan = modelSpan; this.secrets = List.copyOf(secrets);
        directory = request.snapshotDirectory();
        owner = System.getenv().getOrDefault("FH_HISTORY_OWNER", "local-operator");
        this.enabled = enabled; this.writer = writer;
    }
    private String clean(String text) {
        String value = text;
        for (String secret : secrets) if (secret != null && !secret.isEmpty()) value = value.replace(secret, "[REDACTED]");
        return value.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~-]+", "$1[REDACTED]")
                .replaceAll("(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?<![0-9])[0-9]{11,19}(?![0-9])", "[REDACTED_NUMBER]");
    }
    @Override public void capture(String call, String boundary, String state, Map<String,Object> value) {
        capture(call,boundary,state,value,Map.of());
    }
    void capturePython(String call,String boundary,String state,Map<String,Object> value,long projectionNanos) {
        capture(call,boundary,state,value,Map.of("captureRuntime","openjiuwen-python-0.1.18",
                "nativeCallId",call.replace("-",""),"pythonProjectionNanos",projectionNanos,
                "clockScope","PYTHON_PROJECTION_DURATION_NOT_JAVA_TIMESTAMP"));
    }
    private void capture(String call, String boundary, String state, Map<String,Object> value,Map<String,Object> context) {
        if (!enabled) return;
        String span = modelSpan.get();
        if (span == null) return;
        String part = boundary.equals("CLIENT_REQUEST_PREPARED") ? "request" : "response";
        var metadata = new LinkedHashMap<String,Object>();
        metadata.put("recordType", "model-snapshot"); metadata.put("requestId", request.id);
        metadata.put("callId", call); metadata.put("spanId", span); metadata.put("part", part);
        metadata.put("boundary", boundary); metadata.put("dispatch", "NOT_OBSERVED");
        metadata.put("state", state); metadata.put("at", Instant.now().toString());
        metadata.put("filter", "STRUCTURAL_ALLOWLIST_AND_REDACTION_V02");
        metadata.putAll(context);
        try {
            long projectionStarted=System.nanoTime();
            var projection=new SnapshotContentFilter(this::clean);
            if (!context.isEmpty() && state.equals("TRUNCATED")) projection.note("PYTHON_PROJECTION_LIMIT","",-1,0);
            if (!context.isEmpty() && state.equals("FILTERED")) projection.note("PYTHON_PREFILTERED","",-1,0);
            String content = HarnessJson.mapper().writeValueAsString(projection.filter(value, part.equals("request")));
            byte[] original = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (original.length > 48_000) {
                content = "[BODY_OMITTED_SIZE_LIMIT]"; projection.note("BYTE_LIMIT","",original.length,0);
            }
            if(state.equals("CAPTURED"))metadata.put("state",projection.truncated()?"TRUNCATED":
                    projection.changes().isEmpty()?"CAPTURED":"FILTERED");
            metadata.put("changes",projection.changes());metadata.put("changesOmitted",projection.omittedChanges());
            request.snapshotProjection(System.nanoTime()-projectionStarted);
            var policy=SnapshotStorage.Policy.from(System.getenv());
            metadata.put("storagePolicy",Map.of("maxFiles",policy.files(),"maxBytes",policy.bytes(),
                "retentionSeconds",policy.retentionSeconds(),"flushMillis",policy.flushMillis()));
            var body = new LinkedHashMap<String,Object>(metadata);
            body.put("ownerRef", owner); body.put("body", content);
            byte[] frozen = HarnessJson.mapper().writeValueAsBytes(body);
            metadata.put("digest", ExecutionArchive.hash(frozen));
            String filename = request.id + "." + call + "." + part + ".json";
            // The metadata reference is durable before scheduling; absent body is explicitly missing.
            metadata.put("storage", "QUEUED");
            if (!request.snapshotMetadata(metadata)) return;
            writer.accept(() -> {
                var status = new LinkedHashMap<String,Object>();
                status.put("recordType", "model-snapshot-storage");
                status.put("requestId", request.id); status.put("callId", call);
                status.put("part", part); status.put("spanId", span);
                status.put("digest", ExecutionArchive.hash(frozen));
                try {
                    new SnapshotStorage(directory,SnapshotStorage.Policy.from(System.getenv()),java.time.Clock.systemUTC()).put(filename,frozen);
                    status.put("state", "WRITTEN");
                } catch (Exception unavailable) {
                    status.put("state", "WRITE_FAILED");
                    status.put("reason", "SNAPSHOT_QUOTA".equals(unavailable.getMessage())
                            ? "BODY_STORAGE_QUOTA" : "BODY_WRITE_FAILED");
                }
                status.put("at", Instant.now().toString());
                // This storage fact may arrive after request completion; it is not a business event.
                request.snapshotMetadata(status);
            });
        } catch (RuntimeException | java.io.IOException unavailable) {
            metadata.put("storage", "UNAVAILABLE");
            metadata.put("storageReason", unavailable instanceof RejectedExecutionException
                    ? "BODY_QUEUE_REJECTED" : "BODY_PREPARATION_FAILED");
            metadata.remove("digest"); request.snapshotMetadata(metadata);
        }
    }
}
