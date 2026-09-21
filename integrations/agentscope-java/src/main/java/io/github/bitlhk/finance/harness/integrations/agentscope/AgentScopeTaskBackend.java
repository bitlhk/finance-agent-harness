package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.execution.*;
import java.util.*;
import java.util.function.*;

/** Core protocol to AgentScope tool-result projection; no banking state machine. */
final class AgentScopeTaskBackend implements TaskCoordination.Backend {
    private final String capabilityId, providerId;
    private final FinanceProvider provider;
    private final Function<String, String> owner;
    private final BiFunction<String, Map<String, Object>, CapabilityRequestV1> requests;
    private final BiConsumer<ProviderTaskExecution.Outcome, EvidenceReceiptV1> evidence;
    AgentScopeTaskBackend(String capabilityId, String providerId, FinanceProvider provider,
            Function<String, String> owner, BiFunction<String, Map<String, Object>, CapabilityRequestV1> requests,
            BiConsumer<ProviderTaskExecution.Outcome, EvidenceReceiptV1> evidence) {
        this.capabilityId = capabilityId; this.providerId = providerId; this.provider = provider;
        this.owner = owner; this.requests = requests; this.evidence = evidence;
    }
    public Map<String, Object> parameters(Map<String, Object> arguments) { return TaskProtocol.parameters(arguments); }
    public Map<String, Object> initialCheckpoint(String taskId, Map<String, Object> arguments) {
        return TaskProtocol.initial(capabilityId, taskId, owner.apply(taskId), arguments);
    }
    public void authorize(TaskCoordination.Saved saved) {
        var request = requests.apply(saved.taskId(), saved.arguments());
        if (provider instanceof InvocationAccess access) access.checkAccess(request);
    }
    public TaskCoordination.Outcome execute(TaskCoordination.Saved saved, TaskCoordination.Approval approval) {
        var outcome = ProviderTaskExecution.execute(provider, () -> requests.apply(saved.taskId(), saved.arguments()),
                new ProviderTaskExecution.Invocation(saved.taskId(), capabilityId, owner.apply(saved.taskId()), saved.arguments(), saved.checkpoint()), approval);
        if (outcome.decision() == ProviderTaskExecution.Decision.ACCESS_DENIED)
            return new TaskCoordination.Outcome(denied(saved), Map.of(), true);
        if (outcome.decision() != ProviderTaskExecution.Decision.BUSINESS_RESULT) {
            boolean pending = outcome.observationRequired();
            return new TaskCoordination.Outcome(Map.of("status", pending ? "NEED_USER" : "REJECTED",
                    "content", pending ? "当前无法确认原任务结果，请沿原任务查证，勿重复提交。" : "本次请求未通过任务执行检查。",
                    "errorCode", outcome.decision().name(), "requiresUserAction", pending,
                    "data", Map.of("capabilityId", capabilityId, "resultStatus", pending ? "UNKNOWN" : "FAILED")), outcome.checkpoint(), false);
        }
        var result = outcome.result();
        var receipt = EvidenceReceipts.from(outcome.request(), result, providerId, outcome.startedAt(), outcome.finishedAt());
        evidence.accept(outcome, receipt);
        String status = result.status() == CapabilityResultV1.Status.COMPLETED ? "COMPLETED"
                : TaskProtocol.pending(result) || outcome.observationRequired() ? "NEED_USER" : "REJECTED";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capabilityId", capabilityId); data.put("result", result.data());
        data.put("resultStatus", result.status().name()); data.put("requestId", result.requestId()); data.put("traceId", result.traceId());
        data.put("sourceSystem", result.sourceSystem()); data.put("asOf", result.asOf().toString());
        data.put("missingInputs", result.missingInputs()); data.put("nextActions", result.nextActions());
        if (result.task() != null) data.put("taskState", Map.of("taskRef", result.task().taskRef(),
                "status", result.task().status(), "confirmationRequired", result.task().confirmationRequired()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status); response.put("content", result.content()); response.put("data", Map.copyOf(data));
        response.put("requiresUserAction", "NEED_USER".equals(status)); response.put("evidence", List.of(receipt));
        if (result.error() != null) response.put("errorCode", result.error().sourceCode());
        return new TaskCoordination.Outcome(response, outcome.checkpoint(), false);
    }
    public Map<String, Object> denied(TaskCoordination.Saved saved) {
        return Map.of("status", "REJECTED", "errorCode", "AUTHORIZATION_DENIED", "content", "当前身份或权限不允许此操作。", "requiresUserAction", false);
    }
    public Map<String, Object> declined(TaskCoordination.Saved saved) {
        return Map.of("status", "REJECTED", "errorCode", "HOST_DECLINED", "content", "已拒绝此次授权，不提交原任务。", "requiresUserAction", false);
    }
}
