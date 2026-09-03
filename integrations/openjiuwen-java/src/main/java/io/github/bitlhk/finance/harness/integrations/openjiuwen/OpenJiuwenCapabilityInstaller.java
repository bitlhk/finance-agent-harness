package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Installs a Finance Capability Pack as native tools visible to an OpenJiuwen agent. */
public final class OpenJiuwenCapabilityInstaller {
    public static final String INTEGRATION_ID = "finance-harness-openjiuwen-java";
    public static final String INTEGRATION_VERSION = "0.1.0-SNAPSHOT";
    public static final String TASK_HANDLE_ARGUMENT = "financeTaskHandle";
    public static final String CONFIRMATION_ARGUMENT = "confirmationText";

    public Installation install(
            BaseAgent agent,
            FinanceProvider provider,
            String providerId,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            FinanceCapabilityPackV1 pack,
            boolean simulated) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pack, "pack");
        String agentId = agent.getCard().getId();
        Set<String> capabilityIds = new LinkedHashSet<>();
        pack.capabilities().forEach(capability -> capabilityIds.add(capability.capabilityId()));
        OpenJiuwenFinanceBridge bridge = new OpenJiuwenFinanceBridge(
                provider, providerId, defaults, capabilityIds, simulated);
        Map<String, PendingTask> pendingTasks = new ConcurrentHashMap<>();
        Map<String, Tool> installedTools = new LinkedHashMap<>();
        String promptSection = "finance-capability-pack:" + pack.packId();

        if (agent instanceof ReActAgent reactAgent) {
            reactAgent.addPromptBuilderSection(promptSection, pack.systemPrompt(), 80);
        }

        try {
            for (FinanceCapabilityPackV1.Capability capability : pack.capabilities()) {
                ensureAvailable(agent, capability.toolName());
                Tool tool = tool(agentId, capability, bridge, pendingTasks);
                agent.getAbilityManager().add(tool.getCard());
                installedTools.put(capability.toolName(), tool);
                var registered = Runner.resourceMgr().addTool(tool, List.of(agentId), true);
                if (registered.isError()) {
                    throw new IllegalStateException(
                            "Unable to register OpenJiuwen tool: " + capability.toolName(), registered.getError());
                }
            }
        } catch (RuntimeException exception) {
            uninstall(agent, installedTools.keySet(), agentId);
            removePromptSection(agent, promptSection);
            throw exception;
        }

        List<CapabilityBinding> bindings = pack.capabilities().stream()
                .map(capability -> new CapabilityBinding(
                        capability.capabilityId(), capability.toolName(), capability.operation().name()))
                .toList();
        InstallationReceipt receipt = new InstallationReceipt(
                "fh-install-" + UUID.randomUUID(),
                pack.packId(),
                pack.version(),
                INTEGRATION_ID,
                INTEGRATION_VERSION,
                providerId,
                agentId,
                bindings,
                Instant.now());
        return new Installation(agent, installedTools, pendingTasks, receipt, promptSection);
    }

    private static Tool tool(
            String agentId,
            FinanceCapabilityPackV1.Capability capability,
            OpenJiuwenFinanceBridge bridge,
            Map<String, PendingTask> pendingTasks) {
        Map<String, Object> inputSchema = capability.operation() == FinanceCapabilityPackV1.Operation.TASK
                ? withTaskControls(capability.inputSchema())
                : capability.inputSchema();
        ToolCard card = ToolCard.builder()
                .id(capability.toolName())
                .name(capability.toolName())
                .description(capability.description() + " [capabilityId=" + capability.capabilityId() + "]")
                .inputParams(inputSchema)
                .properties(Map.of(
                        "financeCapabilityId", capability.capabilityId(),
                        "financeOperation", capability.operation().name()))
                .build();
        return new LocalFunction(card, arguments -> capability.operation() == FinanceCapabilityPackV1.Operation.QUERY
                ? query(agentId, capability, bridge, arguments)
                : task(agentId, capability, bridge, pendingTasks, arguments));
    }

    private static Map<String, Object> query(
            String agentId,
            FinanceCapabilityPackV1.Capability capability,
            OpenJiuwenFinanceBridge bridge,
            Map<String, Object> arguments) {
        OpenJiuwenInvocationV1 invocation = new OpenJiuwenInvocationV1(
                "fh-query-" + UUID.randomUUID(),
                capability.capabilityId(),
                agentId,
                capability.requiredScopes(),
                arguments,
                Map.of());
        return toolResult(bridge.query(invocation), "");
    }

    private static Map<String, Object> task(
            String agentId,
            FinanceCapabilityPackV1.Capability capability,
            OpenJiuwenFinanceBridge bridge,
            Map<String, PendingTask> pendingTasks,
            Map<String, Object> arguments) {
        String requestedHandle = text(arguments.get(TASK_HANDLE_ARGUMENT));
        PendingTask pending = requestedHandle.isBlank() ? null : pendingTasks.get(requestedHandle);
        if (!requestedHandle.isBlank() && pending == null) {
            return Map.of(
                    "status", OpenJiuwenExecutionV1.Status.REJECTED.name(),
                    "capabilityId", capability.capabilityId(),
                    "errorCode", "FINANCE_TASK_HANDLE_NOT_FOUND",
                    "content", "当前确认任务已失效，请重新发起。",
                    "requiresUserAction", false);
        }

        String handle = pending == null ? "fh-task-handle-" + UUID.randomUUID() : requestedHandle;
        String taskId = pending == null ? "fh-task-" + UUID.randomUUID() : pending.taskId();
        Map<String, Object> mergedArguments = pending == null
                ? businessArguments(arguments)
                : resumeArguments(pending.businessArguments(), arguments, capability.capabilityId());
        Map<String, Object> checkpoint = pending == null ? Map.of() : pending.checkpoint();
        OpenJiuwenExecutionV1 execution = bridge.executeTask(new OpenJiuwenInvocationV1(
                taskId,
                capability.capabilityId(),
                agentId,
                capability.requiredScopes(),
                mergedArguments,
                checkpoint));

        if (execution.status() == OpenJiuwenExecutionV1.Status.NEED_USER
                && !execution.checkpoint().isEmpty()) {
            Map<String, Object> originalArguments = pending == null
                    ? businessArguments(arguments)
                    : pending.businessArguments();
            pendingTasks.put(handle, new PendingTask(taskId, originalArguments, execution.checkpoint()));
            return toolResult(execution, handle);
        }
        pendingTasks.remove(handle);
        return toolResult(execution, "");
    }

    private static Map<String, Object> businessArguments(Map<String, Object> arguments) {
        Map<String, Object> business = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        business.remove(TASK_HANDLE_ARGUMENT);
        business.remove(CONFIRMATION_ARGUMENT);
        return Map.copyOf(business);
    }

    private static Map<String, Object> resumeArguments(
            Map<String, Object> original,
            Map<String, Object> current,
            String capabilityId) {
        Map<String, Object> merged = new LinkedHashMap<>(original);
        if (current != null) merged.putAll(current);
        merged.put("confirmedCapabilityId", capabilityId);
        merged.remove(TASK_HANDLE_ARGUMENT);
        return Map.copyOf(merged);
    }

    private static Map<String, Object> toolResult(OpenJiuwenExecutionV1 execution, String handle) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.status().name());
        result.put("content", execution.content());
        result.put("data", execution.data());
        result.put("evidence", execution.evidence());
        result.put("requiresUserAction", execution.status() == OpenJiuwenExecutionV1.Status.NEED_USER);
        if (!execution.errorCode().isBlank()) result.put("errorCode", execution.errorCode());
        if (!execution.question().isBlank()) result.put("question", execution.question());
        if (!handle.isBlank()) result.put(TASK_HANDLE_ARGUMENT, handle);
        return Map.copyOf(result);
    }

    private static Map<String, Object> withTaskControls(Map<String, Object> source) {
        Map<String, Object> schema = new LinkedHashMap<>(source);
        Map<String, Object> properties = new LinkedHashMap<>();
        Object configured = source.get("properties");
        if (configured instanceof Map<?, ?> map) {
            map.forEach((key, value) -> properties.put(String.valueOf(key), value));
        }
        properties.put(TASK_HANDLE_ARGUMENT, Map.of(
                "type", "string",
                "description", "工具要求确认后返回的续办句柄；首次调用不要提供。"));
        properties.put(CONFIRMATION_ARGUMENT, Map.of(
                "type", "string",
                "description", "用户明确确认时的原始确认文本；不得代替用户填写。"));
        schema.put("properties", Map.copyOf(properties));
        Object configuredRequired = schema.remove("required");
        if (configuredRequired instanceof List<?> required && !required.isEmpty()) {
            schema.put("anyOf", List.of(
                    Map.of("required", List.copyOf(required)),
                    Map.of("required", List.of(TASK_HANDLE_ARGUMENT, CONFIRMATION_ARGUMENT))));
        }
        return Map.copyOf(schema);
    }

    private static void ensureAvailable(BaseAgent agent, String toolName) {
        if (agent.getAbilityManager().get(toolName) != null) {
            throw new IllegalStateException("Agent already contains tool: " + toolName);
        }
        if (Runner.resourceMgr().getTool(toolName) != null) {
            throw new IllegalStateException("Runtime already contains tool: " + toolName);
        }
    }

    private static void uninstall(BaseAgent agent, Set<String> toolNames, String agentId) {
        for (String toolName : toolNames) {
            agent.getAbilityManager().remove(toolName);
            Runner.resourceMgr().removeTool(toolName, List.of(agentId), TagMatchStrategy.ALL, true);
        }
    }

    private static void removePromptSection(BaseAgent agent, String sectionName) {
        if (agent instanceof ReActAgent reactAgent) {
            reactAgent.getSystemPromptBuilder().removeSection(sectionName);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private record PendingTask(
            String taskId,
            Map<String, Object> businessArguments,
            Map<String, Object> checkpoint) {
        private PendingTask {
            businessArguments = Map.copyOf(businessArguments);
            checkpoint = Map.copyOf(checkpoint);
        }
    }

    public record CapabilityBinding(String capabilityId, String toolName, String operation) {
    }

    public record InstallationReceipt(
            String installationId,
            String packId,
            String packVersion,
            String integrationId,
            String integrationVersion,
            String providerId,
            String agentId,
            List<CapabilityBinding> capabilities,
            Instant installedAt) {
        public InstallationReceipt {
            capabilities = List.copyOf(capabilities);
        }
    }

    public static final class Installation implements AutoCloseable {
        private final BaseAgent agent;
        private final Map<String, Tool> tools;
        private final Map<String, PendingTask> pendingTasks;
        private final InstallationReceipt receipt;
        private final String promptSection;
        private boolean closed;

        private Installation(
                BaseAgent agent,
                Map<String, Tool> tools,
                Map<String, PendingTask> pendingTasks,
                InstallationReceipt receipt,
                String promptSection) {
            this.agent = agent;
            this.tools = Map.copyOf(tools);
            this.pendingTasks = pendingTasks;
            this.receipt = receipt;
            this.promptSection = promptSection;
        }

        public InstallationReceipt receipt() {
            return receipt;
        }

        public Tool tool(String toolName) {
            Tool tool = tools.get(toolName);
            if (tool == null) throw new IllegalArgumentException("Tool is not installed: " + toolName);
            return tool;
        }

        public Set<String> toolNames() {
            return tools.keySet();
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            uninstall(agent, tools.keySet(), receipt.agentId());
            removePromptSection(agent, promptSection);
            pendingTasks.clear();
            closed = true;
        }
    }
}
