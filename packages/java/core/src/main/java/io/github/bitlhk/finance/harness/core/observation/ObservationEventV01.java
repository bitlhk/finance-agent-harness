package io.github.bitlhk.finance.harness.core.observation;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Candidate metadata-only telemetry; not an authorization grant or financial audit receipt. */
public record ObservationEventV01(
        String schemaVersion, String requestId, String processBootId, String instanceId,
        long sequence, String spanId, String parentSpanId, String component, String operation,
        Phase phase, Outcome outcome, Dispatch dispatch, Instant at, long elapsedNanos,
        Long durationNanos, Map<String, String> links) {
    public static final String VERSION = "finance-observation.v0.1";
    private static final Set<String> LINKS = Set.of("sdkTraceId", "toolCallId", "harnessRequestId",
            "harnessTraceId", "capabilityId", "providerId", "route", "method", "httpStatus",
            "businessStatus", "attempt", "runtimeLockDigest", "exceptionType");
    public enum Phase { START, END, SIGNAL }
    public enum Outcome { PENDING, RETURNED, COMPLETED, ERROR, DENIED, SKIPPED, INCOMPLETE, CANCEL_REQUESTED, CANCELLED }
    public enum Dispatch { NOT_OBSERVED, NOT_SENT, SENT }

    public ObservationEventV01 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Observation version");
        for (String id : new String[]{requestId, processBootId, instanceId, spanId, component, operation}) {
            if (id == null || !id.matches("[A-Za-z0-9_.:/{}-]{1,160}"))
                throw new IllegalArgumentException("Invalid observation identifier");
        }
        if (parentSpanId != null && !parentSpanId.matches("[A-Za-z0-9_.:/{}-]{1,160}"))
            throw new IllegalArgumentException("Invalid parent observation identifier");
        if (sequence < 1 || elapsedNanos < 0 || (durationNanos != null && durationNanos < 0)
                || at == null || phase == null || outcome == null || dispatch == null)
            throw new IllegalArgumentException("Invalid observation event");
        links = Map.copyOf(links);
        for (var entry : links.entrySet()) {
            if (!LINKS.contains(entry.getKey()) || !entry.getValue().matches("[A-Za-z0-9_.:/{}-]{1,200}"))
                throw new IllegalArgumentException("Only bounded observation metadata is permitted");
        }
    }
}
