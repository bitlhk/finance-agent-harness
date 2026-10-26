package io.github.bitlhk.finance.harness.integrations.otel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.extensions.tracerotel.OtelAgentHandler;
import com.openjiuwen.extensions.tracerotel.OtelRail;
import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional, metadata-only projection of native SDK spans.
 *
 * No SDK callback calls a Host sink or exporter. Hosts poll bounded immutable
 * records and own authorization, persistence and shutdown. Context must be
 * explicitly propagated; missing context is never inferred from span names.
 */
public final class NativeObservationBridge implements SpanProcessor, AutoCloseable {
    public static final int MAX_RECORD_BYTES = 16 * 1024;
    public static final int MAX_REQUEST_BYTES = 256 * 1024;
    public static final int MAX_QUEUE = 256;
    private static final int COVERAGE_RESERVE = 4096;
    private static final int MAX_BINDINGS = 4096;
    private static final int MAX_TOMBSTONES = 256;
    private static final long SAFE_INTEGER = 9007199254740991L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ContextKey<Request> REQUEST = ContextKey.named("finance-native-request-v1");
    private static final ContextKey<ExplicitLinks> LINKS = ContextKey.named("finance-native-links-v1");
    private static final Map<String, Object> SOURCE = Map.of(
            "framework", "openjiuwen-java", "frameworkVersion", "0.1.16",
            "integrationId", "finance-harness-openjiuwen-java-otel", "integrationVersion", "0.1.0-SNAPSHOT");
    private final Object bindingLock = new Object();
    private final Map<String, Binding> bindings = new HashMap<>();
    private final Map<Request, Boolean> requests = new WeakHashMap<>();
    private final LinkedHashMap<String, WeakReference<Request>> tombstones = new LinkedHashMap<>();
    private final AtomicLong unbound = new AtomicLong();
    private final SdkTracerProvider provider;
    private volatile boolean closed;
    private final AtomicBoolean providerClosed = new AtomicBoolean();

    public NativeObservationBridge() {
        provider = SdkTracerProvider.builder().addSpanProcessor(this).build();
    }

    /** SDK-owned handler, with no SDK-created console/OTLP exporter or global provider. */
    public OtelAgentHandler sdkHandler() {
        var config = OtelTracerConfig.builder().isRedactionEnabled(true)
                .shouldRedactPrompts(true).shouldRedactCompletions(true).build();
        return new OtelAgentHandler(provider.get("finance-native-observation-v1"), config);
    }

    /** Host must separately disable the SDK's raw TRACE stream before session creation. */
    public OtelRail sdkRail() { return new OtelRail(); }

    public Request open(String requestId, String instanceId, String processBootId,
                        String runtimeLockDigest, String mode, boolean selected) {
        synchronized (bindingLock) {
            if (closed) throw new IllegalStateException("NATIVE_BRIDGE_CLOSED");
            var request = new Request(requestId, instanceId, processBootId, runtimeLockDigest, mode, selected);
            requests.put(request, Boolean.TRUE);
            return request;
        }
    }

    /** Observed unbound spans are instance diagnostics, not attributed to an arbitrary request. */
    public long unboundSpans() { return unbound.get(); }

    @Override public boolean isStartRequired() { return true; }
    @Override public boolean isEndRequired() { return true; }

    @Override public void onStart(Context parentContext, ReadWriteSpan span) {
        Request request = parentContext.get(REQUEST);
        if (request == null) { unbound.incrementAndGet(); return; }
        if (!request.selected) return;
        try {
            String category = category(span.getName());
            if (category == null) { request.drop(); return; }
            SpanData data = span.toSpanData();
            if (data.getStartEpochNanos() < 0) { request.drop(); return; }
            ExplicitLinks links = parentContext.get(LINKS);
            Binding binding = new Binding(request, data.getTraceId(), data.getSpanId(),
                    data.getParentSpanId(), category, data.getStartEpochNanos(),
                    links != null && links.owner == request ? links.values : Map.of());
            String key = binding.key();
            synchronized (bindingLock) {
                if (closed || request.finished || bindings.size() >= MAX_BINDINGS) {
                    request.drop(); return;
                }
                if (bindings.putIfAbsent(key, binding) != null) { request.drop(); return; }
            }
            if (!request.span(binding, "START", "PENDING", null)) {
                synchronized (bindingLock) { bindings.remove(key, binding); }
            }
        } catch (RuntimeException invalid) {
            request.drop(); // Never copy exception text or change an execution result.
        }
    }

