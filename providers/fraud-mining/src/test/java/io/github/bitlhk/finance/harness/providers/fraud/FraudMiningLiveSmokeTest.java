package io.github.bitlhk.finance.harness.providers.fraud;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfEnvironmentVariable(named = "FRAUD_MINING_LIVE", matches = "true")
class FraudMiningLiveSmokeTest {
    @Test
    void knownRiskAccountProducesBlockDecisionAndEvidence() throws Exception {
        String subjectRef = environment("FRAUD_MINING_RISK_SUBJECT", "202276804025");
        CapabilityRequestV1 request = request(subjectRef);
        FraudMiningProvider provider = new FraudMiningProvider(new FraudMiningProvider.Config(
                URI.create(environment("FRAUD_MINING_BASE_URL", "http://127.0.0.1:18001")),
                Duration.ofSeconds(10)));

        Instant startedAt = Instant.now();
        CapabilityResultV1 result = provider.query(request);
        Instant finishedAt = Instant.now();

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("BLOCK", result.data().get("decision"));
        assertEquals("KNOWN_FRAUD_INCIDENT_MATCH", result.data().get("reasonCode"));

        EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                request, result, FraudMiningProvider.PROVIDER_ID, startedAt, finishedAt);
        Path output = Path.of("target/live-smoke/evidence-receipt.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), receipt);
    }

    private static CapabilityRequestV1 request(String subjectRef) {
        String suffix = UUID.randomUUID().toString();
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-live-fraud-" + suffix,
                "trace-live-fraud-" + suffix,
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "TRANSFER",
                "customer-demo-001",
                null,
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("fraud:read"));
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                FraudMiningProvider.TRANSFER_RISK_ASSESS,
                CapabilityRequestV1.Operation.QUERY,
                context,
                Map.of("accountRef", subjectRef),
                null,
                null,
                Map.of(),
                null);
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
