package io.github.bitlhk.finance.harness.examples.wealth;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EaRuntimeTokenSupplier;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;

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

/** Runnable wealth-manager proof assembled from Domain Pack, Capability Pack and EA Provider. */
public final class WealthManagerPrevisitApplication {
    private static final String AGENT_ID = "finance-harness-wealth-manager";

    private WealthManagerPrevisitApplication() {
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.fromEnvironment();
        Path repositoryRoot = repositoryRoot();
        ReActAgent agent = createAgent(settings);
        EmployeeAgentRuntimeIdentity identity =
                new EmployeeAgentRuntimeIdentity(
                        settings.eaRuntimeId(), settings.eaAgentId(), settings.eaAdoptId());
        EmployeeAgentWealthProvider provider = new EmployeeAgentWealthProvider(
                new EmployeeAgentWealthProvider.Config(
                        URI.create(settings.eaBaseUrl()),
                        identity,
                        EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity),
                        Duration.ofSeconds(30)));
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        repositoryRoot,
                        repositoryRoot.resolve(settings.solutionPath()),
                        agent,
                        Map.of(EmployeeAgentWealthProvider.PROVIDER_ID, provider),
                        defaults(settings),
                        false);
        Path lockFile = repositoryRoot.resolve(
                "examples/solutions/openjiuwen-wealth-manager-previsit/target/solution-lock.json");
        solution.writeLock(lockFile);

        boolean started = false;
        try (solution) {
            if (!Runner.start()) {
                throw new IllegalStateException("OpenJiuwen Runner failed to start");
            }
            started = true;
            System.out.println("Finance Harness solution installed: " + solution.solutionId());
            System.out.println("Installed domain packs: " + solution.lock().domainInstallations().stream()
                    .map(pack -> pack.packId() + "@" + pack.packVersion())
                    .toList());
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
                System.out.println(runTurn(agent, UUID.randomUUID().toString(), String.join(" ", args)));
                return;
            }
            interactive(agent, settings);
        } finally {
            if (started) Runner.stop();
        }
    }

    private static ReActAgent createAgent(Settings settings) {
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of(
                        "role", "system",
                        "content", "你是财富经理的访前助手。使用已安装的金融能力获取事实，并依据已安装的专业 Skill 形成可直接采用的访前判断。")))
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
                .name("财富经理访前助手")
                .description("由 Finance Harness Domain Pack 与 Capability Pack 装配的 OpenJiuwen Agent")
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
                .map(line -> line.replace("${EA_CUSTOMER_REF}", settings.customerRef()))
                .toList();
        if (prompts.isEmpty()) {
            throw new IllegalArgumentException("Demo script has no prompts: " + script);
        }

        String sessionId = "fh-wealth-demo-" + UUID.randomUUID();
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
                solution.lock().domainInstallations(),
                solution.lock().installations(),
                settings.modelName(),
                EmployeeAgentWealthProvider.PROVIDER_ID,
                script.toString(),
                startedAt,
                Instant.now(),
                turns));
        System.out.println("\nRun report: " + report);
    }

    private static void interactive(ReActAgent agent, Settings settings) {
        String sessionId = "fh-wealth-demo-" + UUID.randomUUID();
        System.out.println("当前授权测试客户：" + settings.customerRef());
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
                    sessionId = "fh-wealth-demo-" + UUID.randomUUID();
                    System.out.println("已开始新会话。");
                    continue;
                }
                System.out.println(runTurn(agent, sessionId,
                        query.replace("${EA_CUSTOMER_REF}", settings.customerRef())));
            }
        } finally {
            Runner.release(sessionId);
        }
    }

    private static String runTurn(ReActAgent agent, String sessionId, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("query", query);
        inputs.put("conversation_id", sessionId);
        return render(Runner.runAgent(agent, inputs, sessionId, null));
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

    private static OpenJiuwenFinanceBridge.InvocationDefaults defaults(Settings settings) {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "employee:" + settings.eaAdoptId(),
                settings.customerRef(),
                FinanceContextV1.PrincipalType.EMPLOYEE,
                AGENT_ID,
                "WEALTH_PREVISIT",
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
                    && Files.isDirectory(current.resolve("domain-packs"))) {
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
            String eaBaseUrl,
            String eaRuntimeId,
            String eaAgentId,
            String eaAdoptId,
            String customerRef,
            String solutionPath,
            String runReportPath) {
        private static Settings fromEnvironment() {
            return new Settings(
                    requiredEnvironment("FINANCE_AGENT_MODEL_API_KEY"),
                    environment("FINANCE_AGENT_MODEL_BASE_URL", "https://api.openai.com/v1"),
                    environment("FINANCE_AGENT_MODEL", "glm-5.2"),
                    Boolean.parseBoolean(environment("FINANCE_AGENT_MODEL_VERIFY_SSL", "true")),
                    environment("EA_BASE_URL", "http://127.0.0.1:5180"),
                    environment("EA_RUNTIME_ID", "finance-harness-wealth-demo"),
                    requiredEnvironment("EA_AGENT_ID"),
                    requiredEnvironment("EA_ADOPT_ID"),
                    requiredEnvironment("EA_CUSTOMER_REF"),
                    environment(
                            "FINANCE_AGENT_SOLUTION",
                            "examples/solutions/openjiuwen-wealth-manager-previsit/solution.yaml"),
                    environment(
                            "FINANCE_AGENT_RUN_REPORT",
                            "examples/solutions/openjiuwen-wealth-manager-previsit/target/live-demo/run-report.json"));
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
            List<OpenJiuwenSolutionAssembler.InstalledDomainPack> domainInstallations,
            List<OpenJiuwenSolutionAssembler.InstalledPack> capabilityInstallations,
            String model,
            String providerId,
            String script,
            Instant startedAt,
            Instant finishedAt,
            List<ConversationTurn> turns) {
    }
}
