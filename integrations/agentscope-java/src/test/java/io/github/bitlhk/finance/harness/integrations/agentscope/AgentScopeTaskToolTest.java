package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeTaskToolTest {
    private AgentScopeTaskTool tool() {
        return new AgentScopeTaskTool("synthetic.task", "synthetic_task", "Synthetic task",
                Map.of("type", "object", "properties", Map.of("amount", Map.of("type", "number")),
                        "required", List.of("amount"), "additionalProperties", false));
    }
    private ToolCallParam parameter(Map<String, Object> input, AgentScopeTaskTool.Access access) {
        var builder = ToolCallParam.builder().input(input).toolUseBlock(ToolUseBlock.builder()
                .id("native-call-1").name("synthetic_task").input(input).build());
        if (access != null) builder.runtimeContext(RuntimeContext.builder()
                .sessionId("synthetic-session").userId("synthetic-owner")
                .put(AgentScopeTaskTool.Access.class, access).build());
        return builder.build();
    }
    @Test void missingTrustedAccessIsRejected() {
        assertThrows(SecurityException.class, () -> tool().callAsync(parameter(Map.of("amount", 1), null)).block());
    }
    @Test void invalidInputNeverReachesExecution() {
        var calls = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> tool().callAsync(parameter(Map.of("amount", "bad"),
                (capability, id, input) -> { calls.incrementAndGet(); return Map.of(); })).block());
        assertEquals(0, calls.get());
    }
    @Test void nativeCallIdentityAndArgumentsReachTrustedAccess() {
        var calls = new AtomicInteger();
        var input = Map.<String, Object>of("amount", 1, "confirmationText", "confirm");
        assertNotNull(tool().callAsync(parameter(input, (capability, id, arguments) -> {
            assertEquals("synthetic.task", capability);
            assertEquals("native-call-1", id);
            assertEquals(input, arguments);
            calls.incrementAndGet();
            return Map.of("status", "NEED_USER", "requiresUserAction", true);
        })).block());
        assertEquals(1, calls.get());
        assertFalse(tool().isReadOnly());
    }
    @Test void continuationHandleDoesNotRequireOriginalParameters() {
        assertNotNull(tool().callAsync(parameter(Map.of("financeTaskHandle", "trusted-state-reference"),
                (capability, id, input) -> Map.of("status", "NEED_USER"))).block());
    }
}
