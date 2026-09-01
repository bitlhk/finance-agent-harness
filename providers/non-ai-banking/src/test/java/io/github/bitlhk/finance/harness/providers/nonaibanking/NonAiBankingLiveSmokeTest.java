package io.github.bitlhk.finance.harness.providers.nonaibanking;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceiptV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceipts;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "NON_AI_BANKING_LIVE", matches = "true")
class NonAiBankingLiveSmokeTest {
    @Test
    void deployedSimulatorReturnsAccountFactsAndEvidence() throws Exception {
        String suffix = UUID.randomUUID().toString();
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-live-" + suffix,
                "trace-live-" + suffix,
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "ACCOUNT_SERVICE",
                "customer-demo-001",
                null,
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("account:read"));
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.ACCOUNT_BALANCE_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context,
                Map.of(),
                null,
                null,
                Map.of(),
                null);
        NonAiBankingProvider provider = new NonAiBankingProvider(
                NonAiBankingProvider.Config.simulator(
                        URI.create(environment("NON_AI_BANKING_BASE_URL", "http://116.204.36.83:8086")),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_NAME"),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_SECRET")));

        Instant startedAt = Instant.now();
        CapabilityResultV1 result = provider.query(request);
        Instant finishedAt = Instant.now();

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null ? result.content() : result.error().message());
        assertEquals("account-core-simulator", result.sourceSystem());
        assertTrue(result.data().get("accounts") instanceof java.util.List<?> accounts && !accounts.isEmpty());

        EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                request, result, NonAiBankingProvider.PROVIDER_ID, startedAt, finishedAt);
        Path output = Path.of("target/live-smoke/evidence-receipt.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), receipt);
    }

    @Test
    void deployedSimulatorReturnsCompositeWealthContext() {
        String suffix = UUID.randomUUID().toString();
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-live-wealth-" + suffix,
                "trace-live-wealth-" + suffix,
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "WEALTH_DIAGNOSIS",
                "customer-demo-001",
                null,
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("wealth:read"));
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context,
                Map.of(),
                null,
                null,
                Map.of(),
                null);
        NonAiBankingProvider provider = new NonAiBankingProvider(
                NonAiBankingProvider.Config.simulator(
                        URI.create(environment("NON_AI_BANKING_BASE_URL", "http://116.204.36.83:8086")),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_NAME"),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_SECRET")));

        CapabilityResultV1 result = provider.query(request);

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null ? result.content() : result.error().message());
        assertTrue(result.data().get("wealthAggregate") instanceof Map<?, ?>);
        assertTrue(result.data().get("riskAssessment") instanceof Map<?, ?>);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for live smoke");
        }
        return value;
    }
}
