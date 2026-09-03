package io.github.bitlhk.finance.harness.providers.employeeagent;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;

/** Supplies a short-lived host token without exposing the signing mechanism to a Provider. */
@FunctionalInterface
public interface EmployeeAgentRuntimeTokenSupplier {
    String tokenFor(FinanceContextV1 context) throws Exception;
}
