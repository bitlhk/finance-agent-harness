package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.foundation.tool.function.LocalFunction;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
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
        String providerVersion = provider instanceof DescribedFinanceProvider described
                ? described.descriptor().version()
                : "unresolved";
        Map<String, ProviderBinding> bindings = new LinkedHashMap<>();
        pack.capabilities().forEach(capability -> bindings.put(
                capability.capabilityId(),
                new ProviderBinding(providerId, providerVersion, provider)));
        return install(agent, bindings, defaults, pack, simulated);
    }

    public Installation install(
            BaseAgent agent,
            Map<String, ProviderBinding> providerBindings,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            FinanceCapabilityPackV1 pack,
            boolean simulated) {
        return install(agent, providerBindings, defaults, pack, simulated,
                new TaskCheckpointStore.Memory(), "session-" + UUID.randomUUID());
    }

    /** Supply an authenticated, stable host session id and durable store for restart recovery. */
    public Installation install(
            BaseAgent agent,
            Map<String, ProviderBinding> providerBindings,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            FinanceCapabilityPackV1 pack,
            boolean simulated,
            TaskCheckpointStore store,
            String hostSessionRef) {
        return install(agent, providerBindings, defaults, pack, simulated, store, hostSessionRef, null);
    }

    /** Installs the same capability contract with an optional, frozen description catalog. */
    public Installation install(
            BaseAgent agent,
            Map<String, ProviderBinding> providerBindings,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            FinanceCapabilityPackV1 pack,
            boolean simulated,
            TaskCheckpointStore store,
            String hostSessionRef,
            CapabilityDescriptionCatalogV01 descriptions) {
        return install(agent, providerBindings, defaults, pack, simulated, store, hostSessionRef, descriptions, true);
    }

    Installation install(
            BaseAgent agent,
            Map<String, ProviderBinding> providerBindings,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            FinanceCapabilityPackV1 pack,
            boolean simulated,
            TaskCheckpointStore store,
            String hostSessionRef,
            CapabilityDescriptionCatalogV01 descriptions,
            boolean installTerminology) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(providerBindings, "providerBindings");
        String agentId = agent.getCard().getId();
        if (hostSessionRef == null || hostSessionRef.isBlank()) throw new IllegalArgumentException("hostSessionRef is required");
        String scope = OpenJiuwenFinanceBridge.fingerprint(List.of(agentId, defaults.principalId(),
                defaults.subjectRef(), defaults.purpose(), defaults.channel(), hostSessionRef, pack.packId(), pack.version(),
                providerBindings.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(entry ->
                        List.of(entry.getKey(), entry.getValue().providerId(), entry.getValue().providerVersion())).toList()));
        InstalledTaskController tasks = new InstalledTaskController(agentId, scope, Objects.requireNonNull(store));
        Map<String, Tool> installedTools = new LinkedHashMap<>();
        String promptSection = "finance-capability-pack:" + pack.packId();

        if (agent instanceof ReActAgent reactAgent) {
            reactAgent.addPromptBuilderSection(promptSection, pack.systemPrompt(), 80);
            if (installTerminology && descriptions != null && !descriptions.terminologyPrompt().isBlank()) {
                reactAgent.addPromptBuilderSection(promptSection + ":terminology",
                        descriptions.terminologyPrompt(), 81);
            }
            if (installTerminology && descriptions != null && descriptions.allReadOnly()) {
                reactAgent.addPromptBuilderSection(promptSection + ":task-collaboration",
                        ReadOnlyTaskCollaborationV01.INSTRUCTION, 82);
            }
        }

        try {
            for (FinanceCapabilityPackV1.Capability capability : pack.capabilities()) {
                ProviderBinding providerBinding = providerBindings.get(capability.capabilityId());
                if (providerBinding == null) {
                    throw new IllegalArgumentException(
                            "Provider binding is missing for capability: " + capability.capabilityId());
                }
                OpenJiuwenFinanceBridge bridge = new OpenJiuwenFinanceBridge(
                        providerBinding.provider(),
                        providerBinding.providerId(),
                        defaults,
                        Set.of(capability.capabilityId()),
                        simulated).hostSession(hostSessionRef);
                ensureAvailable(agent, capability.toolName());
                tasks.register(capability, bridge);
                Tool tool = tool(agentId, capability, bridge, tasks, descriptions);
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
            if (installTerminology) removePromptSection(agent, promptSection + ":terminology");
            if (installTerminology) removePromptSection(agent, promptSection + ":task-collaboration");
            throw exception;
        }

        List<CapabilityBinding> bindings = pack.capabilities().stream()
                .map(capability -> new CapabilityBinding(
                        capability.capabilityId(),
                        capability.toolName(),
                        capability.operation().name(),
                        providerBindings.get(capability.capabilityId()).providerId(),
                        providerBindings.get(capability.capabilityId()).providerVersion()))
                .toList();
        Set<String> providerIds = new LinkedHashSet<>();
        bindings.forEach(binding -> providerIds.add(binding.providerId()));
        InstallationReceipt receipt = new InstallationReceipt(
                "fh-install-" + UUID.randomUUID(),
                pack.packId(),
                pack.version(),
                INTEGRATION_ID,
                INTEGRATION_VERSION,
                providerIds.size() == 1 ? providerIds.iterator().next() : "multiple",
                agentId,
                bindings,
                Instant.now());
        return new Installation(agent, installedTools, tasks, receipt, promptSection, installTerminology);
    }

    private static Tool tool(
            String agentId,
            FinanceCapabilityPackV1.Capability capability,
            OpenJiuwenFinanceBridge bridge,
            InstalledTaskController tasks,
            CapabilityDescriptionCatalogV01 descriptions) {
        Map<String, Object> inputSchema = capability.operation() == FinanceCapabilityPackV1.Operation.TASK
                ? withTaskControls(capability.inputSchema())
                : capability.inputSchema();
        ToolCard card = ToolCard.builder()
                .id(capability.toolName())
                .name(capability.toolName())
                .description(descriptions == null
                        ? capability.description() + " [capabilityId=" + capability.capabilityId() + "]"
                        : descriptions.description(capability.capabilityId()))
                .inputParams(inputSchema)
                .properties(Map.of(
                        "financeCapabilityId", capability.capabilityId(),
                        "financeOperation", capability.operation().name()))
                .build();
        return new LocalFunction(card, arguments -> capability.operation() == FinanceCapabilityPackV1.Operation.QUERY
                ? query(agentId, capability, bridge, arguments)
                : tasks.invoke(capability.capabilityId(), arguments));
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
        return InstalledTaskController.result(bridge.query(invocation), "");
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
                "description", "兼容旧调用的文本字段，不构成授权；办理确认须通过宿主确认界面。"));
        schema.put("properties", Map.copyOf(properties));
        Object configuredRequired = schema.remove("required");
        if (configuredRequired instanceof List<?> required && !required.isEmpty()) {
            schema.put("anyOf", List.of(
                    Map.of("required", List.copyOf(required)),
                    Map.of("required", List.of(TASK_HANDLE_ARGUMENT))));
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

    public record ProviderBinding(
            String providerId,
            String providerVersion,
            FinanceProvider provider) {
        public ProviderBinding {
            if (providerId == null || providerId.isBlank()) {
                throw new IllegalArgumentException("providerId is required");
            }
            if (providerVersion == null || providerVersion.isBlank()) {
                throw new IllegalArgumentException("providerVersion is required");
            }
            providerId = providerId.trim();
            providerVersion = providerVersion.trim();
            Objects.requireNonNull(provider, "provider");
        }
    }

    public record CapabilityBinding(
            String capabilityId,
            String toolName,
            String operation,
            String providerId,
            String providerVersion) {
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
        private final InstalledTaskController tasks;
        private final InstallationReceipt receipt;
        private final String promptSection;
        private final boolean terminologyInstalled;
        private boolean closed;

        private Installation(
                BaseAgent agent,
                Map<String, Tool> tools,
                InstalledTaskController tasks,
                InstallationReceipt receipt,
                String promptSection,
                boolean terminologyInstalled) {
            this.agent = agent;
            this.tools = Map.copyOf(tools);
            this.tasks = tasks;
            this.receipt = receipt;
            this.promptSection = promptSection;
            this.terminologyInstalled = terminologyInstalled;
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

        public synchronized String confirmationBinding(String handle) {
            ensureOpen();
            return tasks.confirmationBinding(handle);
        }

        public synchronized Map<String, Object> confirm(String handle, HostTaskConfirmation approval) {
            ensureOpen();
            return tasks.confirm(handle, approval);
        }

        public synchronized Map<String, Object> decline(String handle) {
            ensureOpen();
            return tasks.decline(handle);
        }

        private void ensureOpen() {
            if (closed) throw new IllegalStateException("Installation is closed");
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            uninstall(agent, tools.keySet(), receipt.agentId());
            removePromptSection(agent, promptSection);
            if (terminologyInstalled) removePromptSection(agent, promptSection + ":terminology");
            if (terminologyInstalled) removePromptSection(agent, promptSection + ":task-collaboration");
            closed = true;
        }
    }
}
