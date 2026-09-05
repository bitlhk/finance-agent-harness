package io.github.bitlhk.finance.harness.providers.employeeagent;

import java.time.Instant;
import java.util.Objects;

/** Supplied by the trusted host after resolving the EA adoption owner, never by a model. */
public record EmployeeAgentIdentityBinding(String principalId, EmployeeAgentRuntimeIdentity runtime,
                                           String authorityRef, Instant expiresAt) {
    public EmployeeAgentIdentityBinding {
        if (principalId == null || principalId.isBlank() || authorityRef == null || authorityRef.isBlank()) {
            throw new IllegalArgumentException("Verified adoption-owner mapping is required");
        }
        Objects.requireNonNull(runtime); Objects.requireNonNull(expiresAt);
    }
}
