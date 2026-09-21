package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.execution.TaskCoordination;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Compatibility adapter; journal coordination is shared, SDK/result mapping stays here. */
final class InstalledTaskController {
    private final String agentId;
    private final TaskCoordination tasks;

    InstalledTaskController(String agentId, String scope, TaskCheckpointStore store) {
        this.agentId = agentId;
        tasks = new TaskCoordination(scope, new TaskCoordination.Journal() {
            public <T> T locked(Function<TaskCoordination.Access, T> action) {
                return store.locked(access -> action.apply(new TaskCoordination.Access() {
                    public TaskCoordination.Saved get(String handle) {
                        var saved = access.get(handle);
                        return saved == null ? null : new TaskCoordination.Saved(saved.scope(), saved.capabilityId(),
                                saved.taskId(), saved.arguments(), saved.checkpoint(),
                                saved.terminalResult() == null ? null : result(saved.terminalResult(), ""));
                    }
                    public void put(String handle, TaskCoordination.Saved saved) {
                        try {
                            access.put(handle, new TaskCheckpointStore.SavedTask(saved.scope(), saved.capabilityId(),
                                    saved.taskId(), saved.arguments(), saved.checkpoint(), terminal(saved)));
                        } catch (TaskCheckpointStore.NewTaskRejected rejected) { throw new TaskCoordination.NewTaskRejected(); }
                    }
                }));
            }
        });
    }

    void register(FinanceCapabilityPackV1.Capability capability, OpenJiuwenFinanceBridge bridge) {
        tasks.register(capability.capabilityId(), new TaskCoordination.Backend() {
            private OpenJiuwenInvocationV1 invocation(TaskCoordination.Saved saved) {
                return new OpenJiuwenInvocationV1(saved.taskId(), saved.capabilityId(), agentId,
                        capability.requiredScopes(), saved.arguments(), saved.checkpoint());
            }
            public Map<String, Object> parameters(Map<String, Object> arguments) {
                return OpenJiuwenFinanceBridge.businessParameters(arguments);
            }
            public Map<String, Object> initialCheckpoint(String taskId, Map<String, Object> arguments) {
                return bridge.initialCheckpoint(new OpenJiuwenInvocationV1(taskId, capability.capabilityId(), agentId,
                        capability.requiredScopes(), arguments, Map.of()));
            }
            public void authorize(TaskCoordination.Saved saved) {
                bridge.checkAccess(invocation(saved), CapabilityRequestV1.Operation.TASK);
            }
            public TaskCoordination.Outcome execute(TaskCoordination.Saved saved, TaskCoordination.Approval approval) {
                var execution = bridge.executeTask(invocation(saved), approval == null ? null : new HostTaskConfirmation(
                        approval.eventId(), approval.bindingFingerprint(), approval.confirmedAt(), approval.expiresAt()));
                return new TaskCoordination.Outcome(result(execution, ""), execution.checkpoint(),
                        "AUTHORIZATION_DENIED".equals(execution.errorCode()));
            }
            public Map<String, Object> denied(TaskCoordination.Saved saved) {
                return result(bridge.accessDenied(new OpenJiuwenInvocationV1(saved.taskId(), saved.capabilityId(), agentId,
                        capability.requiredScopes(), Map.of(), Map.of()), Instant.now()), "");
            }
            public Map<String, Object> declined(TaskCoordination.Saved saved) {
                return result(new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.REJECTED, saved.taskId(),
                        "已拒绝此次授权，不提交原任务。", Map.of(), List.of(), "HOST_DECLINED", "", Map.of()), "");
            }
        });
    }
    Map<String, Object> invoke(String capabilityId, Map<String, Object> arguments) { return tasks.invoke(capabilityId, arguments); }
    String confirmationBinding(String handle) { return tasks.confirmationBinding(handle); }
    Map<String, Object> confirm(String handle, HostTaskConfirmation approval) {
        java.util.Objects.requireNonNull(approval, "approval");
        return tasks.confirm(handle, new TaskCoordination.Approval(approval.eventId(), approval.bindingFingerprint(), approval.confirmedAt(), approval.expiresAt()));
    }
    Map<String, Object> decline(String handle) { return tasks.decline(handle); }

    @SuppressWarnings("unchecked")
    static OpenJiuwenExecutionV1 terminal(TaskCoordination.Saved saved) {
        var response = saved.terminalResponse();
        if (response == null) return null;
        return new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.valueOf((String)response.get("status")), saved.taskId(),
                (String)response.get("content"), (Map<String, Object>)response.get("data"),
                (List<Map<String, Object>>)response.get("evidence"), (String)response.get("errorCode"),
                (String)response.get("question"), Map.of());
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
}
