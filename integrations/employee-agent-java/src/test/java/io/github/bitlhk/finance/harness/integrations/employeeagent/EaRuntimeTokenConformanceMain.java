package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** Emits one test token for cross-language verification against the EA verifier. */
public final class EaRuntimeTokenConformanceMain {
    private EaRuntimeTokenConformanceMain() {
    }

    public static void main(String[] args) throws Exception {
        String secret = requiredEnvironment("EA_TOKEN_CONFORMANCE_SECRET");
        EmployeeAgentRuntimeIdentity identity =
                new EmployeeAgentRuntimeIdentity(
                        "openjiuwen-java",
                        "jiuwen_lgj-wealth",
                        "lgj-wealth");
        EaRuntimeTokenSupplier supplier = new EaRuntimeTokenSupplier(
                identity,
                EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE,
                () -> secret.getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(60));
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-conformance",
                "trace-conformance",
                "employee-conformance",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "employee-agent",
                "WEALTH_PREVISIT",
                "C-005",
                "session-conformance",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:customer:read"));
        System.out.print(supplier.tokenFor(context));
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
