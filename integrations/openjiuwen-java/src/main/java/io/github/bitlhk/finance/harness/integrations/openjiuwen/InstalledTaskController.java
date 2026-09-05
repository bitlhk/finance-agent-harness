package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates host checkpoints and approvals; the bank still owns all business task states. */
final class InstalledTaskController {
    private final String scope;
    private final String agentId;
    private final TaskCheckpointStore store;
    private final Map<String, FinanceCapabilityPackV1.Capability> capabilities = new LinkedHashMap<>();
    private final Map<String, OpenJiuwenFinanceBridge> bridges = new LinkedHashMap<>();

    InstalledTaskController(String agentId, String scope, TaskCheckpointStore store) {
        this.agentId = agentId;
        this.scope = scope;
        this.store = store;
    }

    void register(FinanceCapabilityPackV1.Capability capability, OpenJiuwenFinanceBridge bridge) {
        capabilities.put(capability.capabilityId(), capability);
        bridges.put(capability.capabilityId(), bridge);
    }

    Map<String, Object> invoke(String capabilityId, Map<String, Object> arguments) {
        return run(capabilityId, arguments, null);
    }

    String confirmationBinding(String handle) {
        return store.locked(access -> {
            TaskCheckpointStore.SavedTask saved = owned(access, handle);
            Object binding = saved.checkpoint().get(OpenJiuwenFinanceBridge.CK_BINDING);
            if (saved.terminalResult() != null || !(binding instanceof String text) || text.isBlank()) {
                throw new IllegalStateException("Task is not awaiting a host confirmation");
            }
            return text;
        });
    }

    Map<String, Object> confirm(String handle, HostTaskConfirmation approval) {
        String capability = store.locked(access -> owned(access, handle).capabilityId());
        return run(capability, Map.of(OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT, handle),
                java.util.Objects.requireNonNull(approval, "approval"));
    }

    Map<String, Object> decline(String handle) {
        return store.locked(access -> {
            TaskCheckpointStore.SavedTask saved = owned(access, handle);
            if (saved.terminalResult() != null) return result(saved.terminalResult(), "");
            if (!saved.checkpoint().containsKey(OpenJiuwenFinanceBridge.CK_BINDING)) {
                return error("TASK_NOT_AWAITING_CONFIRMATION", "原任务结果仍需查证，不能作为未提交任务取消。");
            }
            OpenJiuwenExecutionV1 declined = new OpenJiuwenExecutionV1(
                    OpenJiuwenExecutionV1.Status.REJECTED, saved.taskId(), "已拒绝此次授权，不提交原任务。",
                    Map.of(), List.of(), "HOST_DECLINED", "", Map.of());
            access.put(handle, saved(saved, Map.of(), declined));
            return result(declined, "");
        });
    }

