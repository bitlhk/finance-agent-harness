package io.github.bitlhk.finance.harness.providers.employeeagent;

import java.time.Instant;
import java.util.Objects;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;

/** Supplied by the trusted host after resolving the EA adoption owner, never by a model. */
public record EmployeeAgentIdentityBinding(String principalId, EmployeeAgentRuntimeIdentity runtime,
                                           String authorityRef, Instant expiresAt) {
    public EmployeeAgentIdentityBinding {
        if (principalId == null || principalId.isBlank() || authorityRef == null || authorityRef.isBlank()) {
            throw new IllegalArgumentException("Verified adoption-owner mapping is required");
        }
        Objects.requireNonNull(runtime); Objects.requireNonNull(expiresAt);
    }

    static void verify(EmployeeAgentIdentityBinding binding, EmployeeAgentRuntimeIdentity configured,
                       CapabilityRequestV1 request) {
        if (binding == null || !binding.expiresAt().isAfter(Instant.now())
                || !binding.runtime().equals(configured)
                || !binding.principalId().equals(request.context().principalId())
                || request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !Objects.equals(request.context().subjectRef(), request.parameters().get("customerRef"))) {
            throw new SecurityException("EA_IDENTITY_BINDING_REJECTED");
        }
    }
}
