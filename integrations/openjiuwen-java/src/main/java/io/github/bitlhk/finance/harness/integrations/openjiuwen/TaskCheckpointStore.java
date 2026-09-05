package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Host-owned storage. The lock must serialize all processes sharing this store. Writes are immediate. */
public interface TaskCheckpointStore {
    <T> T locked(Function<Access, T> action);

    interface Access {
        SavedTask get(String handle);
        void put(String handle, SavedTask task);
    }

    record SavedTask(
            String scope,
            String capabilityId,
            String taskId,
            Map<String, Object> arguments,
            Map<String, Object> checkpoint,
            OpenJiuwenExecutionV1 terminalResult) {
        public SavedTask {
            arguments = OpenJiuwenFinanceBridge.freeze(arguments);
            checkpoint = OpenJiuwenFinanceBridge.freeze(checkpoint);
        }
    }

    /** Development default; hosts needing restart recovery must supply durable storage. */
    final class Memory implements TaskCheckpointStore {
        private final Map<String, SavedTask> tasks = new HashMap<>();

        @Override
        public synchronized <T> T locked(Function<Access, T> action) {
            return action.apply(new Access() {
                public SavedTask get(String handle) { return tasks.get(handle); }
                public void put(String handle, SavedTask task) { tasks.put(handle, task); }
            });
        }
    }
}
