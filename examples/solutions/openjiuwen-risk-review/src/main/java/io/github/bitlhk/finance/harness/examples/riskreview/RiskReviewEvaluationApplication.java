package io.github.bitlhk.finance.harness.examples.riskreview;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
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
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runs one controlled risk-review case for the Finance Skill Eval RuntimeAdapter. */
public final class RiskReviewEvaluationApplication {
    private static final String ACCOUNT_PROVIDER_ID = "non-ai-banking-reference";
    private static final String FRAUD_PROVIDER_ID = "fraud-mining-reference";

    private RiskReviewEvaluationApplication() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> arguments = arguments(args);
        String variant = required(arguments, "variant");
        String caseId = required(arguments, "case-id");
        Path promptFile = Path.of(required(arguments, "prompt-file")).toAbsolutePath().normalize();
        Path outputFile = Path.of(required(arguments, "output-file")).toAbsolutePath().normalize();
        if (!Set.of("with-domain", "without-domain").contains(variant)) {
            throw new IllegalArgumentException("variant must be with-domain or without-domain");
        }
        String prompt = Files.readString(promptFile).trim();
        if (prompt.isBlank()) throw new IllegalArgumentException("prompt file is empty");

        Settings settings = Settings.fromEnvironment();
        Path root = repositoryRoot();
        List<ToolCall> toolCalls = new ArrayList<>();
        Map<String, FinanceProvider> providers = Map.of(
                ACCOUNT_PROVIDER_ID, new ControlledAccountProvider(toolCalls),
                FRAUD_PROVIDER_ID, new ControlledFraudProvider(toolCalls));
        ReActAgent agent = createAgent(settings, caseId, variant);
        Path solutionFile = root.resolve("examples/solutions/openjiuwen-risk-review/")
                .resolve("with-domain".equals(variant) ? "solution.yaml" : "solution-without-domain.yaml");
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        solutionFile,
                        agent,
                        providers,
                        defaults(caseId),
                        true);

        boolean started = false;
        Instant startedAt = Instant.now();
        String sessionId = "risk-review-eval-" + UUID.randomUUID();
        try (solution) {
            if (!Runner.start()) throw new IllegalStateException("OpenJiuwen Runner failed to start");
            started = true;
            String response = render(Runner.runAgent(
                    agent,
                    Map.of("query", prompt, "conversation_id", sessionId),
                    sessionId,
                    null));
            Instant finishedAt = Instant.now();
            Path lockFile = outputFile.resolveSibling(outputFile.getFileName() + ".lock.json");
            solution.writeLock(lockFile);
            List<LoadedSkill> loadedSkills = solution.lock().domainInstallations().stream()
                    .flatMap(pack -> pack.skills().stream()
                            .map(skill -> new LoadedSkill(skill.directory(), pack.packVersion())))
                    .toList();
            EvaluationOutput output = new EvaluationOutput(
                    "finance-risk-review-execution.v1",
                    caseId,
                    variant,
                    settings.modelName(),
                    response,
                    java.time.Duration.between(startedAt, finishedAt).toMillis(),
                    List.copyOf(toolCalls),
                    loadedSkills,
                    solution.solutionId(),
                    solution.lock(),
                    false,
                    false,
                    Map.of("runtimeIdentity", "NOT_CHECKED", "streamCompletion", "NOT_CHECKED",
                            "synchronousInvocation", "RETURNED", "providerMode", "CONTROLLED_FIXTURE"));
            Files.createDirectories(outputFile.getParent());
            HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(outputFile.toFile(), output);
            System.out.println(outputFile);
        } finally {
            Runner.release(sessionId);
            if (started) Runner.stop();
        }
    }

    private static ReActAgent createAgent(Settings settings, String caseId, String variant) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of(
                        "role", "system",
                        "content", "你是银行内部转账风险审核助手。请使用已安装能力核验信息，并给审核人员提供可采用的审核材料。")))
                .maxIterations(8)
                .build()
                .configureModelClient(
                        "OpenAI",
                        settings.apiKey(),
                        settings.baseUrl(),
                        settings.modelName(),
                        settings.verifySsl());
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(3000);
        ModelClientConfig originalClient = config.getModelClientConfig();
        ModelClientConfig.Builder client = ModelClientConfig.builder()
                .clientId(originalClient.getClientId())
                .clientProvider(originalClient.getClientProvider())
                .apiKey(originalClient.getApiKey())
                .apiBase(originalClient.getApiBase())
                .timeout(180)
                .maxRetries(1)
                .verifySsl(originalClient.isVerifySsl())
                .headers(originalClient.getHeaders());
        originalClient.getExtraFields().forEach(client::extraField);
        config.setModelClientConfig(client.build());
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id("risk-review-" + variant + "-" + caseId.toLowerCase())
                .name("转账风险审核助手")
                .description("Finance Harness 受控风险审核评测 Candidate")
                .build());
        agent.configure(config);
        return agent;
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults defaults(String caseId) {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "risk-reviewer-001",
                "customer-fixture-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "risk-review-eval-" + caseId.toLowerCase(),
                "TRANSFER_RISK_REVIEW",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
    }

    private static String render(Object value) {
        if (value == null) return "";
        if (value instanceof WorkflowOutput output) return render(output.getResult());
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("content", "output", "response", "result")) {
                Object nested = map.get(key);
                if (nested != null) {
                    String rendered = render(nested);
                    if (!rendered.isBlank()) return rendered;
                }
            }
        }
        return String.valueOf(value);
    }

    private static Map<String, String> arguments(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String argument : args) {
            if (!argument.startsWith("--") || !argument.contains("=")) continue;
            int separator = argument.indexOf('=');
            values.put(argument.substring(2, separator), argument.substring(separator + 1));
        }
        return Map.copyOf(values);
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " is required");
        return value.trim();
    }

    private static Path repositoryRoot() {
        String configured = System.getenv("FINANCE_HARNESS_REPOSITORY");
        Path current = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                : Path.of(configured).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("capability-packs"))
                    && Files.isDirectory(current.resolve("domain-packs"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("Finance Harness repository root was not found");
    }

    private static CapabilityResultV1 completed(
            CapabilityRequestV1 request,
            String content,
            Map<String, Object> data,
            List<String> nextActions,
            String sourceSystem,
            Instant asOf) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                content,
                data,
                List.of(),
                nextActions,
                sourceSystem,
                asOf,
                null,
                null);
    }

    private static final class ControlledAccountProvider implements DescribedFinanceProvider {
        private final List<ToolCall> calls;

        private ControlledAccountProvider(List<ToolCall> calls) {
            this.calls = calls;
        }

        @Override
        public ProviderDescriptorV1 descriptor() {
            return RiskReviewEvaluationApplication.descriptor(
                    ACCOUNT_PROVIDER_ID, "account.balance.query");
        }

        @Override
        public CapabilityResultV1 query(CapabilityRequestV1 request) {
            String accountId = String.valueOf(request.parameters().getOrDefault("accountId", "unknown"));
            CapabilityResultV1 result = completed(
                    request,
                    "账户事实查询完成。",
                    Map.of("accounts", List.of(Map.of(
                            "accountId", accountId,
                            "holderNameMasked", "王**",
                            "status", "ACTIVE",
                            "currency", "CNY",
                            "availableBalance", 128500.00,
                            "asOf", "2026-09-03T09:00:00Z"))),
                    List.of("CONTINUE_RISK_REVIEW"),
                    "controlled-account-fixture",
                    Instant.parse("2026-09-03T09:00:00Z"));
            calls.add(new ToolCall("account_balance_query", "completed"));
            return result;
        }

        @Override
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            throw new UnsupportedOperationException("controlled account Provider is read-only");
        }
    }

    private static final class ControlledFraudProvider implements DescribedFinanceProvider {
        private final List<ToolCall> calls;

        private ControlledFraudProvider(List<ToolCall> calls) {
            this.calls = calls;
        }

        @Override
        public ProviderDescriptorV1 descriptor() {
            return RiskReviewEvaluationApplication.descriptor(
                    FRAUD_PROVIDER_ID, "fraud.transfer-risk.assess");
        }

        @Override
        public CapabilityResultV1 query(CapabilityRequestV1 request) {
            String accountRef = String.valueOf(request.parameters().getOrDefault("accountRef", "unknown"));
            if (accountRef.contains("unavailable")) {
                CapabilityResultV1 result = new CapabilityResultV1(
                        CapabilityResultV1.SCHEMA_VERSION,
                        request.context().requestId(),
                        request.context().traceId(),
                        CapabilityResultV1.Status.FAILED,
                        "反诈风险服务暂时无法完成评估。",
                        Map.of(),
                        List.of(),
                        List.of("RETRY_OR_MANUAL_REVIEW"),
                        "controlled-fraud-fixture",
                        Instant.parse("2026-09-03T09:00:00Z"),
                        null,
                        new CapabilityResultV1.Failure(
                                "TEMPORARY_FAILURE",
                                "FRAUD_SERVICE_UNAVAILABLE",
                                true,
                                "controlled unavailable fixture",
                                List.of()));
                calls.add(new ToolCall("fraud_transfer_risk_assess", "failed"));
                return result;
            }
            boolean matched = accountRef.contains("risk");
            List<Map<String, Object>> signals = matched
                    ? List.of(Map.of(
                            "signalId", "incident-20260903-001",
                            "matchStatus", "matched",
                            "matchConfidence", 0.98,
                            "modelLabel", "suspected-fraud",
                            "modelScore", 0.91,
                            "observedAt", "2026-09-02T14:30:00Z"))
                    : List.of();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("subjectRef", accountRef);
            data.put("decision", matched ? "BLOCK" : "ALLOW");
            data.put("reasonCode", matched ? "KNOWN_FRAUD_INCIDENT_MATCH" : "NO_KNOWN_FRAUD_MATCH");
            data.put("knownRiskMatch", matched);
            data.put("signals", signals);
            data.put("decisionSource", "controlled-fraud-incident-registry");
            CapabilityResultV1 result = completed(
                    request,
                    matched ? "命中已知涉诈风险记录。" : "未命中已知涉诈风险记录。",
                    Map.copyOf(data),
                    matched ? List.of("BLOCK_TRANSFER", "REFER_FRAUD_REVIEW") : List.of("CONTINUE_PREFLIGHT"),
                    "controlled-fraud-fixture",
                    Instant.parse("2026-09-03T09:00:00Z"));
            calls.add(new ToolCall("fraud_transfer_risk_assess", "completed"));
            return result;
        }

        @Override
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            throw new UnsupportedOperationException("controlled fraud Provider is read-only");
        }
    }

    private static ProviderDescriptorV1 descriptor(String providerId, String capabilityId) {
        return new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION,
                providerId,
                "0.1.0-SNAPSHOT",
                List.of(new ProviderDescriptorV1.CapabilitySupport(
                        capabilityId,
                        CapabilityRequestV1.Operation.QUERY)));
    }

    private record Settings(String apiKey, String baseUrl, String modelName, boolean verifySsl) {
        private static Settings fromEnvironment() {
            return new Settings(
                    requiredEnvironment("FINANCE_AGENT_MODEL_API_KEY"),
                    environment("FINANCE_AGENT_MODEL_BASE_URL", "https://api.openai.com/v1"),
                    environment("FINANCE_AGENT_MODEL", "glm-5.2"),
                    Boolean.parseBoolean(environment("FINANCE_AGENT_MODEL_VERIFY_SSL", "true")));
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
            return value.trim();
        }

        private static String environment(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record ToolCall(String name, String status) {
    }

    private record LoadedSkill(String skillId, String version) {
    }

    private record EvaluationOutput(
            String schemaVersion,
            String caseId,
            String variant,
            String modelId,
            String output,
            long elapsedMs,
            List<ToolCall> toolCalls,
            List<LoadedSkill> loadedSkills,
            String solutionId,
            OpenJiuwenSolutionAssembler.SolutionLock solutionLock,
            boolean runtimeIdentityVerified,
            boolean streamCompleted,
            Map<String, String> verification) {
    }
}
