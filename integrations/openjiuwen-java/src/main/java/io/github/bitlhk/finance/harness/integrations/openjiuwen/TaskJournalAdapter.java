package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.execution.TaskCoordination;
import java.util.Objects;
import java.util.function.Function;

/** Projects a new Host-owned neutral journal onto the legacy installation API. */
public final class TaskJournalAdapter implements TaskCheckpointStore {
    private final TaskCoordination.Journal journal;
    public TaskJournalAdapter(TaskCoordination.Journal journal) { this.journal = Objects.requireNonNull(journal); }
    public <T> T locked(Function<Access, T> operation) {
        return journal.locked(delegate -> operation.apply(new Access() {
            public SavedTask get(String handle) {
                var task = delegate.get(handle);
                return task == null ? null : new SavedTask(task.scope(), task.capabilityId(), task.taskId(), task.arguments(), task.checkpoint(),
                        InstalledTaskController.terminal(task));
            }
            public void put(String handle, SavedTask task) {
                try {
                    delegate.put(handle, new TaskCoordination.Saved(task.scope(), task.capabilityId(), task.taskId(), task.arguments(), task.checkpoint(),
                            task.terminalResult() == null ? null : InstalledTaskController.result(task.terminalResult(), "")));
                } catch (TaskCoordination.NewTaskRejected rejected) { throw new NewTaskRejected(); }
            }
        }));
    }
}