    @Override public void onEnd(ReadableSpan span) {
        String key = span.getSpanContext().getTraceId() + ":" + span.getSpanContext().getSpanId();
        Binding binding;
        Request late = null;
        synchronized (bindingLock) {
            binding = bindings.remove(key);
            if (binding == null) {
                WeakReference<Request> reference = tombstones.remove(key);
                if (reference != null) late = reference.get();
            }
        }
        if (late != null) { late.late(); return; }
        if (binding == null) return;
        try {
            SpanData data = span.toSpanData();
            String status = data.getStatus().getStatusCode() == StatusCode.ERROR ? "ERROR" : "RETURNED";
            binding.owner.span(binding, "END", status, data);
        } catch (RuntimeException invalid) { binding.owner.drop(); }
    }

    private static String category(String name) {
        if (name.startsWith("llm.")) return "MODEL";
        if (name.startsWith("tool.")) return "TOOL";
        if (name.startsWith("chain.")) return "RUN";
        if (name.startsWith("workflow.")) return "WORKFLOW";
        return null; // Do not persist arbitrary names.
    }

    private static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("NATIVE_ID_INVALID");
        return value;
    }

    private record ExplicitLinks(Request owner, Map<String, Object> values) {}
    private record Binding(Request owner, String traceId, String spanId, String parentId,
                           String category, long start, Map<String, Object> links) {
        String key() { return traceId + ":" + spanId; }
    }

    /** One Host-owned request; data objects contain no raw SDK span or attributes. */
    public final class Request implements AutoCloseable {
        private final Map<String, Object> identity;
        private final String mode;
        private final boolean selected;
        private final ArrayDeque<Map<String, Object>> queue = new ArrayDeque<>();
        private volatile boolean finished;
        private long sequence, bytes, dropped, late, budgetRefused;

        private Request(String requestId, String instanceId, String bootId, String digest,
                        String mode, boolean selected) {
            if (!List.of("off", "sample", "full").contains(mode)
                    || ("off".equals(mode) && selected) || ("full".equals(mode) && !selected))
                throw new IllegalArgumentException("NATIVE_SAMPLING_INVALID");
            if (digest == null || !digest.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("NATIVE_LOCK_DIGEST_INVALID");
            identity = Map.of("requestId", id(requestId), "instanceId", id(instanceId),
                    "processBootId", id(bootId), "runtimeLockDigest", digest);
            this.mode = mode;
            this.selected = selected;
        }

        /** Bind in the actual execution path; propagate this Context explicitly across threads. */
        public Scope bind() {
            if (finished || NativeObservationBridge.this.closed)
                throw new IllegalStateException("NATIVE_REQUEST_FINISHED");
            // A Host request is an explicit isolation boundary. Never inherit a
            // stale SDK span from a cancelled/unfinished previous request.
            return Context.root().with(REQUEST, this).makeCurrent();
        }

        /** Only the trusted Host can supply explicit financial/SDK correspondence. */
        public Scope bindLinks(String financialSpanId, String toolCallId) {
            if (Context.current().get(REQUEST) != this || finished)
                throw new IllegalStateException("NATIVE_SCOPE_REQUIRED");
            var values = new LinkedHashMap<String, Object>();
            values.put("basis", "HOST_EXPLICIT_BINDING");
            if (financialSpanId != null) values.put("financialSpanId", id(financialSpanId));
            if (toolCallId != null) values.put("toolCallId", id(toolCallId));
            if (values.size() == 1) throw new IllegalArgumentException("NATIVE_LINK_REQUIRED");
            return Context.current().with(LINKS, new ExplicitLinks(this, Map.copyOf(values))).makeCurrent();
        }

        /** Non-blocking: the Host is responsible for a separately bounded authorized sink. */
        public synchronized Map<String, Object> poll() { return queue.poll(); }

        private synchronized Map<String, Object> base(String kind) {
            var record = new LinkedHashMap<String, Object>(identity);
            record.put("schemaVersion", "finance.native-observation.v1");
            record.put("kind", kind); record.put("sequence", sequence + 1);
            record.put("source", SOURCE); record.put("mode", mode); record.put("sampled", selected);
            // A functioning API probe is NOT a qualified collector.
            record.put("supportState", "UNVERIFIED");
            record.put("droppedRecords", dropped); record.put("lateRecords", late);
            record.put("budgetRefusedRecords", budgetRefused);
            return record;
        }

        private synchronized boolean span(Binding b, String phase, String status, SpanData end) {
            if (finished) { late(); return false; }
            if (!selected) return false;
            var record = base("SDK_SPAN");
            record.put("sdkTraceId", b.traceId); record.put("sdkSpanId", b.spanId);
            if (!"0000000000000000".equals(b.parentId)) record.put("parentSdkSpanId", b.parentId);
            record.put("category", b.category); record.put("phase", phase); record.put("status", status);
            record.put("clockSource", "SDK_OTEL_EPOCH_NANOS");
            record.put("startEpochNanos", Long.toString(b.start));
            if (!b.links.isEmpty()) record.put("links", b.links);
            if (end != null) {
                long stop = end.getEndEpochNanos();
                if (b.start < 0 || stop < b.start || stop - b.start > SAFE_INTEGER) {
                    dropped++; return false;
                }
                record.put("endEpochNanos", Long.toString(stop));
                record.put("durationNanos", stop - b.start);
                var usage = new LinkedHashMap<String, Object>();
                usage(end, usage, "gen_ai.usage.prompt_tokens", "inputTokens");
                usage(end, usage, "gen_ai.usage.completion_tokens", "outputTokens");
                if (!usage.isEmpty()) record.put("usage", Map.copyOf(usage));
            }
            return enqueue(record, false);
        }

        private void usage(SpanData span, Map<String, Object> target, String attribute, String field) {
            Long value = span.getAttributes().get(AttributeKey.longKey(attribute));
            if (value != null && value >= 0 && value <= SAFE_INTEGER) target.put(field, value);
        }

        private synchronized boolean enqueue(Map<String, Object> record, boolean coverage) {
            int size;
            try { size = JSON.writeValueAsBytes(record).length; }
            catch (JsonProcessingException invalid) { dropped++; return false; }
            int limit = coverage ? MAX_REQUEST_BYTES : MAX_REQUEST_BYTES - COVERAGE_RESERVE;
            int capacity = coverage ? MAX_QUEUE : MAX_QUEUE - 1;
            if (size > MAX_RECORD_BYTES || size > limit - bytes) {
                budgetRefused++; dropped++; return false;
            }
            if (queue.size() >= capacity) { dropped++; return false; }
            queue.add(Map.copyOf(record)); sequence++; bytes += size;
            return true;
        }

        private synchronized void drop() { dropped++; }
        private synchronized void late() {
            late++;
            if (finished) enqueue(base("COVERAGE"), true);
        }

        /** Fresh immutable counters, not a statement that unobserved late work did not exist. */
        public synchronized Map<String, Object> coverage() { return Map.copyOf(base("COVERAGE")); }

        @Override public void close() {
            synchronized (this) {
                if (finished) return;
                finished = true;
            }
            int unfinished = 0;
            synchronized (bindingLock) {
                requests.remove(this);
                var iterator = bindings.entrySet().iterator();
                while (iterator.hasNext()) {
                    var value = iterator.next();
                    if (value.getValue().owner != this) continue;
                    unfinished++;
                    tombstones.put(value.getKey(), new WeakReference<>(this));
                    iterator.remove();
                }
                while (tombstones.size() > MAX_TOMBSTONES)
                    tombstones.remove(tombstones.keySet().iterator().next());
            }
            synchronized (this) {
                dropped += unfinished;
                enqueue(base("COVERAGE"), true);
            }
        }
    }

    @Override public CompletableResultCode forceFlush() {
        // No worker/exporter. This does NOT acknowledge Host disk durability.
        return CompletableResultCode.ofSuccess();
    }

    @Override public CompletableResultCode shutdown() {
        finishRequests();
        return CompletableResultCode.ofSuccess();
    }

    private void finishRequests() {
        closed = true;
        List<Request> pending;
        synchronized (bindingLock) {
            pending = List.copyOf(requests.keySet());
        }
        pending.forEach(Request::close);
        synchronized (bindingLock) { bindings.clear(); tombstones.clear(); }
    }

    @Override public void close() {
        if (!providerClosed.compareAndSet(false, true)) return;
        finishRequests();
        provider.close();
    }
}
