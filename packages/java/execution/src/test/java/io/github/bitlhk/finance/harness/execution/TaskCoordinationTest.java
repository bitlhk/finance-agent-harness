package io.github.bitlhk.finance.harness.execution;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskCoordinationTest {
    private static final String CAP = "example.task";
    private static final String BINDING = "a".repeat(64);
    static final class Journal implements TaskCoordination.Journal {
        final Map<String, TaskCoordination.Saved> saved = new HashMap<>();
        int puts, failAt; boolean rejectNew;
        public synchronized <T> T locked(Function<TaskCoordination.Access, T> action) {
            return action.apply(new TaskCoordination.Access() {
                public TaskCoordination.Saved get(String handle) { return saved.get(handle); }
                public void put(String handle, TaskCoordination.Saved task) {
                    if (++puts == failAt) throw new IllegalStateException("DISK_FAILURE");
                    if (rejectNew && !saved.containsKey(handle)) throw new TaskCoordination.NewTaskRejected();
                    saved.put(handle, task);
                }
            });
        }
    }
    static final class Bank implements TaskCoordination.Backend {
        final Journal journal;
        int calls, writes, checks; boolean authorized = true, loseResponse;
        Bank(Journal journal) { this.journal = journal; }
        public Map<String, Object> parameters(Map<String, Object> args) { return TaskProtocol.parameters(args); }
        public Map<String, Object> initialCheckpoint(String taskId, Map<String, Object> args) { return TaskProtocol.initial(CAP, taskId, "owner", args); }
        public void authorize(TaskCoordination.Saved task) { checks++; if (!authorized) throw new SecurityException("REVOKED"); }
        public Map<String, Object> denied(TaskCoordination.Saved task) { return Map.of("status", "REJECTED", "errorCode", "AUTHORIZATION_DENIED"); }
        public Map<String, Object> declined(TaskCoordination.Saved task) { return Map.of("status", "REJECTED", "errorCode", "HOST_DECLINED"); }
        public TaskCoordination.Outcome execute(TaskCoordination.Saved task, TaskCoordination.Approval approval) {
            calls++;
            var durable = journal.saved.values().iterator().next();
            assertFalse(durable.checkpoint().containsKey(CK_BINDING));
            if (approval != null) {
                assertEquals(true, ((Map<?, ?>)durable.checkpoint().get(CK_CONTINUATION)).get("submitOutcomeUnknown"));
                writes++;
                if (loseResponse) throw new IllegalStateException("CONNECTION_LOST");
                return new TaskCoordination.Outcome(Map.of("status", "COMPLETED"), Map.of(), false);
            }
            if (task.checkpoint().get(CK_CONTINUATION) instanceof Map<?, ?> continuation
                    && Boolean.TRUE.equals(continuation.get("submitOutcomeUnknown"))) {
                return new TaskCoordination.Outcome(Map.of("status", "COMPLETED"), Map.of(), false);
            }
            var checkpoint = new HashMap<>(task.checkpoint());
            checkpoint.put(CK_TASK_REF, "bank-task"); checkpoint.put(CK_BINDING, BINDING);
            return new TaskCoordination.Outcome(Map.of("status", "NEED_USER"), checkpoint, false);
        }
    }
    private TaskCoordination coordinator(Journal store, Bank bank, String scope) {
        var tasks = new TaskCoordination(scope, store); tasks.register(CAP, bank); return tasks;
    }
    private String start(TaskCoordination tasks) { return (String)tasks.invoke(CAP, Map.of("amount", 10)).get(TaskCoordination.HANDLE_ARGUMENT); }
    private TaskCoordination.Approval approval() {
        var now = Instant.now(); return new TaskCoordination.Approval("event", BINDING, now.minusSeconds(1), now.plusSeconds(60));
    }
    @Test void confirmationPersistsUncertaintyAndRepeatedApprovalDoesNotResubmit() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope");
        var handle = start(tasks); assertEquals(BINDING, tasks.confirmationBinding(handle));
        assertEquals("COMPLETED", tasks.confirm(handle, approval()).get("status"));
        assertEquals("COMPLETED", tasks.confirm(handle, approval()).get("status"));
        assertEquals(1, bank.writes); assertEquals(2, bank.calls);
    }
    @Test void crashAfterSubmitRestartsByObservationNotApproval() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope");
        var handle = start(tasks); bank.loseResponse = true;
        assertThrows(IllegalStateException.class, () -> tasks.confirm(handle, approval()));
        var restarted = coordinator(store, bank, "scope");
        assertThrows(IllegalStateException.class, () -> restarted.confirmationBinding(handle));
        assertEquals("COMPLETED", restarted.invoke(CAP, Map.of(TaskCoordination.HANDLE_ARGUMENT, handle)).get("status"));
        assertEquals(1, bank.writes);
    }
    @Test void initialAndAttemptWritesFailBeforeProvider() {
        for (int fail : new int[]{1, 2}) {
            var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); store.failAt = fail;
            assertThrows(IllegalStateException.class, () -> start(tasks)); assertEquals(0, bank.calls);
        }
    }
    @Test void uncertainWriteFailurePreventsSubmission() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); var handle = start(tasks);
        store.failAt = store.puts + 2;
        assertThrows(IllegalStateException.class, () -> tasks.confirm(handle, approval())); assertEquals(0, bank.writes);
    }
    @Test void terminalWriteFailureRetainsDurableUncertainty() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); var handle = start(tasks);
        store.failAt = store.puts + 3;
        assertThrows(IllegalStateException.class, () -> tasks.confirm(handle, approval())); assertEquals(1, bank.writes);
        assertEquals("COMPLETED", tasks.invoke(CAP, Map.of(TaskCoordination.HANDLE_ARGUMENT, handle)).get("status"));
        assertEquals(1, bank.writes);
    }
    @Test void terminalCacheStillRequiresCurrentAuthorization() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); var handle = start(tasks);
        tasks.confirm(handle, approval()); bank.authorized = false;
        assertEquals("AUTHORIZATION_DENIED", tasks.invoke(CAP, Map.of(TaskCoordination.HANDLE_ARGUMENT, handle)).get("errorCode"));
        assertEquals(2, bank.calls);
    }
    @Test void scopeChangesCannotReadOrResumeTasks() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); var handle = start(tasks);
        var other = coordinator(store, bank, "other");
        assertThrows(IllegalArgumentException.class, () -> other.confirmationBinding(handle));
        assertEquals("FINANCE_TASK_HANDLE_NOT_FOUND", other.invoke(CAP, Map.of(TaskCoordination.HANDLE_ARGUMENT, handle)).get("errorCode"));
        assertEquals(1, bank.calls);
    }
    @Test void admissionRejectionIsNotAStorageFailure() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); store.rejectNew = true;
        assertEquals("TASK_PENDING_USE_ORIGINAL", tasks.invoke(CAP, Map.of()).get("errorCode")); assertEquals(0, bank.calls);
    }
    @Test void declineIsTerminalAndFrozenParametersCannotChange() {
        var store = new Journal(); var bank = new Bank(store); var tasks = coordinator(store, bank, "scope"); var handle = start(tasks);
        assertEquals("TASK_PARAMETERS_CHANGED", tasks.invoke(CAP, Map.of(TaskCoordination.HANDLE_ARGUMENT, handle, "amount", 20)).get("errorCode"));
        tasks.decline(handle);
        assertEquals("HOST_DECLINED", tasks.confirm(handle, approval()).get("errorCode")); assertEquals(0, bank.writes);
    }
}
