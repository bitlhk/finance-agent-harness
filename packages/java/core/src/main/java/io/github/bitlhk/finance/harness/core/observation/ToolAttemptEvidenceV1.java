package io.github.bitlhk.finance.harness.core.observation;

import java.time.Instant;
import java.util.*;

/** Bounded metadata only; no arguments, results, prompts or hidden reasoning. */
public final class ToolAttemptEvidenceV1 {
    private final String requestId;
    private final String startedAt = Instant.now().toString();
    private final List<Map<String, String>> attempts = new ArrayList<>();
    private final Set<String> gaps = new LinkedHashSet<>();
    private int responses;
    private String endedAt;
    public ToolAttemptEvidenceV1(String requestId) { this.requestId = Objects.requireNonNull(requestId); }
    public synchronized void responseObserved() { responses++; }
    public synchronized void gap(String reason) { gaps.add(reason); }
    public synchronized void attempt(String id, String name, String phase) {
        if (endedAt != null) gaps.add("LATE_ATTEMPT");
        if (id == null || name == null || id.length() > 200 || name.length() > 200) {
            gaps.add("INVALID_ATTEMPT_METADATA"); return;
        }
        if (attempts.size() >= 512) { gaps.add("ATTEMPT_LIMIT"); return; }
        attempts.add(Map.of("toolCallId", id, "name", name, "phase", phase, "at", Instant.now().toString()));
    }
    public synchronized Map<String, Object> finish(boolean normal) {
        if (endedAt == null) endedAt = Instant.now().toString();
        if (!normal) gaps.add("INVOCATION_NOT_COMPLETED");
        if (responses == 0) gaps.add("NO_PARSED_MODEL_RESPONSE");
        return Map.of("schemaVersion", "finance.tool-attempt-evidence.v1", "requestId", requestId,
                "coverage", "NATIVE_PARSED_SELECTION_AND_DISPATCH", "startedAt", startedAt, "endedAt", endedAt,
                "status", gaps.isEmpty() ? "COMPLETE" : "PARTIAL", "gaps", List.copyOf(gaps),
                "modelResponses", responses, "attempts", List.copyOf(attempts));
    }
}
