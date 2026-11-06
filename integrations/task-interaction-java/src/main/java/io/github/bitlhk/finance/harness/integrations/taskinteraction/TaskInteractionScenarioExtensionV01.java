package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.execution.TaskCoordination;
import java.util.Set;

/** Optional scenario port. The existing Host owns its task Journal and one-time approval store. */
public final class TaskInteractionScenarioExtensionV01 implements ManagedScenarioExtensionV01 {
    public static final String ID="original-task-review-managed.v1";
    @Override public String id(){return ID;}
    @Override public Set<String> actions(){return TaskInteractionCommands.ACTIONS;}
    @Override public Session open(Context context){throw new IllegalStateException("HOST_TASK_JOURNAL_REQUIRED");}
    public Session openWithTaskJournal(Context context,TaskCoordination.Journal journal) throws Exception {
        return new TaskInteractionSession(context,journal);
    }
    public String hostTaskScopeKey(Context context) throws Exception {
        try(var scope=new TaskInteractionSourceScope(context)){
            var lease=scope.current();
            return io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.fingerprint(java.util.Map.of(
                "owner",context.ownerRef(),"customer",lease.subjectRef(),"task",lease.taskRef(),"configuration",scope.factory.configurationDigests(),"build",context.buildId()));
        }
    }
}
