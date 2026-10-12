package com.openjiuwen.intelligence.finance.harness;

import com.openjiuwen.intelligence.contract.CapabilityDescriptor;
import com.openjiuwen.intelligence.contract.ExecutionResult;
import com.openjiuwen.intelligence.contract.ExecutionStatus;
import com.openjiuwen.intelligence.contract.TaskEnvelope;
import com.openjiuwen.intelligence.execution.RoutableCapabilityAdapter;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenExecutionV1;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenInvocationV1;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Thin host adapter between the Candidate runtime and the installable Harness bridge. */
public final class FinanceHarnessCapabilityAdapter implements RoutableCapabilityAdapter {
    private final OpenJiuwenFinanceBridge bridge;

    public FinanceHarnessCapabilityAdapter(OpenJiuwenFinanceBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public boolean supports(String capabilityId) {
        return bridge.supports(capabilityId);
    }

    @Override
    public ExecutionResult invoke(
            CapabilityDescriptor capability,
            TaskEnvelope task,
            Map<String, Object> checkpoint) {
        OpenJiuwenInvocationV1 invocation = new OpenJiuwenInvocationV1(
                task.taskId(),
                task.capabilityId(),
                capability.ownerAgentId(),
                Set.copyOf(capability.requiredPermissions()),
                task.arguments(),
                checkpoint);
        OpenJiuwenExecutionV1 result = bridge.query(invocation);
        ExecutionStatus status = switch (result.status()) {
            case COMPLETED -> ExecutionStatus.COMPLETED;
            case NEED_USER -> ExecutionStatus.NEED_USER;
            case REJECTED -> ExecutionStatus.REJECTED;
        };
        return new ExecutionResult(
                status,
                result.taskId(),
                result.content(),
                result.data(),
                result.evidence(),
                result.errorCode(),
                result.question(),
                result.checkpoint());
    }
}
