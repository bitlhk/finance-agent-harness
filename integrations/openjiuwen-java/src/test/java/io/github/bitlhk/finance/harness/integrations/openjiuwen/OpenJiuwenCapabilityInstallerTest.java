package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenJiuwenCapabilityInstallerTest {
    private static final String AGENT_ID = "clean-openjiuwen-agent";
    private static final String ACCOUNT_TOOL = "account_balance_query";
    private static final String TRANSFER_TOOL = "transfer_internal_create";

    @Test
    void packMakesToolsVisibleAndExecutableThenUninstallsThem() throws Exception {
        FinanceCapabilityPackV1 pack = new FinanceCapabilityPackLoader().load(
                root().resolve("capability-packs/retail-banking-basics/pack.yaml"));
        List<CapabilityRequestV1> taskRequests = new ArrayList<>();
        AtomicReference<CapabilityRequestV1> queryRequest = new AtomicReference<>();
        FinanceProvider provider = provider(queryRequest, taskRequests);
        ReActAgent cleanAgent = new ReActAgent(AgentCard.builder()
                .id(AGENT_ID)
                .name("Clean OpenJiuwen Agent")
                .description("Agent without application-owned financial tools")
                .build());

        assertNull(cleanAgent.getAbilityManager().get(ACCOUNT_TOOL));
        assertNull(cleanAgent.getAbilityManager().get(TRANSFER_TOOL));

        OpenJiuwenCapabilityInstaller.Installation installation =
                new OpenJiuwenCapabilityInstaller().install(
                        cleanAgent,
                        provider,
                        "reference-bank-provider",
                        defaults(),
                        pack,
                        true);
        try {
            assertNotNull(cleanAgent.getAbilityManager().get(ACCOUNT_TOOL));
            assertNotNull(cleanAgent.getAbilityManager().get(TRANSFER_TOOL));
            assertNotNull(Runner.resourceMgr().getTool(ACCOUNT_TOOL));
            assertTrue(cleanAgent.getSystemPromptBuilder().hasSection(
                    "finance-capability-pack:retail-banking-basics"));
            assertEquals(Set.of(ACCOUNT_TOOL, TRANSFER_TOOL), installation.toolNames());
            assertEquals("retail-banking-basics", installation.receipt().packId());
            assertEquals("0.1.0", installation.receipt().packVersion());
            assertEquals(2, installation.receipt().capabilities().size());

            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>) installation.tool(ACCOUNT_TOOL).invoke(Map.of());
            assertEquals("COMPLETED", query.get("status"));
            assertEquals("account.balance.query", queryRequest.get().capabilityId());
            assertEquals(Set.of("account:read"), queryRequest.get().context().scopes());

            @SuppressWarnings("unchecked")
            Map<String, Object> first = (Map<String, Object>) installation.tool(TRANSFER_TOOL).invoke(Map.of(
                    "fromAccountId", "acct-001",
                    "beneficiaryId", "bene-002",
                    "amount", 0.01));
            assertEquals("NEED_USER", first.get("status"));
            assertEquals(true, first.get("requiresUserAction"));
            String handle = String.valueOf(first.get(OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT));
            assertTrue(handle.startsWith("fh-task-handle-"));

            Instant confirmedAt = Instant.now();
            Map<String, Object> second = installation.confirm(handle, new HostTaskConfirmation(
                    "user-event-001", installation.confirmationBinding(handle), confirmedAt, confirmedAt.plusSeconds(60)));
            assertEquals("COMPLETED", second.get("status"));
            assertFalse(second.containsKey(OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT));
            assertEquals(2, taskRequests.size());
            assertNull(taskRequests.get(0).taskRef());
            assertEquals("bank-task-001", taskRequests.get(1).taskRef());
            assertEquals(taskRequests.get(0).context().sessionRef(), taskRequests.get(1).context().sessionRef());
            assertEquals(taskRequests.get(0).idempotencyKey(), taskRequests.get(1).idempotencyKey());
            assertEquals(Map.of(
                    "fromAccountId", "acct-001",
                    "beneficiaryId", "bene-002",
                    "amount", 0.01), taskRequests.get(1).parameters());
            assertTrue(taskRequests.get(1).confirmation().confirmed());
        } finally {
            installation.close();
        }

        assertNull(cleanAgent.getAbilityManager().get(ACCOUNT_TOOL));
        assertNull(cleanAgent.getAbilityManager().get(TRANSFER_TOOL));
        assertNull(Runner.resourceMgr().getTool(ACCOUNT_TOOL));
        assertNull(Runner.resourceMgr().getTool(TRANSFER_TOOL));
        assertFalse(cleanAgent.getSystemPromptBuilder().hasSection(
                "finance-capability-pack:retail-banking-basics"));
    }

    @Test
    void invalidResumeHandleIsRejectedWithoutCallingProvider() throws Exception {
        FinanceCapabilityPackV1 pack = new FinanceCapabilityPackLoader().load(
                root().resolve("capability-packs/retail-banking-basics/pack.yaml"));
        List<CapabilityRequestV1> taskRequests = new ArrayList<>();
        ReActAgent cleanAgent = new ReActAgent(AgentCard.builder()
                .id(AGENT_ID + "-invalid-resume")
                .name("Clean OpenJiuwen Agent")
                .description("Invalid resume test")
                .build());
        OpenJiuwenCapabilityInstaller.Installation installation =
                new OpenJiuwenCapabilityInstaller().install(
                        cleanAgent,
                        provider(new AtomicReference<>(), taskRequests),
                        "reference-bank-provider",
                        defaults(),
                        pack,
                        true);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) installation.tool(TRANSFER_TOOL).invoke(Map.of(
                    OpenJiuwenCapabilityInstaller.TASK_HANDLE_ARGUMENT, "missing-handle",
                    OpenJiuwenCapabilityInstaller.CONFIRMATION_ARGUMENT, "确认提交"));
            assertEquals("REJECTED", output.get("status"));
            assertEquals("FINANCE_TASK_HANDLE_NOT_FOUND", output.get("errorCode"));
            assertTrue(taskRequests.isEmpty());
        } finally {
            installation.close();
        }
    }

    private static FinanceProvider provider(
            AtomicReference<CapabilityRequestV1> queryRequest,
            List<CapabilityRequestV1> taskRequests) {
        return new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                queryRequest.set(request);
                return result(request, CapabilityResultV1.Status.COMPLETED,
                        "账户余额查询完成。",
                        Map.of("accounts", List.of(Map.of(
                                "accountId", "acct-001",
                                "availableBalance", 1000.00))),
                        null);
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                taskRequests.add(request);
                if (request.confirmation() == null) {
                    return result(request, CapabilityResultV1.Status.NEED_CONFIRMATION,
                            "请确认向王先生转账0.01元。",
                            Map.of("preflight", "PASSED"),
                            new CapabilityResultV1.TaskState(
                                    "bank-task-001", "WAITING_CONFIRMATION", true,
                                    Map.of("preflightId", "preflight-001")));
                }
                return result(request, CapabilityResultV1.Status.COMPLETED,
                        "转账已提交。",
                        Map.of("transactionId", "txn-001"),
                        new CapabilityResultV1.TaskState(
                                "bank-task-001", "COMPLETED", false, request.continuation()));
            }
        };
    }

    private static CapabilityResultV1 result(
            CapabilityRequestV1 request,
            CapabilityResultV1.Status status,
            String content,
            Map<String, Object> data,
            CapabilityResultV1.TaskState task) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                status,
                content,
                data,
                List.of(),
                status == CapabilityResultV1.Status.NEED_CONFIRMATION
                        ? List.of("CONFIRM_TASK")
                        : List.of(),
                "reference-bank",
                Instant.parse("2026-09-03T02:00:00Z"),
                task,
                null);
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults defaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "customer-001",
                "customer-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "openjiuwen-clean-example",
                "RETAIL_BANKING",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
    }

    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("capability-packs"))
                    && Files.isDirectory(current.resolve("integrations/openjiuwen-java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
