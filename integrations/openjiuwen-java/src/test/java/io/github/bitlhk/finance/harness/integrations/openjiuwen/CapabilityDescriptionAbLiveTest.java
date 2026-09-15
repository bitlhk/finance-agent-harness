package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit live A/B experiment. It installs real Runtime tools backed only by an in-memory recorder. */
class CapabilityDescriptionAbLiveTest {
    private static final Set<String> ALL = Set.of("account.balance.query", "finance.transaction-summary.query",
            "finance.spending-analysis.query", "wealth.facts.describe", "transfer.internal.create");

    @Test void modelFailuresNeverReachTaskProvider() throws Exception {
        AtomicInteger http = new AtomicInteger(), provider = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            http.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            byte[] body = "controlled model failure".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Path root = root();
            var pack = new FinanceCapabilityPackLoader().load(root.resolve("capability-packs/internal-transfer/pack.yaml"));
            var descriptions = enhanced(root, List.of(pack));
            for (int index = 0; index < 5; index++) {
                String session = "fh-ab-failure-" + index + "-" + UUID.randomUUID();
                ReActAgent agent = agent(session, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                        "controlled-failure", "not-a-secret");
                var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults("synthetic-customer", "synthetic-customer",
                        FinanceContextV1.PrincipalType.CUSTOMER, session, "AB_FAILURE_EVALUATION",
                        FinanceContextV1.AuthenticationLevel.STRONG, Set.of("transfer:create"));
                var bindings = Map.of("transfer.internal.create", new OpenJiuwenCapabilityInstaller.ProviderBinding(
                        "isolated-recorder", "0.1.0", recorder(provider)));
                try (var installation = new OpenJiuwenCapabilityInstaller().install(agent, bindings, defaults, pack,
                        true, new TaskCheckpointStore.Memory(), session, descriptions)) {
                    try { Runner.runAgent(agent, Map.of("query", "从账户A转100元给收款人B"), session, null); }
                    catch (RuntimeException expected) { /* The model transport is deliberately unavailable. */ }
                } finally { Runner.release(session); }
            }
        } finally { server.stop(0); }
        assertEquals(0, provider.get(), "Model failure must not invoke the task Provider");
        assertEquals(5, http.get(), "Each controlled case should make one model attempt");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FH_AB_LIVE", matches = "true")
    void compareBaseAndEnhancedDescriptionsOnSameRuntimeAndModel() throws Exception {
        requireEnvironment("FH_MODEL_URL", "FH_MODEL_NAME", "FH_MODEL_KEY");
        Path root = root();
        JsonNode suite = HarnessJson.mapper().readTree(root.resolve(
                "evaluation/capability-description-ab-v0.1/cases.json").toFile());
        int limit = Integer.parseInt(System.getenv().getOrDefault("FH_AB_CASE_LIMIT", "75"));
        List<Map<String, Object>> results = new ArrayList<>();
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode testCase : suite.path("cases")) {
            if (testCase.path("category").asText().equals("controlled_model_failure")) continue;
            if (selected.size() >= limit) break;
            selected.add(testCase);
        }
        for (int index = 0; index < selected.size(); index++) {
            List<String> order = index % 2 == 0 ? List.of("A", "B") : List.of("B", "A");
            for (String variant : order) results.add(runCase(root, variant, selected.get(index)));
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "finance-capability-description-ab-report.v0.1");
        report.put("createdAt", Instant.now().toString());
        report.put("modelName", System.getenv("FH_MODEL_NAME"));
        report.put("runtime", "openjiuwen-java-0.1.15");
        report.put("casesPerVariant", limit);
        report.put("results", results);
        report.put("summary", summarize(results));
        Path output = Path.of(System.getenv().getOrDefault("FH_AB_REPORT",
                root.resolve("target/capability-description-ab-report.json").toString()));
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(limit * 2, results.size());
    }

    private Map<String, Object> runCase(Path root, String variant, JsonNode testCase) throws Exception {
        Set<String> installed = new LinkedHashSet<>();
        if (testCase.has("installedCapabilities")) testCase.path("installedCapabilities").forEach(v -> installed.add(v.asText()));
        else installed.addAll(ALL);
        String session = "fh-ab-" + variant.toLowerCase() + "-" + testCase.path("id").asText() + "-" + UUID.randomUUID();
        List<String> calls = new ArrayList<>();
        List<List<String>> callsByTurn = new ArrayList<>();
        AtomicInteger providerOperations = new AtomicInteger();
        List<OpenJiuwenCapabilityInstaller.Installation> installations = new ArrayList<>();
        long started = System.nanoTime();
        String error = "";
        try {
            ReActAgent agent = agent(session);
            agent.registerCallback(AgentCallbackEvent.BEFORE_TOOL_CALL, context -> {
                if (context.getInputs() instanceof ToolCallInputs input) {
                    calls.add(input.getToolName());
                    context.requestForceFinish(Map.of("evaluation", "TOOL_SELECTION_CAPTURED_NO_EXECUTION"));
                }
            }, 0);
            List<FinanceCapabilityPackV1> packs = selectedPacks(root, installed);
            CapabilityDescriptionCatalogV01 descriptions = variant.equals("B") ? enhanced(root, packs) : null;
            FinanceProvider recorder = recorder(providerOperations);
            var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults("synthetic-customer", "synthetic-customer",
                    FinanceContextV1.PrincipalType.CUSTOMER, session, "AB_EVALUATION",
                    FinanceContextV1.AuthenticationLevel.STRONG,
                    Set.of("account:read", "cashflow:read", "wealth:facts:read", "transfer:create"));
            var installer = new OpenJiuwenCapabilityInstaller();
            boolean installTerminology = true;
            for (FinanceCapabilityPackV1 pack : packs) {
                Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> bindings = new LinkedHashMap<>();
                pack.capabilities().forEach(capability -> bindings.put(capability.capabilityId(),
                        new OpenJiuwenCapabilityInstaller.ProviderBinding("isolated-recorder", "0.1.0", recorder)));
                installations.add(installer.install(agent, bindings, defaults, pack, true,
                        new TaskCheckpointStore.Memory(), session, descriptions, installTerminology));
                installTerminology = false;
            }
            List<String> turns = new ArrayList<>();
            if (testCase.has("turns")) testCase.path("turns").forEach(value -> turns.add(value.asText()));
            else turns.add(testCase.path("input").asText());
            for (String turn : turns) {
                int before = calls.size();
                try {
                    Runner.runAgent(agent, Map.of("query", turn), session, null);
                } finally {
                    callsByTurn.add(List.copyOf(calls.subList(before, calls.size())));
                }
            }
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
        } finally {
            for (int i = installations.size() - 1; i >= 0; i--) installations.get(i).close();
            Runner.release(session);
        }
        String expected = testCase.path("expected").asText();
        String expectedId = testCase.path("capabilityId").asText("");
        List<String> evaluatedCalls = callsByTurn.isEmpty() ? List.of() : callsByTurn.getLast();
        String actualLast = evaluatedCalls.isEmpty() ? "" : toolCapability(evaluatedCalls.getLast());
        boolean passed = (expected.equals("NO_TOOL_CALL") ? evaluatedCalls.isEmpty() : expectedId.equals(actualLast))
                && providerOperations.get() == 0;
        return Map.ofEntries(
                Map.entry("id", testCase.path("id").asText()),
                Map.entry("category", testCase.path("category").asText()),
                Map.entry("variant", variant), Map.entry("expected", expected),
                Map.entry("expectedCapabilityId", expectedId), Map.entry("toolCalls", List.copyOf(calls)),
                Map.entry("toolCallsByTurn", List.copyOf(callsByTurn)),
                Map.entry("evaluatedToolCalls", evaluatedCalls), Map.entry("passed", passed),
                Map.entry("error", error), Map.entry("providerOperations", providerOperations.get()),
                Map.entry("elapsedMillis", (System.nanoTime() - started) / 1_000_000L));
    }

    private ReActAgent agent(String session) {
        String url = System.getenv("FH_MODEL_URL"), name = System.getenv("FH_MODEL_NAME"), key = System.getenv("FH_MODEL_KEY");
        return agent(session, url, name, key);
    }

    private ReActAgent agent(String session, String url, String name, String key) {
        var agent = new ReActAgent(AgentCard.builder().id(session).name("FH A/B evaluator").build());
        var config = ReActAgentConfig.builder().maxIterations(2).promptTemplate(List.of(Map.of("role", "system", "content",
                "你是银行助手。只处理当前用户请求，只能调用已安装工具。咨询、否定、取消和未支持请求不得调用办理工具；缺少办理参数时先澄清。")))
                .build().configureModelClient("OpenAI", key, url, name, true);
        config.setModelClientConfig(ModelClientConfig.builder().clientProvider("OpenAI").apiKey(key)
                .apiBase(url).timeout(30).maxRetries(0).verifySsl(true).build());
        config.getModelConfigObj().setMaxTokens(600);
        agent.configure(config);
        return agent;
    }

    private List<FinanceCapabilityPackV1> selectedPacks(Path root, Set<String> selected) throws Exception {
        var loader = new FinanceCapabilityPackLoader();
        List<FinanceCapabilityPackV1> packs = new ArrayList<>();
        for (String id : List.of("account-balance-readonly", "cashflow-readonly", "wealth-facts-description", "internal-transfer")) {
            var pack = loader.load(root.resolve("capability-packs/" + id + "/pack.yaml"));
            List<FinanceCapabilityPackV1.Capability> capabilities = pack.capabilities().stream()
                    .filter(capability -> selected.contains(capability.capabilityId())).toList();
            if (!capabilities.isEmpty()) packs.add(new FinanceCapabilityPackV1(pack.schemaVersion(), pack.packId(),
                    pack.version(), pack.systemPrompt(), capabilities));
        }
        return packs;
    }

    private CapabilityDescriptionCatalogV01 enhanced(Path root, List<FinanceCapabilityPackV1> packs) throws Exception {
        List<Path> files = packs.stream().map(pack -> root.resolve("routing/capability-descriptions/"
                + pack.packId() + ".v0.1.json")).toList();
        return CapabilityDescriptionCatalogV01.load(packs, files,
                List.of(root.resolve("routing/terminology/demo-bank-zh-cn.v0.1.json")));
    }

    private FinanceProvider recorder(AtomicInteger operations) {
        return new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                operations.incrementAndGet(); return result(request, "isolated query result");
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                operations.incrementAndGet(); return result(request, "isolated task blocked before bank");
            }
            private CapabilityResultV1 result(CapabilityRequestV1 request, String message) {
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                        request.context().traceId(), CapabilityResultV1.Status.FAILED, message, Map.of(), List.of(),
                        List.of(), "isolated-recorder", Instant.now(), null, null);
            }
        };
    }

    private Map<String, Object> summarize(List<Map<String, Object>> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String variant : List.of("A", "B")) {
            var selected = results.stream().filter(row -> row.get("variant").equals(variant)).toList();
            long passed = selected.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count();
            long unsafe = selected.stream().filter(row -> row.get("expected").equals("NO_TOOL_CALL")
                    && !((List<?>) row.get("evaluatedToolCalls")).isEmpty()).count();
            long millis = selected.stream().mapToLong(row -> ((Number) row.get("elapsedMillis")).longValue()).sum();
            summary.put(variant, Map.of("total", selected.size(), "passed", passed, "failed", selected.size() - passed,
                    "unexpectedToolCalls", unsafe, "totalElapsedMillis", millis));
        }
        return summary;
    }

    private static String toolCapability(String tool) {
        return switch (tool) {
            case "account_balance_query" -> "account.balance.query";
            case "finance_transaction_summary" -> "finance.transaction-summary.query";
            case "finance_spending_analysis" -> "finance.spending-analysis.query";
            case "wealth_facts_describe" -> "wealth.facts.describe";
            case "transfer_internal_create" -> "transfer.internal.create";
            default -> "unknown:" + tool;
        };
    }
    private static void requireEnvironment(String... names) {
        for (String name : names) if (System.getenv(name) == null || System.getenv(name).isBlank())
            throw new IllegalStateException(name + " is required");
    }
    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null && !Files.isDirectory(current.resolve("capability-packs"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("Repository root not found");
        return current;
    }
}
