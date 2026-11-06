package io.github.bitlhk.finance.harness.taskinteraction;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Validation only: no business database, scheduler, approval store or duplicate task journal. */
public final class TaskInteractionActions {
    private TaskInteractionActions() { }
    public record Action(String taskRef, String requestId, String expectedVersion, String materialVersion,
            String action, JsonNode parameters, boolean executionConfirmed) {
        public Action {
            TaskInteractionMapper.id(taskRef); TaskInteractionMapper.id(requestId);
            TaskInteractionMapper.hash(expectedVersion); TaskInteractionMapper.hash(materialVersion);
            role(action);
            if (parameters==null || !parameters.isObject()) TaskInteractionMapper.invalid();
            Set<String> allowed = action.equals("supplement") ? Set.of("commissionFee", "attachmentRef")
                : action.equals("reject") ? Set.of("reason") : Set.of();
            parameters.fieldNames().forEachRemaining(k -> {if (!allowed.contains(k)) TaskInteractionMapper.invalid();});
            if (parameters.has("attachmentRef")) TaskInteractionMapper.id(TaskInteractionMapper.text(parameters,"attachmentRef"));
            if (parameters.has("commissionFee") && (!parameters.get("commissionFee").isNumber()
                    || !Double.isFinite(parameters.get("commissionFee").asDouble()) || parameters.get("commissionFee").decimalValue().signum()<0)) TaskInteractionMapper.invalid();
            if (parameters.has("reason") && TaskInteractionMapper.text(parameters,"reason").length()>1000) TaskInteractionMapper.invalid();
            if (action.equals("execute") && !executionConfirmed) throw new IllegalArgumentException("TASK_EXECUTION_CONFIRMATION_REQUIRED");
            parameters=parameters.deepCopy();
        }
        @Override public JsonNode parameters(){return parameters.deepCopy();}
    }
    public static Action parse(JsonNode n) {
        TaskInteractionMapper.fields(n,Set.of("schemaVersion","taskRef","requestId","expectedVersion","materialVersion","action","parameters","executionConfirmed"));
        if (!"finance-task-action.v1".equals(TaskInteractionMapper.text(n,"schemaVersion")) || !n.path("executionConfirmed").isBoolean()) TaskInteractionMapper.invalid();
        return new Action(TaskInteractionMapper.text(n,"taskRef"),TaskInteractionMapper.text(n,"requestId"),
            TaskInteractionMapper.text(n,"expectedVersion"),TaskInteractionMapper.text(n,"materialVersion"),
            TaskInteractionMapper.text(n,"action"),n.get("parameters"),n.get("executionConfirmed").booleanValue());
    }
    public static String role(String action) {
        if (action==null) throw new IllegalArgumentException("TASK_ACTION_UNSUPPORTED");
        return switch(action) {
            case "claim","supplement","submit","cancel" -> "operator";
            case "approve","reject" -> "reviewer";
            case "execute" -> "executor";
            default -> throw new IllegalArgumentException("TASK_ACTION_UNSUPPORTED");
        };
    }
    public static void validate(TaskInteractionV1 snapshot, Action action) {
        if (!snapshot.taskRef().equals(action.taskRef()) || !snapshot.version().equals(action.expectedVersion())
                || !snapshot.materialVersion().equals(action.materialVersion())) throw new IllegalArgumentException("TASK_STALE_VERSION");
        if (!snapshot.unresolvedRequestIds().isEmpty() || !snapshot.allowedActions().contains(action.action())) throw new IllegalArgumentException("TASK_ACTION_NOT_AVAILABLE");
    }
}
