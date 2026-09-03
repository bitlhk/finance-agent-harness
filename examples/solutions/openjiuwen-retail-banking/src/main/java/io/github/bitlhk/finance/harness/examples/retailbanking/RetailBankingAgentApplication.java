package io.github.bitlhk.finance.harness.examples.retailbanking;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;

/** Runnable proof that a clean OpenJiuwen Agent can acquire financial tools from solution.yaml. */
public final class RetailBankingAgentApplication {
    private static final String AGENT_ID = "finance-harness-retail-banking";

    private RetailBankingAgentApplication() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        Path repositoryRoot = repositoryRoot();
        ReActAgent agent = createAgent(settings);
        NonAiBankingProvider provider = new NonAiBankingProvider(new NonAiBankingProvider.Config(
                URI.create(settings.bankingBaseUrl()),
                settings.bankingLoginName(),
                settings.bankingLoginSecret(),
                "finance-harness-clean-agent",
                "M",
                "finance-harness-clean-agent-v1",
                Duration.ofSeconds(20),
                settings.bankingStepUpCode()));
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        repositoryRoot,
                        repositoryRoot.resolve(settings.solutionPath()),
                        agent,
                        Map.of(NonAiBankingProvider.PROVIDER_ID, provider),
                        defaults(),
                        true);
        Path lockFile = repositoryRoot.resolve(
                "examples/solutions/openjiuwen-retail-banking/target/solution-lock.json");
        solution.writeLock(lockFile);

        boolean started = false;
        try (solution) {
            if (!Runner.start()) {
                throw new IllegalStateException("OpenJiuwen Runner failed to start");
            }
            started = true;
            System.out.println("Finance Harness solution installed: " + solution.solutionId());
            System.out.println("Installed tools: " + solution.lock().installations().stream()
                    .flatMap(pack -> pack.capabilities().stream())
                    .map(binding -> binding.toolName() + " -> " + binding.capabilityId())
                    .toList());
            System.out.println("Solution lock: " + lockFile);

            Path script = scriptPath(args, repositoryRoot);
            if (script != null) {
                runScript(agent, solution, settings, script, repositoryRoot);
                return;
            }
            if (args.length > 0) {
                runTurn(agent, UUID.randomUUID().toString(), String.join(" ", args));
                return;
            }
            interactive(agent);
        } finally {
            if (started) Runner.stop();
        }
    }

    private static ReActAgent createAgent(Settings settings) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of(
                        "role", "system",
                        "content", "你是一个克制的手机银行助手。只使用已经安装的能力获取事实或办理业务；没有工具结果时不得猜测。")))
                .maxIterations(8)
                .build()
                .configureModelClient(
                        "OpenAI",
                        settings.modelApiKey(),
                        settings.modelBaseUrl(),
                        settings.modelName(),
                        settings.verifySsl());
        config.getModelConfigObj().setTemperature(0.1);
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id(AGENT_ID)
                .name("零售银行助手")
                .description("由 Finance Harness Capability Pack 装配的干净 OpenJiuwen Agent")
                .build());
        agent.configure(config);
        return agent;
    }

    private static Path scriptPath(String[] args, Path repositoryRoot) {
        if (args.length == 0) return null;
        String value = null;
        if (args[0].startsWith("--script=")) {
            value = args[0].substring("--script=".length());
        } else if ("--script".equals(args[0]) && args.length > 1) {
            value = args[1];
        }
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value);
        return path.isAbsolute() ? path.normalize() : repositoryRoot.resolve(path).normalize();
    }

    private static void runScript(
            ReActAgent agent,
            OpenJiuwenSolutionAssembler.AssembledSolution solution,
            Settings settings,
            Path script,
            Path repositoryRoot) throws Exception {
        if (!Files.isRegularFile(script)) {
            throw new IllegalArgumentException("Demo script does not exist: " + script);
        }
        List<String> prompts = Files.readAllLines(script).stream()
                .map(String::trim)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        if (prompts.isEmpty()) {
            throw new IllegalArgumentException("Demo script has no prompts: " + script);
        }

        String sessionId = "fh-demo-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        List<ConversationTurn> turns = new ArrayList<>();
        try {
            int turnNumber = 1;
            for (String prompt : prompts) {
                System.out.println("\n[用户 " + turnNumber + "] " + prompt);
                String response = runTurn(agent, sessionId, prompt);
                System.out.println("[助手 " + turnNumber + "] " + response);
                turns.add(new ConversationTurn(turnNumber, prompt, response));
                turnNumber++;
            }
        } finally {
            Runner.release(sessionId);
        }

        Path report = repositoryRoot.resolve(settings.runReportPath()).normalize();
        Files.createDirectories(report.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), new LiveRunReport(
                "finance-live-agent-run.v1",
                solution.solutionId(),
                solution.lock().installations(),
                settings.modelName(),
                NonAiBankingProvider.PROVIDER_ID,
                script.toString(),
                startedAt,
                Instant.now(),
                turns));
        System.out.println("\nRun report: " + report);
    }

    private static void interactive(ReActAgent agent) {
        String sessionId = "fh-demo-" + UUID.randomUUID();
        System.out.println("输入问题开始对话；输入 :new 开始新会话，输入 :quit 退出。");
        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("> ");
                if (!scanner.hasNextLine()) return;
                String query = scanner.nextLine().trim();
                if (query.isBlank()) continue;
                if (":quit".equalsIgnoreCase(query)) return;
                if (":new".equalsIgnoreCase(query)) {
                    Runner.release(sessionId);
                    sessionId = "fh-demo-" + UUID.randomUUID();
                    System.out.println("已开始新会话。");
                    continue;
                }
                runTurn(agent, sessionId, query);
            }
        } finally {
            Runner.release(sessionId);
        }
    }

    private static String runTurn(ReActAgent agent, String sessionId, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("conversation_id", sessionId);
        Object result = Runner.runAgent(agent, inputs, sessionId, null);
        return render(result);
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

    private static OpenJiuwenFinanceBridge.InvocationDefaults defaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "customer-demo-001",
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "finance-harness-clean-agent",
                "RETAIL_BANKING",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
    }

    private static Path repositoryRoot() {
        String configured = System.getenv("FINANCE_HARNESS_REPOSITORY");
        Path current = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir")).toAbsolutePath()
                : Path.of(configured).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("capability-packs"))
                    && Files.isDirectory(current.resolve("integrations/openjiuwen-java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Finance Harness repository root was not found; set FINANCE_HARNESS_REPOSITORY");
    }

    private record Settings(
            String modelApiKey,
            String modelBaseUrl,
            String modelName,
            boolean verifySsl,
            String bankingBaseUrl,
            String bankingLoginName,
            String bankingLoginSecret,
            String bankingStepUpCode,
            String solutionPath,
            String runReportPath) {
        private static Settings fromEnvironment() {
            return new Settings(
                    requiredEnvironment("FINANCE_AGENT_MODEL_API_KEY"),
                    environment("FINANCE_AGENT_MODEL_BASE_URL", "https://api.openai.com/v1"),
                    environment("FINANCE_AGENT_MODEL", "glm-5.2"),
                    Boolean.parseBoolean(environment("FINANCE_AGENT_MODEL_VERIFY_SSL", "true")),
                    environment("NON_AI_BANKING_BASE_URL", "http://116.204.36.83:8086"),
                    environment("NON_AI_BANKING_LOGIN_NAME", "demo"),
                    environment("NON_AI_BANKING_LOGIN_SECRET", "demo123"),
                    environment("NON_AI_BANKING_STEP_UP_CODE", "000000"),
                    environment(
                            "FINANCE_AGENT_SOLUTION",
                            "examples/solutions/openjiuwen-retail-banking/solution.yaml"),
                    environment(
                            "FINANCE_AGENT_RUN_REPORT",
                            "examples/solutions/openjiuwen-retail-banking/target/live-demo/run-report.json"));
        }

        private static String requiredEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required");
            }
            return value.trim();
        }

        private static String environment(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    private record ConversationTurn(int turn, String user, String assistant) {
    }

    private record LiveRunReport(
            String schemaVersion,
            String solutionId,
            List<OpenJiuwenSolutionAssembler.InstalledPack> installations,
            String model,
            String providerId,
            String script,
            Instant startedAt,
            Instant finishedAt,
            List<ConversationTurn> turns) {
    }
}
