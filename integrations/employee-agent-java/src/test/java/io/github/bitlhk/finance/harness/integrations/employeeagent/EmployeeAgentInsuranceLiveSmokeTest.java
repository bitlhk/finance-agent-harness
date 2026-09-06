package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentInsuranceProvider;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
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

class EmployeeAgentInsuranceLiveSmokeTest {
    @Test
    void signedRuntimeIdentityReadsAuthorizedInsuranceCustomerContext() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("EMPLOYEE_AGENT_INSURANCE_LIVE")),
                "Set EMPLOYEE_AGENT_INSURANCE_LIVE=true to run against an EA environment");
        String runtimeId = required("EA_INSURANCE_RUNTIME_ID");
        String agentId = required("EA_INSURANCE_AGENT_ID");
        String adoptId = required("EA_INSURANCE_ADOPT_ID");
        String customerRef = required("EA_INSURANCE_CUSTOMER_REF");
        EmployeeAgentRuntimeIdentity identity =
                new EmployeeAgentRuntimeIdentity(runtimeId, agentId, adoptId);
        EmployeeAgentInsuranceProvider provider = new EmployeeAgentInsuranceProvider(
                new EmployeeAgentInsuranceProvider.Config(
                        URI.create(environment("EA_BASE_URL", "http://127.0.0.1:5180")),
                        identity,
                        EaRuntimeTokenSupplier.customerContextFromEnvironment(identity),
                        Duration.ofSeconds(30), () -> LiveIdentityBinding.read("EA_INSURANCE_IDENTITY_BINDING_FILE")));
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentInsuranceProvider.INSURANCE_CUSTOMER_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(
                        FinanceContextV1.SCHEMA_VERSION,
                        "fh-ea-insurance-live-request",
                        "fh-ea-insurance-live-trace",
                        required("EA_INSURANCE_PRINCIPAL_ID"),
                        FinanceContextV1.PrincipalType.EMPLOYEE,
                        "finance-harness-insurance-live-smoke",
                        "INSURANCE_ADVISORY",
                        customerRef,
                        "fh-ea-insurance-live-session",
                        FinanceContextV1.AuthenticationLevel.STRONG,
                        Set.of("insurance:customer:read")),
                Map.of("customerRef", customerRef),
                null,
                "IA-GT-01",
                Map.of(),
                null);

        CapabilityResultV1 result = provider.query(request);
        Path output = Path.of("target/live-smoke/employee-agent-insurance-result.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null
                        ? result.content()
                        : result.error().sourceCode() + ": " + result.error().message());
        assertTrue(result.data().get("customerContext") instanceof Map<?, ?>);
        assertEquals(EmployeeAgentInsuranceProvider.HOST_TOOL, result.data().get("hostCapability"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value.trim();
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
