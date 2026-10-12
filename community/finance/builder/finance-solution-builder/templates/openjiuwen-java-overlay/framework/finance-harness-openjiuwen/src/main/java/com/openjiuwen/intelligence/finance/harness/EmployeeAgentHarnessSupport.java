package com.openjiuwen.intelligence.finance.harness;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EaRuntimeTokenSupplier;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/** Builds the employee-assisted Finance Harness adapter from deployment-owned settings. */
public final class EmployeeAgentHarnessSupport {
    private EmployeeAgentHarnessSupport() {}

    public static Optional<FinanceHarnessQueryAdapter> maybeQueryAdapter(Environment environment) {
        if (!"employee-assisted".equals(environment.getProperty(
                "three-engine.finance-harness.profile", ""))) {
            return Optional.empty();
        }
        String baseUri = property(environment, "three-engine.finance-harness.employee-agent.base-uri");
        String runtimeId = property(environment, "three-engine.finance-harness.employee-agent.runtime-id");
        String agentId = property(environment, "three-engine.finance-harness.employee-agent.agent-id");
        String adoptId = property(environment, "three-engine.finance-harness.employee-agent.adopt-id");
        if (baseUri.isBlank() || runtimeId.isBlank() || agentId.isBlank() || adoptId.isBlank()) {
            return Optional.empty();
        }

        var identity = new EmployeeAgentWealthProvider.RuntimeIdentity(runtimeId, agentId, adoptId);
        var provider = new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
                URI.create(baseUri),
                identity,
                EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity),
                Duration.ofSeconds(20)));
        var defaults = new FinanceHarnessQueryAdapter.InvocationDefaults(
                agentId,
                environment.getProperty("three-engine.finance-harness.subject-ref", "selected-customer"),
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "employee-agent",
                "WEALTH_PREVISIT",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:read"));
        return Optional.of(new FinanceHarnessQueryAdapter(
                provider,
                EmployeeAgentWealthProvider.PROVIDER_ID,
                defaults,
                Set.of(EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY),
                false));
    }

    private static String property(Environment environment, String name) {
        return environment.getProperty(name, "").trim();
    }
}
