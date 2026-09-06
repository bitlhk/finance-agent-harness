package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointPrecisionTest {
    @TempDir Path directory;

    @Test void roundtripPreservesNestedParametersAndTerminalData() throws Exception {
        var amount = new BigDecimal("90071992547409.91");
        Map<String, Object> params = Map.of("amount", amount, "nested", List.of(Map.of("decimal", new BigDecimal("0.00000001"))),
                "integer", 9007199254740993L, "scaled", new BigDecimal("10.00"));
        var terminal = new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.COMPLETED, "task", "done", params,
                List.of(), "", "", Map.of());
        var task = new TaskCheckpointStore.SavedTask("scope", "transfer", "task", params,
                Map.of(OpenJiuwenFinanceBridge.CK_PARAMETERS, params), terminal);
        String handle = "fh-task-handle-" + UUID.randomUUID();
        var store = new FileTaskCheckpointStore(directory);
        store.locked(access -> {
            access.put(handle, task);
            var restored = access.get(handle);
            assertEquals(amount, restored.arguments().get("amount"));
            assertEquals(OpenJiuwenFinanceBridge.fingerprint(params), OpenJiuwenFinanceBridge.fingerprint(restored.arguments()));
            assertEquals(OpenJiuwenFinanceBridge.fingerprint(task.checkpoint()), OpenJiuwenFinanceBridge.fingerprint(restored.checkpoint()));
            assertEquals(OpenJiuwenFinanceBridge.fingerprint(terminal), OpenJiuwenFinanceBridge.fingerprint(restored.terminalResult()));
            return null;
        });
        assertEquals(amount, new FileTaskCheckpointStore(directory).locked(a -> a.get(handle).arguments().get("amount")));
    }
}
