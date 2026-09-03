package io.github.bitlhk.finance.harness.providers.employeeagent;

/** Stable EA runtime identity shared by wealth, insurance and later employee-facing Providers. */
public record EmployeeAgentRuntimeIdentity(String runtimeId, String agentId, String adoptId) {
    public EmployeeAgentRuntimeIdentity {
        runtimeId = required(runtimeId, "runtimeId");
        agentId = required(agentId, "agentId");
        adoptId = required(adoptId, "adoptId");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
