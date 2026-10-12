package com.openjiuwen.intelligence.finance.harness;

import com.openjiuwen.intelligence.contract.CapabilityDescriptor;
import com.openjiuwen.intelligence.contract.ExecutionResult;
import com.openjiuwen.intelligence.contract.ExecutionStatus;
import com.openjiuwen.intelligence.contract.TaskEnvelope;
import com.openjiuwen.intelligence.execution.RoutableCapabilityAdapter;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenExecutionV1;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenInvocationV1;
import io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditCapabilityCatalog;
import io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelProvider;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Binds task purpose and scope from the installed scenario, never browser arguments. */
public final class SmartAuditCapabilityAdapter implements RoutableCapabilityAdapter {
    private final SmartAuditModelProvider provider;
    private final String scenario;
    private final String principalId;
    private final String subjectRef;
    private final String sessionRef;

    public SmartAuditCapabilityAdapter(SmartAuditModelProvider provider, String scenario,
                                       String principalId, String subjectRef, String sessionRef) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.principalId = required(principalId, "principalId");
        this.subjectRef = required(subjectRef, "subjectRef");
        this.sessionRef = required(sessionRef, "sessionRef");
    }

    @Override
    public boolean supports(String capabilityId) {
        try {
            var task = SmartAuditCapabilityCatalog.require(capabilityId);
            return task.personal() == "personal-loan".equals(scenario);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public ExecutionResult invoke(CapabilityDescriptor capability, TaskEnvelope task, Map<String, Object> checkpoint) {
        if (!supports(task.capabilityId())) throw new SecurityException("CAPABILITY_NOT_INSTALLED");
        var definition = SmartAuditCapabilityCatalog.require(task.capabilityId());
        var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults(
                principalId, subjectRef, FinanceContextV1.PrincipalType.EMPLOYEE,
                "internal-control", definition.purpose(), FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of(definition.requiredScope()));
        var bridge = new OpenJiuwenFinanceBridge(provider, SmartAuditModelProvider.PROVIDER_ID, defaults,
                Set.of(definition.capabilityId()), false, sessionRef);
        OpenJiuwenExecutionV1 result = bridge.query(new OpenJiuwenInvocationV1(
                task.taskId(), task.capabilityId(), capability.ownerAgentId(),
                Set.copyOf(capability.requiredPermissions()), task.arguments(), checkpoint));
        ExecutionStatus status = switch (result.status()) {
            case COMPLETED -> ExecutionStatus.COMPLETED;
            case NEED_USER -> ExecutionStatus.NEED_USER;
            case REJECTED -> ExecutionStatus.REJECTED;
        };
        return new ExecutionResult(status, result.taskId(), result.content(), result.data(), result.evidence(),
                result.errorCode(), result.question(), result.checkpoint());
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
}
