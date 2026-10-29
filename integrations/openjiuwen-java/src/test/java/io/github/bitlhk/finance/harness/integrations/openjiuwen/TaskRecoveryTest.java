package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskRecoveryTest {
    private static final String CAP = "transfer.internal.create";
    private static final String HANDLE = "financeTaskHandle";
    @TempDir Path temp;

    @Test void posixCheckpointKeepsOwnerOnlyDirectoryAndPayload() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(temp.getFileSystem().supportedFileAttributeViews().contains("posix"));
        String handle = start(controller(new FileTaskCheckpointStore(temp), new Bank(), "session"));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"),
                java.nio.file.Files.getPosixFilePermissions(temp));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                java.nio.file.Files.getPosixFilePermissions(temp.resolve(handle + ".json")));
    }

    @Test void filesFromOldCoordinatorResumeWithoutChangingTaskIdentity() throws Exception {
        var json = io.github.bitlhk.finance.harness.core.HarnessJson.mapper();
        var resource = getClass().getResourceAsStream("/task-checkpoints/legacy-89d0ad1.json");
        assertNotNull(resource);
        com.fasterxml.jackson.databind.JsonNode fixtures;
        try (resource) { fixtures = json.readTree(resource); }
        assertEquals("89d0ad1", fixtures.path("sourceCommit").asText());
        String handle = fixtures.path("handle").asText();
        for (String state : List.of("pending", "unknown", "terminal")) {
            Path directory = temp.resolve(state);
            var store = new FileTaskCheckpointStore(directory);
            String bytes = fixtures.path(state).asText();
            java.nio.file.Files.writeString(directory.resolve(handle + ".json"), bytes);
            Bank bank = new Bank();
            var restored = controller(store, bank, "legacy-session");
            if (state.equals("pending")) {
                assertEquals(json.readTree(bytes).path("checkpoint").path(OpenJiuwenFinanceBridge.CK_BINDING).asText(),
                        restored.confirmationBinding(handle));
                assertEquals("COMPLETED", restored.confirm(handle, approval(restored, handle)).get("status"));
                assertEquals(1, bank.writes.get());
            } else if (state.equals("unknown")) {
                bank.writes.set(1);
                assertThrows(IllegalStateException.class, () -> restored.confirmationBinding(handle));
                assertEquals("COMPLETED", restored.invoke(CAP, Map.of(HANDLE, handle)).get("status"));
                assertEquals(1, bank.writes.get());
                assertNull(bank.requests.getFirst().confirmation());
                assertEquals(true, bank.requests.getFirst().continuation().get("submitOutcomeUnknown"));
            } else {
                var returned = restored.invoke(CAP, Map.of(HANDLE, handle));
                var oldTerminal = json.readTree(bytes).path("terminalResult");
                for (String field : List.of("status", "content", "data", "evidence"))
                    assertEquals(oldTerminal.path(field), json.valueToTree(returned.get(field)), field);
                assertEquals(false, returned.get("requiresUserAction"));
                assertTrue(bank.requests.isEmpty());
                assertEquals(bytes, java.nio.file.Files.readString(directory.resolve(handle + ".json")));
            }
            if (!bank.requests.isEmpty()) {
                assertEquals("bank-task-1", bank.requests.getFirst().taskRef());
                assertEquals(json.readTree(bytes).path("checkpoint").path(OpenJiuwenFinanceBridge.CK_IDEMPOTENCY).asText(),
                        bank.requests.getFirst().idempotencyKey());
            }
        }
    }

    @Test
    void exactAmountSurvivesRecoveryApprovalAndTerminalCache() throws Exception {
        Bank bank = new Bank();
        var amount = new java.math.BigDecimal("90071992547409.91");
        var first = controller(new FileTaskCheckpointStore(temp), bank, "session");
        String handle = (String)first.invoke(CAP, Map.of("amount", amount, "beneficiaryId", "beneficiary-1")).get(HANDLE);
        String binding = first.confirmationBinding(handle);
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "session");
        assertEquals(binding, restarted.confirmationBinding(handle));
        assertEquals(handle, restarted.invoke(CAP, Map.of(HANDLE, handle, "amount", amount)).get(HANDLE));
        assertEquals("COMPLETED", restarted.confirm(handle, approval(restarted, handle)).get("status"));
        assertEquals("COMPLETED", controller(new FileTaskCheckpointStore(temp), bank, "session")
                .invoke(CAP, Map.of(HANDLE, handle, "amount", amount)).get("status"));
        assertEquals(1, bank.writes.get());
        assertTrue(bank.requests.stream().allMatch(r -> amount.compareTo(new java.math.BigDecimal(r.parameters().get("amount").toString())) == 0));
    }

    @Test
    void modelStringsIncludingAffirmativeTextCannotAuthorizeWrites() {
        Bank bank = new Bank();
        var controller = controller(new TaskCheckpointStore.Memory(), bank, "user-session");
        String handle = start(controller);
        for (String text : List.of("", "不确认", "查询进度", "确认提交")) {
            assertEquals("NEED_USER", controller.invoke(CAP, Map.of(HANDLE, handle,
                    "confirmationText", text, "confirmedCapabilityId", CAP, "query", "确认")).get("status"));
        }
        assertEquals(0, bank.writes.get());
        assertTrue(bank.requests.stream().allMatch(request -> request.confirmation() == null));
    }

    @Test
    void bindingRejectsWrongExpiredAndFutureApprovalsAndChangedParameters() {
        Bank bank = new Bank();
        var controller = controller(new TaskCheckpointStore.Memory(), bank, "user-session");
        String handle = start(controller);
        String binding = controller.confirmationBinding(handle);
        Instant now = Instant.now();
        for (HostTaskConfirmation approval : List.of(
                new HostTaskConfirmation("wrong", "0".repeat(64), now, now.plusSeconds(30)),
                new HostTaskConfirmation("expired", binding, now.minusSeconds(60), now.minusSeconds(1)),
                new HostTaskConfirmation("future", binding, now.plusSeconds(60), now.plusSeconds(90)))) {
            assertEquals("HOST_CONFIRMATION_INVALID", controller.confirm(handle, approval).get("errorCode"));
        }
        for (Map<String, Object> change : List.<Map<String, Object>>of(
                Map.of(HANDLE, handle, "amount", 20), Map.of(HANDLE, handle, "beneficiaryId", "other"))) {
            assertEquals("TASK_PARAMETERS_CHANGED", controller.invoke(CAP, change).get("errorCode"));
        }
        assertEquals(0, bank.writes.get());
        assertEquals(1, bank.requests.size());
    }

    @Test
    void negativeHostDecisionIsTerminalAndCannotBeOverriddenByLaterApproval() {
        Bank bank = new Bank();
        var controller = controller(new TaskCheckpointStore.Memory(), bank, "user-session");
        String handle = start(controller);
        HostTaskConfirmation approval = approval(controller, handle);
        assertEquals("HOST_DECLINED", controller.decline(handle).get("errorCode"));
        assertEquals("HOST_DECLINED", controller.confirm(handle, approval).get("errorCode"));
        assertEquals(0, bank.writes.get());
    }

    @Test
    void restartAndTemporaryObservationFailurePreserveHandleIdentityAndKey() throws Exception {
        Bank bank = new Bank();
        var first = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(first);
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        bank.observationUnavailable = true;
        assertEquals(handle, restarted.invoke(CAP, Map.of(HANDLE, handle)).get(HANDLE));
        bank.observationUnavailable = false;
        assertEquals(handle, restarted.invoke(CAP, Map.of(HANDLE, handle)).get(HANDLE));
        assertEquals("COMPLETED", restarted.confirm(handle, approval(restarted, handle)).get("status"));
        assertEquals(1, bank.writes.get());
        assertEquals(1, bank.requests.stream().map(CapabilityRequestV1::idempotencyKey).distinct().count());
        assertTrue(bank.requests.stream().skip(1).allMatch(r -> "bank-task-1".equals(r.taskRef())));
        assertEquals(1, bank.requests.stream().map(r -> r.context().sessionRef()).distinct().count());
    }

    @Test
    void lostSubmitResponseSurvivesRestartAndNeverReplaysApproval() throws Exception {
        Bank bank = new Bank();
        var controller = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(controller);
        bank.loseSubmitResponse = true;
        assertEquals(handle, controller.confirm(handle, approval(controller, handle)).get(HANDLE));
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        bank.observationUnavailable = true;
        assertEquals(handle, restarted.invoke(CAP, Map.of(HANDLE, handle)).get(HANDLE));
        bank.observationUnavailable = false;
        assertEquals("COMPLETED", restarted.invoke(CAP, Map.of(HANDLE, handle)).get("status"));
        assertEquals(1, bank.writes.get());
        assertTrue(bank.requests.getLast().continuation().containsKey("submitOutcomeUnknown"));
        assertNull(bank.requests.getLast().confirmation());
    }

    @Test
    void reauthenticationWithoutTaskStatePreservesUncertainTaskAcrossRestart() throws Exception {
        Bank bank = new Bank();
        var first = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(first);
        bank.loseSubmitResponse = true;
        first.confirm(handle, approval(first, handle));
        bank.reauthenticationRequired = true;
        assertEquals(handle, first.invoke(CAP, Map.of(HANDLE, handle)).get(HANDLE));
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        assertThrows(IllegalStateException.class, () -> restarted.confirmationBinding(handle));
        bank.reauthenticationRequired = false;
        assertEquals("COMPLETED", restarted.invoke(CAP, Map.of(HANDLE, handle)).get("status"));
        assertEquals("bank-task-1", bank.requests.getLast().taskRef());
        assertEquals(1, bank.requests.stream().map(CapabilityRequestV1::idempotencyKey).distinct().count());
        assertTrue(bank.requests.getLast().continuation().containsKey("submitOutcomeUnknown"));
        assertNull(bank.requests.getLast().confirmation());
        assertEquals(1, bank.writes.get());
    }

    @Test
    void processingReplyKeepsProviderContinuationAlongsideRecoveryMarker() throws Exception {
        Bank bank = new Bank();
        var first = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(first);
        bank.replyProcessing = true;
        assertEquals(handle, first.confirm(handle, approval(first, handle)).get(HANDLE));
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        assertEquals("COMPLETED", restarted.invoke(CAP, Map.of(HANDLE, handle)).get("status"));
        assertEquals("new-bank-cursor", bank.requests.getLast().continuation().get("resumeToken"));
        assertTrue(bank.requests.getLast().continuation().containsKey("submitOutcomeUnknown"));
        assertNull(bank.requests.getLast().confirmation());
        assertEquals(1, bank.writes.get());
    }

    @Test
    void crashBetweenBankCommitAndCheckpointWriteUsesPersistedUncertainty() throws Exception {
        Bank bank = new Bank();
        var first = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(first);
        HostTaskConfirmation approval = approval(first, handle);
        bank.crashAfterSubmit = true;
        assertThrows(AssertionError.class, () -> first.confirm(handle, approval));
        var restarted = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        assertThrows(IllegalStateException.class, () -> restarted.confirmationBinding(handle));
        assertEquals("COMPLETED", restarted.invoke(CAP, Map.of(HANDLE, handle)).get("status"));
        assertTrue(bank.requests.getLast().continuation().containsKey("submitOutcomeUnknown"));
        assertNull(bank.requests.getLast().confirmation());
        assertEquals(1, bank.writes.get());
    }

    @Test
    void concurrentDuplicateApprovalsReturnOneTerminalReceiptAndOneWrite() throws Exception {
        Bank bank = new Bank();
        var first = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        var second = controller(new FileTaskCheckpointStore(temp), bank, "user-session");
        String handle = start(first);
        HostTaskConfirmation approval = approval(first, handle);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = pool.submit(() -> first.confirm(handle, approval));
            var right = pool.submit(() -> second.confirm(handle, approval));
            assertEquals(left.get(), right.get());
        }
        assertEquals(1, bank.writes.get());
    }

    @Test
    void handlesCannotCrossSessionOrCapabilityAndTamperedFilesDoNotRestartTasks() throws Exception {
        Bank bank = new Bank();
        var store = new FileTaskCheckpointStore(temp);
        var first = controller(store, bank, "session-a");
        String handle = start(first);
        var other = controller(store, bank, "session-b");
        assertEquals("FINANCE_TASK_HANDLE_NOT_FOUND", other.invoke(CAP, Map.of(HANDLE, handle)).get("errorCode"));
        assertEquals("TASK_CAPABILITY_MISMATCH", first.invoke("other-capability", Map.of(HANDLE, handle)).get("errorCode"));
        assertEquals("FINANCE_TASK_HANDLE_NOT_FOUND", first.invoke(CAP, Map.of(HANDLE, "../escape")).get("errorCode"));
        java.nio.file.Files.writeString(temp.resolve(handle + ".json"), "broken checkpoint");
        assertThrows(java.io.UncheckedIOException.class, () -> first.invoke(CAP, Map.of(HANDLE, handle)));
        assertEquals(1, bank.requests.size());
    }

    private static String start(InstalledTaskController controller) {
        return (String) controller.invoke(CAP, Map.of("amount", 10, "beneficiaryId", "beneficiary-1")).get(HANDLE);
    }

    private static HostTaskConfirmation approval(InstalledTaskController controller, String handle) {
        Instant now = Instant.now();
        return new HostTaskConfirmation("host-event-1", controller.confirmationBinding(handle), now, now.plusSeconds(60));
    }

    private static InstalledTaskController controller(TaskCheckpointStore store, Bank bank, String scope) {
        var controller = new InstalledTaskController("agent-1", scope, store);
        var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults("user-1", "user-1",
                FinanceContextV1.PrincipalType.CUSTOMER, "mobile", "TRANSFER",
                FinanceContextV1.AuthenticationLevel.STRONG, Set.of());
        controller.register(new FinanceCapabilityPackV1.Capability(CAP, "transfer", "Transfer", "Transfer",
                FinanceCapabilityPackV1.Operation.TASK, Map.of("type", "object"), Set.of()),
                new OpenJiuwenFinanceBridge(bank, "bank", defaults, Set.of(CAP), true));
        return controller;
    }

    private static final class Bank implements FinanceProvider {
        final List<CapabilityRequestV1> requests = new ArrayList<>();
        final AtomicInteger writes = new AtomicInteger();
        boolean observationUnavailable;
        boolean reauthenticationRequired;
        boolean replyProcessing;
        boolean loseSubmitResponse;
        boolean crashAfterSubmit;
        public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new UnsupportedOperationException(); }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            requests.add(request);
            if (observationUnavailable) throw new IllegalStateException("temporary outage");
            if (reauthenticationRequired) {
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                        request.context().requestId(), request.context().traceId(), CapabilityResultV1.Status.NEED_USER,
                        "Reauthenticate before checking this task", Map.of(), List.of(), List.of(),
                        "bank", Instant.now(), null, null);
            }
            if (request.confirmation() != null) {
                writes.incrementAndGet();
                if (crashAfterSubmit) throw new AssertionError("simulated process termination after bank commit");
                if (loseSubmitResponse) throw new IllegalStateException("acknowledgement lost after commit");
                if (replyProcessing) {
                    return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                            request.context().requestId(), request.context().traceId(), CapabilityResultV1.Status.PROCESSING,
                            "Bank processing", Map.of(), List.of(), List.of(), "bank", Instant.now(),
                            new CapabilityResultV1.TaskState("bank-task-1", "PROCESSING", false,
                                    Map.of("resumeToken", "new-bank-cursor")), null);
                }
            }
            boolean completed = writes.get() > 0;
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                    request.context().requestId(), request.context().traceId(),
                    completed ? CapabilityResultV1.Status.COMPLETED : CapabilityResultV1.Status.NEED_CONFIRMATION,
                    "Transfer 10 to beneficiary-1", Map.of("amount", 10), List.of(), List.of(), "bank", Instant.now(),
                    new CapabilityResultV1.TaskState("bank-task-1", completed ? "COMPLETED" : "CREATED",
                            !completed, request.continuation()), null);
        }
    }
}
