package io.github.bitlhk.finance.harness.providers.nonaibanking;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@EnabledIfEnvironmentVariable(named = "NON_AI_BANKING_TRANSFER_LIVE", matches = "true")
class NonAiBankingTransferLiveSmokeTest {
    @Test
    void deployedSimulatorBlocksFraudAndCompletesConfirmedTransferOnce() throws Exception {
        NonAiBankingProvider provider = new NonAiBankingProvider(new NonAiBankingProvider.Config(
                URI.create(environment("NON_AI_BANKING_BASE_URL", "http://127.0.0.1:18088")),
                requiredEnvironment("NON_AI_BANKING_LOGIN_NAME"),
                requiredEnvironment("NON_AI_BANKING_LOGIN_SECRET"),
                "finance-harness-transfer-live",
                "M",
                "finance-harness-live-smoke",
                Duration.ofSeconds(10),
                environment("NON_AI_BANKING_STEP_UP_CODE", "000000")));

        String suffix = UUID.randomUUID().toString();
        CapabilityRequestV1 blockedRequest = request(
                suffix,
                "live-fraud-" + suffix,
                null,
                Map.of(
                        "fromAccountId", "acct-001",
                        "beneficiaryId", "bene-001",
                        "amount", 88.5,
                        "purpose", "Finance Harness 反诈验收"),
                Map.of(),
                null);
        Instant blockedStartedAt = Instant.now();
        CapabilityResultV1 blocked = provider.executeTask(blockedRequest);
        Instant blockedFinishedAt = Instant.now();

        assertEquals(CapabilityResultV1.Status.FAILED, blocked.status(), message(blocked));
        assertNotNull(blocked.error());
        assertEquals("TRANSFER_FRAUD_RISK_BLOCKED", blocked.error().sourceCode());
        assertNull(blocked.task());

        CapabilityRequestV1 initial = request(
                suffix,
                "live-normal-" + suffix,
                null,
                Map.of(
                        "fromAccountId", "acct-001",
                        "beneficiaryId", "bene-002",
                        "amount", 0.01,
                        "purpose", "Finance Harness 正常转账验收"),
                Map.of(),
                null);
        Instant normalStartedAt = Instant.now();
        CapabilityResultV1 pending = provider.executeTask(initial);
        assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION, pending.status(), message(pending));
        assertNotNull(pending.task());

        CapabilityRequestV1 confirmed = request(
                suffix,
                initial.idempotencyKey(),
                pending.task().taskRef(),
                Map.of(),
                pending.task().continuation(),
                new CapabilityRequestV1.Confirmation(true, "确认提交", Instant.now()));
        CapabilityResultV1 completed = provider.executeTask(confirmed);
        CapabilityResultV1 replayed = provider.executeTask(confirmed);
        Instant normalFinishedAt = Instant.now();

        assertEquals(CapabilityResultV1.Status.COMPLETED, completed.status(), message(completed));
        assertEquals(CapabilityResultV1.Status.COMPLETED, replayed.status(), message(replayed));

        Path output = Path.of("target/live-smoke/transfer-task-receipts.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                "fraudBlocked", EvidenceReceipts.from(
                        blockedRequest, blocked, NonAiBankingProvider.PROVIDER_ID,
                        blockedStartedAt, blockedFinishedAt),
                "normalCompleted", EvidenceReceipts.from(
                        confirmed, completed, NonAiBankingProvider.PROVIDER_ID,
                        normalStartedAt, normalFinishedAt)));
    }

    private static CapabilityRequestV1 request(
            String suffix,
            String idempotencyKey,
            String taskRef,
            Map<String, Object> parameters,
            Map<String, Object> continuation,
            CapabilityRequestV1.Confirmation confirmation) {
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-live-transfer-" + suffix,
                "trace-live-transfer-" + suffix,
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "TRANSFER",
                "customer-demo-001",
                null,
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("transfer:write"));
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.TRANSFER_INTERNAL_CREATE,
                CapabilityRequestV1.Operation.TASK,
                context,
                parameters,
                idempotencyKey,
                taskRef,
                continuation,
                confirmation);
    }

    private static String message(CapabilityResultV1 result) {
        return result.error() == null ? result.content() : result.error().message();
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