    private Map<String, Object> run(String capabilityId, Map<String, Object> current, HostTaskConfirmation approval) {
        return store.locked(access -> {
            Object requested = current.get(OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT);
            String handle = requested == null ? "" : String.valueOf(requested).trim();
            TaskCheckpointStore.SavedTask prior = null;
            if (!handle.isBlank()) {
                try { prior = owned(access, handle); }
                catch (IllegalArgumentException exception) {
                    return error("FINANCE_TASK_HANDLE_NOT_FOUND", "无法找到有权续办的原任务，请联系人工查证，勿重复提交。");
                }
                if (!capabilityId.equals(prior.capabilityId())) {
                    return error("TASK_CAPABILITY_MISMATCH", "此句柄不属于当前业务能力。");
                }
                if (prior.terminalResult() != null) return result(prior.terminalResult(), "");
                Map<String, Object> supplied = OpenJiuwenFinanceBridge.businessParameters(current);
                for (var field : supplied.entrySet()) {
                    if (!prior.arguments().containsKey(field.getKey()) || !OpenJiuwenFinanceBridge.fingerprint(field.getValue())
                            .equals(OpenJiuwenFinanceBridge.fingerprint(prior.arguments().get(field.getKey())))) {
                        return error("TASK_PARAMETERS_CHANGED", "原任务金额、收款人等参数不可在续办时变更。");
                    }
                }
            }
            var capability = capabilities.get(capabilityId);
            var bridge = bridges.get(capabilityId);
            if (bridge == null) return error("CAPABILITY_NOT_INSTALLED", "当前未安装此能力。");
            if (prior == null) {
                handle = "fh-task-handle-" + UUID.randomUUID();
                String taskId = "fh-task-" + UUID.randomUUID();
                Map<String, Object> business = OpenJiuwenFinanceBridge.businessParameters(current);
                var initial = new OpenJiuwenInvocationV1(taskId, capabilityId, agentId,
                        capability.requiredScopes(), business, Map.of());
                prior = new TaskCheckpointStore.SavedTask(scope, capabilityId, taskId, business,
                        bridge.initialCheckpoint(initial), null);
                access.put(handle, prior);
            }
            if (approval != null && !approval.validFor(
                    String.valueOf(prior.checkpoint().get(OpenJiuwenFinanceBridge.CK_BINDING)), Instant.now())) {
                return error("HOST_CONFIRMATION_INVALID", "确认已过期或与原任务不一致。");
            }
            var invocation = new OpenJiuwenInvocationV1(prior.taskId(), capabilityId, agentId,
                    capability.requiredScopes(), prior.arguments(), prior.checkpoint());
            if (approval != null) {
                // Persist uncertainty BEFORE a write. A crash must resume by observation, never replay approval.
                Map<String, Object> recovery = new LinkedHashMap<>(OpenJiuwenFinanceBridge.recoveryCheckpoint(invocation));
                Map<String, Object> continuation = new LinkedHashMap<>();
                Object existing = recovery.get(OpenJiuwenFinanceBridge.CK_CONTINUATION);
                if (existing instanceof Map<?, ?> map) map.forEach((key, value) -> continuation.put(String.valueOf(key), value));
                continuation.put("submitOutcomeUnknown", true);
                recovery.put(OpenJiuwenFinanceBridge.CK_CONTINUATION, Map.copyOf(continuation));
                access.put(handle, saved(prior, recovery, null));
            }
            OpenJiuwenExecutionV1 execution = bridge.executeTask(invocation, approval);
            if (!execution.checkpoint().isEmpty()) {
                Map<String, Object> checkpoint = execution.checkpoint();
                if (approval != null) {
                    Map<String, Object> uncertain = new LinkedHashMap<>(checkpoint);
                    uncertain.remove(OpenJiuwenFinanceBridge.CK_BINDING);
                    Map<String, Object> continuation = new LinkedHashMap<>();
                    Object returned = checkpoint.get(OpenJiuwenFinanceBridge.CK_CONTINUATION);
                    if (returned instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> continuation.put(String.valueOf(key), value));
                    }
                    continuation.put("submitOutcomeUnknown", true);
                    uncertain.put(OpenJiuwenFinanceBridge.CK_CONTINUATION, Map.copyOf(continuation));
                    checkpoint = Map.copyOf(uncertain);
                }
                access.put(handle, saved(prior, checkpoint, null));
                return result(execution, handle);
            }
            access.put(handle, saved(prior, Map.of(), execution));
            return result(execution, "");
        });
    }

    private TaskCheckpointStore.SavedTask owned(TaskCheckpointStore.Access access, String handle) {
        if (handle == null || !handle.matches("fh-task-handle-[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Unknown task handle");
        }
        var saved = access.get(handle);
        if (saved == null || !scope.equals(saved.scope())) throw new IllegalArgumentException("Unknown task handle");
        return saved;
    }

    private static TaskCheckpointStore.SavedTask saved(TaskCheckpointStore.SavedTask previous,
            Map<String, Object> checkpoint, OpenJiuwenExecutionV1 terminal) {
        return new TaskCheckpointStore.SavedTask(previous.scope(), previous.capabilityId(), previous.taskId(),
                previous.arguments(), checkpoint, terminal);
    }

    static Map<String, Object> result(OpenJiuwenExecutionV1 execution, String handle) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("status", execution.status().name());
        output.put("content", execution.content());
        output.put("data", execution.data());
        output.put("evidence", execution.evidence());
        output.put("requiresUserAction", execution.status() == OpenJiuwenExecutionV1.Status.NEED_USER);
        if (!execution.errorCode().isBlank()) output.put("errorCode", execution.errorCode());
        if (!execution.question().isBlank()) output.put("question", execution.question());
        if (!handle.isBlank()) output.put(OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT, handle);
        return Map.copyOf(output);
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("status", "REJECTED", "errorCode", code, "content", message, "requiresUserAction", false);
    }
}
