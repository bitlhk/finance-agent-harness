package io.github.bitlhk.finance.harness.core.observation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** Explicit request scope. Hosts propagate this object; no implicit cross-thread identity. */
public final class ObservationRun {
    private final String requestId, processBootId, instanceId;
    private final ObservationSink sink;
    private final long started = System.nanoTime();
    private final AtomicLong dropped = new AtomicLong();
    private long sequence;
    private final ThreadLocal<String> parentOnThread = new ThreadLocal<>();

    public ObservationRun(String requestId, String processBootId, String instanceId, ObservationSink sink) {
        this.requestId = requestId;
        this.processBootId = processBootId;
        this.instanceId = instanceId;
        this.sink = java.util.Objects.requireNonNull(sink);
    }

    public Span start(String parent, String component, String operation, Map<String, String> links) {
        return new Span(parent == null ? parentOnThread.get() : parent, component, operation, links);
    }
    /** Explicit same-thread nesting only. Async adapters must propagate their own parent scope. */
    public ParentScope bindParent(String parent) { return new ParentScope(parent); }
    public final class ParentScope implements AutoCloseable {
        private final String previous = parentOnThread.get();
        private final Thread owner = Thread.currentThread();
        private ParentScope(String parent) { parentOnThread.set(parent); }
        @Override public void close() {
            if (Thread.currentThread() != owner) return;
            if (previous == null) parentOnThread.remove(); else parentOnThread.set(previous);
        }
    }
    public long droppedEvents() { return dropped.get(); }
    public String requestId() { return requestId; }

    private synchronized void emit(Span span, Phase phase, Outcome outcome, Dispatch dispatch,
                                    Long duration, Map<String, String> links) {
        try {
            var event = new ObservationEventV01(VERSION, requestId, processBootId, instanceId,
                    ++sequence, span.id, span.parent, span.component, span.operation, phase, outcome,
                    dispatch, Instant.now(), Math.max(0, System.nanoTime() - started), duration, links);
            if (!sink.append(event)) dropped.incrementAndGet();
        } catch (RuntimeException ignored) {
            // Telemetry failures cannot rewrite the outcome of a financial operation.
            dropped.incrementAndGet();
        }
    }

    public final class Span implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final String parent, component, operation;
        private final Map<String, String> links;
        private final long began = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile Dispatch dispatch = Dispatch.NOT_OBSERVED;
        private Span(String parent, String component, String operation, Map<String, String> links) {
            this.parent = parent; this.component = component; this.operation = operation;
            this.links = Map.copyOf(links);
            emit(this, Phase.START, Outcome.PENDING, dispatch, null, this.links);
        }
        public String id() { return id; }
        public void dispatched() {
            dispatch = Dispatch.SENT;
            emit(this, Phase.SIGNAL, Outcome.PENDING, dispatch, null, links);
        }
        public void notSent() { dispatch = Dispatch.NOT_SENT; }
        public void finish(Outcome outcome, Map<String, String> extra) {
            if (!ended.compareAndSet(false, true)) return;
            var all = new java.util.LinkedHashMap<>(links); all.putAll(extra);
            emit(this, Phase.END, outcome, dispatch, Math.max(0, System.nanoTime() - began), all);
        }
        @Override public void close() { finish(Outcome.INCOMPLETE, Map.of()); }
    }
}
