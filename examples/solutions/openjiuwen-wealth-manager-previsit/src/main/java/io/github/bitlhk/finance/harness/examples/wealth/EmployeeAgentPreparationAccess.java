package io.github.bitlhk.finance.harness.examples.wealth;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentWealthSession;
import java.util.Map;
import java.util.Objects;

/** Compatibility adapter for historical evidence. New managed builds use the direct CRM access. */
public final class EmployeeAgentPreparationAccess implements PreparationAccess {
    private final EmployeeAgentWealthSession delegate;

    public EmployeeAgentPreparationAccess(EmployeeAgentWealthSession delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override public Snapshot authorize(String customerRef) {
        var authority = delegate.authorizePrevisit(customerRef);
        return new Snapshot(authority.binding().authorityRef(), authority.grant().principalId(),
                authority.authenticationLevel());
    }

    @Override public String providerId() { return "employee-agent-platform"; }

    @Override public CapabilityResultV1 query(String requestId, String traceId, String customerRef,
            String capabilityId, Map<String, Object> arguments) {
        return delegate.query(requestId, traceId, customerRef, capabilityId, arguments);
    }

    @Override public void close() { delegate.close(); }
}
