package io.github.bitlhk.finance.harness.taskinteraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** Optional domain projection. Original business state and Core TASK state retain their owners. */
public record TaskInteractionV1(String taskRef, String sourceObjectRef, String sourceCommit,
        String sourceRelease, String version, String materialVersion, String originalState,
        String displayState, List<String> allowedActions, List<String> inputRequired,
        JsonNode rawResult, List<String> unresolvedRequestIds) {
    public static final String SCHEMA_VERSION = "finance-task-snapshot.v1";
    public static final String SOURCE_COMMIT = "0e158eee225c698774aa75671c944624cd36550b";
    public static final Set<String> ACTIONS = Set.of("claim", "supplement", "submit", "approve", "reject", "execute", "cancel");

    public TaskInteractionV1 {
        TaskInteractionMapper.id(taskRef); TaskInteractionMapper.id(sourceObjectRef);
        if (!SOURCE_COMMIT.equals(sourceCommit)) TaskInteractionMapper.invalid();
        for (var value : List.of(sourceRelease, version, materialVersion)) TaskInteractionMapper.hash(value);
        allowedActions = List.copyOf(allowedActions); inputRequired = List.copyOf(inputRequired);
        unresolvedRequestIds = List.copyOf(unresolvedRequestIds);
        for (String value : unresolvedRequestIds) TaskInteractionMapper.id(value);
        if (allowedActions.stream().anyMatch(a -> !ACTIONS.contains(a))
                || inputRequired.stream().anyMatch(a -> !Set.of("attachmentRef", "commissionFee").contains(a))
                || allowedActions.stream().distinct().count() != allowedActions.size()
                || inputRequired.stream().distinct().count() != inputRequired.size()
                || unresolvedRequestIds.stream().distinct().count() != unresolvedRequestIds.size()) TaskInteractionMapper.invalid();
        String expected = switch (originalState) {
            case "pending", "pending_submit" -> "WAITING_INPUT";
            case "pending_review" -> "WAITING_REVIEW";
            case "approved" -> "WAITING_CONFIRMATION";
            case "executed" -> "SUCCEEDED";
            case "rejected" -> "REJECTED";
            case "cancelled" -> "CANCELLED";
            default -> throw new IllegalArgumentException("TASK_INTERACTION_INVALID");
        };
        if (unresolvedRequestIds.isEmpty() ? !expected.equals(displayState)
                : !"UNKNOWN".equals(displayState) || !allowedActions.isEmpty()) TaskInteractionMapper.invalid();
        if (rawResult == null || !rawResult.isObject() || !sourceObjectRef.equals(rawResult.path("id").asText())
                || !originalState.equals(rawResult.path("status").asText())) TaskInteractionMapper.invalid();
        rawResult = rawResult.deepCopy();
    }
    @Override public JsonNode rawResult() { return rawResult.deepCopy(); }
}
