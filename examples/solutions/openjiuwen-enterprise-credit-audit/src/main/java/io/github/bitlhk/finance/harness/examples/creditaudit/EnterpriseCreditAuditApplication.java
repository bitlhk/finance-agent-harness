package io.github.bitlhk.finance.harness.examples.creditaudit;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;
import io.github.bitlhk.finance.harness.providers.creditaudit.DemoRuleSet;
import io.github.bitlhk.finance.harness.providers.creditaudit.EnterpriseCreditMaterialAuditProvider;
import io.github.bitlhk.finance.harness.providers.creditaudit.MaterialSetRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EnterpriseCreditAuditApplication {
    private EnterpriseCreditAuditApplication() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> values = arguments(args);
        String materialSetRef = required(values, "material-set-ref");
        String owner = required(values, "owner");
        String principal = required(values, "principal");
        String session = required(values, "session");
        Path output = Path.of(required(values, "output-file")).toAbsolutePath().normalize();
        Path repository = repositoryRoot();

        var base = new EnterpriseCreditMaterialAuditProvider(
                new MaterialSetRepository(Path.of(requiredEnv("FH_CREDIT_AUDIT_MATERIAL_ROOT"))),
                DemoRuleSet.load(Path.of(requiredEnv("FH_CREDIT_AUDIT_RULE_FILE"))));
        var provider = new RecordingProvider(base);
        var context = context(owner, principal, session);
        List<CapabilityResultV1> deterministic = List.of(
                base.query(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL, materialSetRef, context)),
                base.query(request(EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL, materialSetRef, context)));

        Settings settings = Settings.fromEnvironment();
        ReActAgent agent = createAgent(settings);
        var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults(principal, owner,
                FinanceContextV1.PrincipalType.EMPLOYEE, "credit-audit-isolated",
                "enterprise-credit-material-review", FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("credit-audit:financial-statement:read", "credit-audit:application-contract:read"));
        var solution = new OpenJiuwenSolutionAssembler().assemble(repository,
                repository.resolve("examples/solutions/openjiuwen-enterprise-credit-audit/solution.yaml"),
                agent, Map.of(EnterpriseCreditMaterialAuditProvider.ID, provider), defaults, true,
                new io.github.bitlhk.finance.harness.integrations.openjiuwen.TaskCheckpointStore.Memory(), session);

        String response = "";
        String explanationStatus = "FAILED";
        String explanationError = "MODEL_NOT_RUN";
        boolean started = false;
        String conversation = "credit-audit-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        try (solution) {
            if (!Runner.start()) throw new IllegalStateException("OpenJiuwen Runner failed to start");
            started = true;
            try {
                response = render(Runner.runAgent(agent, Map.of(
                        "query", explanationPrompt(materialSetRef, deterministic),
                        "conversation_id", conversation), conversation, null));
                ExplanationGuard.requireAllowed(response);
                explanationStatus = "COMPLETED";
                explanationError = "";
            } catch (Exception exception) {
                explanationError = exception.getClass().getSimpleName() + ": " + safe(exception.getMessage());
            }
            Files.createDirectories(output.getParent());
            Path lock = output.resolveSibling(output.getFileName() + ".lock.json");
            solution.writeLock(lock);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schemaVersion", "enterprise-credit-audit-run.v0.1");
            report.put("materialSetRef", materialSetRef);
            report.put("deterministicResults", deterministic);
            report.put("explanationStatus", explanationStatus);
            report.put("explanation", response);
            report.put("explanationError", explanationError);
            report.put("model", settings.model());
            report.put("modelParameters", Map.of("temperature", 0.1, "maxTokens", 2200));
            report.put("methodVersion", "0.1.0");
            report.put("toolAttempts", provider.attempts());
            report.put("installationLock", lock.toString());
            report.put("startedAt", startedAt.toString());
            report.put("finishedAt", Instant.now().toString());
            HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } finally {
            Runner.release(conversation);
            if (started) Runner.stop();
        }
    }

    static String explanationPrompt(String reference, List<CapabilityResultV1> results) throws Exception {
        return "当前材料集引用为 " + reference + "。以下确定性结果已经由 Harness 计算并冻结，请只解释这些结果，"
                + "不得修改数值、补造材料或形成通过/拒绝/授信/放款/欺诈结论。给出证据矩阵、影响和最小补件：\n"
                + HarnessJson.mapper().writeValueAsString(results);
    }

    private static ReActAgent createAgent(Settings settings) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content",
                        "你是企业信贷材料核验辅助。确定性结果优先，输出仅供人工复核。")))
                .maxIterations(6).build()
                .configureModelClient("OpenAI", settings.apiKey(), settings.baseUrl(), settings.model(), true);
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(2200);
        ModelClientConfig original = config.getModelClientConfig();
        ModelClientConfig.Builder client = ModelClientConfig.builder().clientId(original.getClientId())
                .clientProvider(original.getClientProvider()).apiKey(original.getApiKey()).apiBase(original.getApiBase())
                .timeout(120).maxRetries(1).verifySsl(original.isVerifySsl()).headers(original.getHeaders());
        original.getExtraFields().forEach(client::extraField);
        config.setModelClientConfig(client.build());
        ReActAgent agent = new ReActAgent(AgentCard.builder().id("enterprise-credit-material-audit")
                .name("企业信贷材料核验辅助").description("确定性核验与受约束人工复核草稿").build());
        agent.configure(config);
        return agent;
    }

    private static CapabilityRequestV1 request(String capability, String ref, FinanceContextV1 context) {
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, capability,
                CapabilityRequestV1.Operation.QUERY, context, Map.of("materialSetRef", ref), null, null, Map.of(), null);
    }

    private static FinanceContextV1 context(String owner, String principal, String session) {
        return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-" + UUID.randomUUID(),
                "trace-" + UUID.randomUUID(), principal, FinanceContextV1.PrincipalType.EMPLOYEE,
                "credit-audit-isolated", "enterprise-credit-material-review", owner, session,
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("credit-audit:financial-statement:read", "credit-audit:application-contract:read"));
    }

    private static String render(Object value) {
        if (value == null) return "";
        if (value instanceof WorkflowOutput output) return render(output.getResult());
        if (value instanceof Map<?, ?> map) for (String key : List.of("content", "output", "response", "result")) {
            if (map.get(key) != null && !render(map.get(key)).isBlank()) return render(map.get(key));
        }
        return String.valueOf(value);
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getenv().getOrDefault("FINANCE_HARNESS_REPOSITORY", System.getProperty("user.dir"))).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("capability-packs")) && Files.isDirectory(current.resolve("domain-packs"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Finance Harness repository root was not found");
    }

    private static Map<String, String> arguments(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String arg : args) if (arg.startsWith("--") && arg.contains("=")) {
            int at = arg.indexOf('='); values.put(arg.substring(2, at), arg.substring(at + 1));
        }
        return Map.copyOf(values);
    }
    private static String required(Map<String, String> values, String key) {
        String value = values.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + key + " is required"); return value;
    }
    private static String requiredEnv(String key) {
        String value = System.getenv(key); if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required"); return value;
    }
    private static String safe(String value) {
        if (value == null) return "unknown";
        String normalized = value.replaceAll("[\\r\\n]+", " ");
        return normalized.substring(0, Math.min(300, normalized.length()));
    }

    record Settings(String apiKey, String baseUrl, String model) {
        static Settings fromEnvironment() {
            return new Settings(requiredEnv("FINANCE_AGENT_MODEL_API_KEY"),
                    requiredEnv("FINANCE_AGENT_MODEL_BASE_URL"), requiredEnv("FINANCE_AGENT_MODEL"));
        }
    }

    static final class ExplanationGuard {
        private static final List<String> PROHIBITED = List.of("批准授信", "同意放款", "拒绝贷款", "认定欺诈", "自动审批通过");
        static void requireAllowed(String response) {
            if (response == null || response.isBlank()) throw new IllegalArgumentException("EMPTY_MODEL_EXPLANATION");
            if (response.matches("\\{output=\\s*,\\s*result_type=answer}"))
                throw new IllegalArgumentException("EMPTY_MODEL_EXPLANATION");
            for (String phrase : PROHIBITED) if (response.contains(phrase)) throw new IllegalArgumentException("MODEL_DECISION_BOUNDARY_VIOLATION");
        }
    }

    static final class RecordingProvider implements DescribedFinanceProvider {
        private final EnterpriseCreditMaterialAuditProvider delegate;
        private final List<Map<String, Object>> attempts = new ArrayList<>();
        RecordingProvider(EnterpriseCreditMaterialAuditProvider delegate) { this.delegate = delegate; }
        @Override public ProviderDescriptorV1 descriptor() { return delegate.descriptor(); }
        @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
            CapabilityResultV1 result = delegate.query(request);
            attempts.add(Map.of("capabilityId", request.capabilityId(), "status", result.status().name(),
                    "requestId", result.requestId(), "observedAt", Instant.now().toString()));
            return result;
        }
        @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { return delegate.executeTask(request); }
        List<Map<String, Object>> attempts() { return List.copyOf(attempts); }
    }
}
