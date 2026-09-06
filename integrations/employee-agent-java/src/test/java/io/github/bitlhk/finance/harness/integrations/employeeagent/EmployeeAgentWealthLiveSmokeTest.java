package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeAgentWealthLiveSmokeTest {
    @Test
    void signedRuntimeIdentityReadsAuthorizedWealthContext() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("EMPLOYEE_AGENT_LIVE")),
                "Set EMPLOYEE_AGENT_LIVE=true to run against an EA environment");
        String runtimeId = required("EA_RUNTIME_ID");
        String agentId = required("EA_AGENT_ID");
        String adoptId = required("EA_ADOPT_ID");
        String customerRef = required("EA_CUSTOMER_REF");
        EmployeeAgentRuntimeIdentity identity =
                new EmployeeAgentRuntimeIdentity(runtimeId, agentId, adoptId);
        EmployeeAgentWealthProvider provider = new EmployeeAgentWealthProvider(
                new EmployeeAgentWealthProvider.Config(
                        URI.create(environment("EA_BASE_URL", "http://127.0.0.1:5180")),
                        identity,
                        EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity),
                        Duration.ofSeconds(30)));
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(
                        FinanceContextV1.SCHEMA_VERSION,
                        "fh-ea-live-request",
                        "fh-ea-live-trace",
                        "employee:" + adoptId,
                        FinanceContextV1.PrincipalType.EMPLOYEE,
                        "finance-harness-live-smoke",
                        "WEALTH_PREVISIT",
                        customerRef,
                        "fh-ea-live-session",
                        FinanceContextV1.AuthenticationLevel.STRONG,
                        Set.of("wealth:previsit:read")),
                Map.of("customerRef", customerRef),
                null,
                null,
                Map.of(),
                null);

        CapabilityResultV1 result = provider.query(request);
        Path output = Path.of("target/live-smoke/employee-agent-wealth-result.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null ? result.content() : result.error().sourceCode() + ": " + result.error().message());
        assertTrue(result.data().get("previsitContext") instanceof Map<?, ?>);
        assertEquals(EmployeeAgentWealthProvider.HOST_TOOL, result.data().get("hostCapability"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value.trim();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
