package io.github.bitlhk.finance.harness.execution;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.spi.InputCollectionProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;

/** Shared TASK request/checkpoint protocol. The caller still supplies fresh authorized context. */
public final class TaskProtocol {
    private static final Set<String> CONTROLS = Set.of("query", "message", "confirmedCapabilityId", "confirmationText",
            "idempotencyKey", "targetCapabilityId", "candidateCapabilityIds", "cancel", "reconcile", "verificationCode",
            "financeTaskHandle", "_confirmation", "_cancel", "_reconcile");
    private TaskProtocol() { }

    public static Map<String, Object> parameters(Map<String, Object> arguments) {
        Map<String, Object> values = new LinkedHashMap<>();
        arguments.forEach((key, value) -> { if (key != null && value != null && !CONTROLS.contains(key)) values.put(key, value); });
        return freeze(values);
    }
    public static String idempotencyKey(String capabilityId, String taskId) {
        return "fh-" + UUID.nameUUIDFromBytes((capabilityId + ":" + taskId).getBytes(StandardCharsets.UTF_8));
    }
    public static String normalizeIdempotencyKey(String raw) {
        String key = text(raw);
        if (key.isBlank() || (key.length() >= 8 && key.length() <= 128)) return key;
        try { return "fh-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { return idempotencyKey("fallback", key); }
    }
    public static Map<String, Object> initial(String capabilityId, String taskId, String owner, Map<String, Object> arguments) {
        return Map.of(CK_CAPABILITY, capabilityId, CK_OWNER, owner, CK_PARAMETERS, parameters(arguments),
                CK_IDEMPOTENCY, idempotencyKey(capabilityId, taskId));
    }
    public static CapabilityRequestV1 request(CapabilityRequestV1 base, String taskId, Map<String, Object> arguments,
            Map<String, Object> prior, TaskCoordination.Approval approval) {
        if (base.operation() != CapabilityRequestV1.Operation.TASK) throw new IllegalArgumentException("TASK_REQUEST_REQUIRED");
        String key = text(prior.get(CK_IDEMPOTENCY));
        if (key.isBlank()) key = text(arguments.get("idempotencyKey"));
        key = normalizeIdempotencyKey(key);
        if (key.isBlank()) key = idempotencyKey(base.capabilityId(), taskId);
        String taskRef = text(prior.get(CK_TASK_REF));
        return new CapabilityRequestV1(base.schemaVersion(), base.capabilityId(), base.operation(), base.context(),
                prior.containsKey(CK_PARAMETERS) && !canSupplement(prior, base.parameters()) ? savedParameters(prior) : base.parameters(),
                key, taskRef.isBlank() ? null : taskRef, continuation(prior),
                approval == null ? null : new CapabilityRequestV1.Confirmation(true, "Host approval event: " + approval.eventId(), approval.confirmedAt()));
    }
    public static Map<String, Object> checkpoint(CapabilityResultV1 result, CapabilityRequestV1 request,
            Map<String, Object> previous, String owner, InputCollectionProvider collector) {
        var task = result.task();
        if (task == null) {
            Map<String, Object> prior = new LinkedHashMap<>(recoveryCheckpoint(previous));
            if (result.status() == CapabilityResultV1.Status.NEED_USER && !prior.containsKey(CK_TASK_REF)
                    && !Boolean.TRUE.equals(continuation(prior).get("submitOutcomeUnknown")) && collector != null) {
                Set<String> fields = collector.collectableInputs(request, result);
                if (!fields.isEmpty()) { prior.put(CK_INPUTS, fields.stream().sorted().toList()); prior.put(CK_PARAMETERS, request.parameters()); }
            }
            return Map.copyOf(prior);
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put(CK_TASK_REF, task.taskRef()); checkpoint.put(CK_STATUS, task.status());
        checkpoint.put(CK_CAPABILITY, request.capabilityId()); checkpoint.put(CK_IDEMPOTENCY, request.idempotencyKey());
        checkpoint.put(CK_CONTINUATION, task.continuation()); checkpoint.put(CK_PARAMETERS, request.parameters());
        checkpoint.put(CK_OWNER, owner);
        if (task.confirmationRequired()) checkpoint.put(CK_BINDING,
                fingerprint(Map.of("checkpoint", checkpoint, "summary", result.content(), "data", result.data())));
        return Map.copyOf(checkpoint);
    }
    public static boolean pending(CapabilityResultV1 result) {
        return switch (result.status()) {
            case NEED_CONFIRMATION, NEED_USER, PROCESSING, UNKNOWN -> true;
            default -> false;
        };
    }
    public static boolean retainForObservation(CapabilityResultV1 result, Map<String, Object> prior) {
        return !prior.isEmpty() && result.task() == null && (prior.containsKey(CK_TASK_REF)
                || (result.error() != null && (result.error().retryable() || "TEMPORARY_FAILURE".equals(result.error().failureClass()))));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> savedParameters(Map<String, Object> prior) { return (Map<String, Object>)prior.get(CK_PARAMETERS); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> continuation(Map<String, Object> prior) {
        return prior.get(CK_CONTINUATION) instanceof Map<?, ?> map ? freeze((Map<String, Object>)map) : Map.of();
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
