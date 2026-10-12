package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.util.Map;

/** Host-owned deadlines. Only explicit user actions touch activity, never polling/model output. */
final class TerminalSession {
    private final Clock clock;
    private final long created, idleMillis, warningMillis, absoluteMillis;
    private long active;
    private String closed, runId, lastRunId, lastRunStatus = "IDLE";
    private boolean cancelled;
    private final java.util.Set<String> usedRuns = new java.util.HashSet<>();

    TerminalSession(JsonNode config, Clock clock) {
        this.clock = clock; created = active = clock.millis();
        JsonNode policy = config.path("sessionPolicy");
        idleMillis = policy.path("idleSeconds").asLong(900) * 1000;
        warningMillis = policy.path("warningSeconds").asLong(30) * 1000;
        absoluteMillis = policy.path("absoluteSeconds").asLong(3600) * 1000;
        if (idleMillis < 1000 || warningMillis < 1000 || absoluteMillis < idleMillis || absoluteMillis > 3600000)
            throw new IllegalArgumentException("TERMINAL_POLICY_INVALID");
    }
    synchronized void check() {
        if (closed == null && (clock.millis() >= created + absoluteMillis || clock.millis() >= active + idleMillis + warningMillis))
            closed = "SESSION_EXPIRED";
        if (closed != null) throw new SecurityException(closed);
    }
    synchronized void touch() { check(); active = clock.millis(); }
    synchronized void begin(String id) {
        touch();
        if (runId != null) throw new IllegalArgumentException("RUN_ALREADY_ACTIVE");
        if (!usedRuns.add(id)) throw new IllegalArgumentException("RUN_ALREADY_FINISHED");
        runId = id; cancelled = false; lastRunStatus = "RUNNING";
    }
    synchronized void processing() {
        check();
        if (cancelled) throw new SecurityException("RUN_CANCELLED");
    }
    synchronized Map<String, Object> cancel(String id) {
        check();
        if (runId == null || !runId.equals(id)) return Map.of("status", "NOT_RUNNING", "runId", id);
        cancelled = true;
        return Map.of("status", "CANCEL_REQUESTED", "runId", id, "sourceRequestMayHaveCompleted", true);
    }
    synchronized void finish(String status) {
        lastRunId = runId; runId = null; lastRunStatus = cancelled ? "CANCELLED" : status;
    }
    synchronized void complete(Runnable commit) {
        processing();
        commit.run();
        finish("COMPLETED");
    }
    synchronized void close(String reason) { closed = reason; cancelled = true; }
    synchronized boolean cancelled() { return cancelled && closed == null; }
    synchronized Map<String, Object> status() {
        check();
        long expires = Math.min(created + absoluteMillis, active + idleMillis + warningMillis);
        return Map.of("status", clock.millis() >= active + idleMillis ? "IDLE_WARNING" : "AUTHORIZED",
                "remainingSeconds", Math.max(0, (expires - clock.millis() + 999) / 1000),
                "runStatus", lastRunStatus, "runId", runId == null ? (lastRunId == null ? "" : lastRunId) : runId);
    }
}
