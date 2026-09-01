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
import static org.junit.jupiter.api.Assertions.assertNotNull;

@EnabledIfEnvironmentVariable(named = "NON_AI_BANKING_TASK_LIVE", matches = "true")
class NonAiBankingTaskLiveSmokeTest {
    @Test
    void channelPreferenceUpdateRequiresConfirmationAndSubmitsOnce() throws Exception {
        String suffix = UUID.randomUUID().toString();
        NonAiBankingProvider provider = new NonAiBankingProvider(
                NonAiBankingProvider.Config.simulator(
                        URI.create(environment("NON_AI_BANKING_BASE_URL", "http://116.204.36.83:8086")),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_NAME"),
                        requiredEnvironment("NON_AI_BANKING_LOGIN_SECRET")));
        CapabilityRequestV1 initial = request(
                suffix,
                "live-channel-" + suffix,
                null,
                Map.of(),
                null);

        Instant startedAt = Instant.now();
        CapabilityResultV1 pending = provider.executeTask(initial);
        assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION, pending.status(), message(pending));
        assertNotNull(pending.task());

        CapabilityRequestV1 confirmed = request(
                suffix,
                initial.idempotencyKey(),
                pending.task().taskRef(),
                pending.task().continuation(),
                new CapabilityRequestV1.Confirmation(true, "确认保存渠道偏好", Instant.now()));
        CapabilityResultV1 completed = provider.executeTask(confirmed);
        CapabilityResultV1 replayed = provider.executeTask(confirmed);
        Instant finishedAt = Instant.now();

        assertEquals(CapabilityResultV1.Status.COMPLETED, completed.status(), message(completed));
        assertEquals(CapabilityResultV1.Status.COMPLETED, replayed.status(), message(replayed));

        EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                confirmed, completed, NonAiBankingProvider.PROVIDER_ID, startedAt, finishedAt);
        Path output = Path.of("target/live-smoke/channel-preference-task-receipt.json");
        Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), receipt);
    }

    private static CapabilityRequestV1 request(
            String suffix,
            String idempotencyKey,
            String taskRef,
            Map<String, Object> continuation,
            CapabilityRequestV1.Confirmation confirmation) {
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-live-channel-" + suffix,
                "trace-live-channel-" + suffix,
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "CHANNEL_SETTINGS",
                "customer-demo-001",
                null,
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("channel:write"));
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.CHANNEL_PREFERENCES_UPDATE,
                CapabilityRequestV1.Operation.TASK,
                context,
                Map.of(),
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
