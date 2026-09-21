package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.execution.TaskToolSchema;
import java.util.Map;
import java.util.Objects;
import reactor.core.publisher.Mono;

/** Native TASK projection. The authenticated Host supplies access through RuntimeContext. */
public final class AgentScopeTaskTool implements AgentTool {
    public interface Access {
        Map<String, Object> invoke(String capabilityId, String toolCallId, Map<String, Object> arguments);
    }
    private final String capabilityId, name, description;
    private final Map<String, Object> schema;
    private final QueryInputValidator validator;
    public AgentScopeTaskTool(String capabilityId, String name, String description, Map<String, Object> inputSchema) {
        this.capabilityId = Objects.requireNonNull(capabilityId); this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description); schema = TaskToolSchema.withControls(inputSchema);
        validator = new QueryInputValidator(schema);
    }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getParameters() { return schema; }
    public boolean isReadOnly() { return false; }
    public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
        return Mono.fromCallable(() -> {
            Access access = parameter.getRuntimeContext() == null ? null : parameter.getRuntimeContext().get(Access.class);
            if (access == null) throw new SecurityException("TRUSTED_TASK_ACCESS_REQUIRED");
            validator.validate(parameter.getInput());
            var response = access.invoke(capabilityId, parameter.getToolUseBlock().getId(), parameter.getInput());
            return ToolResultBlock.of(TextBlock.builder().text(HarnessJson.mapper().writeValueAsString(response)).build());
        });
    }
}
