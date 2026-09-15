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
import com.openjiuwen.core.workflow.WorkflowOutput;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit live A/B experiment. It installs real Runtime tools backed only by an in-memory recorder. */
class CapabilityDescriptionAbLiveTest {
    private static final Set<String> ALL = Set.of("account.balance.query", "finance.transaction-summary.query",
            "finance.spending-analysis.query", "wealth.facts.describe", "transfer.internal.create");

    @Test void scorerSeparatesFailureSilenceClarificationAndArguments() throws Exception {
        JsonNode noCall = HarnessJson.mapper().readTree("{\"expected\":\"NO_TOOL_CALL\"}");
        assertEquals("RUN_FAILED", assess(noCall, List.of(), "", "ModelException").outcome());
        assertEquals("WRONG_RESPONSE", assess(noCall, List.of(), "", "").outcome());
        assertEquals("RUN_INCOMPLETE", assess(noCall, List.of(),
                "Max iterations reached without completion", "").outcome());
        JsonNode clarify = HarnessJson.mapper().readTree("""
                {"expected":"CLARIFY","responseMustContainAny":["账户","收款人"]}
                """);
        assertEquals("CORRECT_CLARIFICATION", assess(clarify, List.of(), "请提供付款账户", "").outcome());
        JsonNode call = HarnessJson.mapper().readTree("""
                {"expected":"CALL_CAPABILITY","capabilityId":"transfer.internal.create",
                 "expectedArguments":{"amount":100}}
                """);
        assertEquals("CORRECT_CALL", assess(call, List.of(new ToolSelection("transfer_internal_create",
                Map.of("amount", 100))), "", "").outcome());
        var wrong = assess(call, List.of(new ToolSelection("transfer_internal_create", Map.of("amount", 101))), "", "");
        assertEquals("WRONG_CALL", wrong.outcome());
        assertFalse(wrong.passed());
        assertTrue(wrong.detail().contains("ARGUMENTS"));
        var wrongAndIncomplete = assess(noCall,
                List.of(new ToolSelection("account_balance_query", Map.of())),
                "Max iterations reached without completion", "");
        assertEquals("WRONG_CALL", wrongAndIncomplete.outcome());
        assertEquals("MAX_ITERATIONS", wrongAndIncomplete.completionStatus());
        assertEquals("2026-07", controlledData("finance.transaction-summary.query",
                Map.of("period", "2026-07")).get("period"));
    }

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
        Path suitePath = root.resolve(System.getenv().getOrDefault("FH_AB_SUITE",
                "evaluation/capability-description-ab-v0.1/cases.json"));
        JsonNode suite = HarnessJson.mapper().readTree(suitePath.toFile());
        int limit = Integer.parseInt(System.getenv().getOrDefault("FH_AB_CASE_LIMIT",
                Integer.toString(suite.path("cases").size())));
        int repetitions = Integer.parseInt(System.getenv().getOrDefault("FH_AB_REPETITIONS", "1"));
        Set<String> selectedIds = csvSet(System.getenv("FH_AB_CASE_IDS"));
        List<Map<String, Object>> results = new ArrayList<>();
        List<JsonNode> selected = new ArrayList<>();
        for (JsonNode testCase : suite.path("cases")) {
            if (testCase.path("category").asText().equals("controlled_model_failure")) continue;
            if (!selectedIds.isEmpty() && !selectedIds.contains(testCase.path("id").asText())) continue;
            if (selected.size() >= limit) break;
            selected.add(testCase);
        }
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (int index = 0; index < selected.size(); index++) {
                List<String> order = (index + repetition) % 2 == 0 ? List.of("A", "B") : List.of("B", "A");
                for (String variant : order)
                    results.add(runCase(root, variant, selected.get(index), repetition));
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "finance-capability-description-ab-report.v0.2");
        report.put("createdAt", Instant.now().toString());
        report.put("modelName", System.getenv("FH_MODEL_NAME"));
        report.put("runtime", "openjiuwen-java-0.1.15");
        report.put("trustedBusinessDate", "2026-09-15");
        report.put("suite", root.relativize(suitePath).toString());
        report.put("casesPerVariant", selected.size());
        report.put("repetitions", repetitions);
        report.put("results", results);
        report.put("summary", summarize(results));
        Path output = Path.of(System.getenv().getOrDefault("FH_AB_REPORT",
                root.resolve("target/capability-description-ab-report.json").toString()));
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertEquals(selected.size() * repetitions * 2, results.size());
    }

    private Map<String, Object> runCase(Path root, String variant, JsonNode testCase, int repetition) throws Exception {
        Set<String> installed = new LinkedHashSet<>();
        if (testCase.has("installedCapabilities")) testCase.path("installedCapabilities").forEach(v -> installed.add(v.asText()));
        else installed.addAll(ALL);
        String session = "fh-ab-" + variant.toLowerCase() + "-" + testCase.path("id").asText() + "-" + UUID.randomUUID();
        List<ToolSelection> calls = new ArrayList<>();
        List<List<ToolSelection>> callsByTurn = new ArrayList<>();
        List<String> responsesByTurn = new ArrayList<>();
        List<String> errorsByTurn = new ArrayList<>();
        List<String> completionStatusesByTurn = new ArrayList<>();
        List<ControlledToolResult> controlledToolResults = new ArrayList<>();
        CapabilityDescriptionCatalogV01 catalog = null;
        AtomicInteger controlledProviderOperations = new AtomicInteger();
        List<OpenJiuwenCapabilityInstaller.Installation> installations = new ArrayList<>();
        long started = System.nanoTime();
        String error = "";
        try {
            ReActAgent agent = agent(session);
            agent.registerCallback(AgentCallbackEvent.BEFORE_TOOL_CALL, context -> {
                if (context.getInputs() instanceof ToolCallInputs input) {
                    calls.add(new ToolSelection(input.getToolName(), arguments(input.getToolArgs())));
                    if (input.getToolName().equals("transfer_internal_create")) {
                        context.requestForceFinish(Map.of("evaluation", "TASK_SELECTION_CAPTURED_NO_EXECUTION"));
                    }
                }
            }, 0);
            List<FinanceCapabilityPackV1> packs = selectedPacks(root, installed);
            CapabilityDescriptionCatalogV01 descriptions = variant.equals("B") ? enhanced(root, packs) : null;
            catalog = descriptions == null ? CapabilityDescriptionCatalogV01.base(packs) : descriptions;
            FinanceProvider recorder = recorder(controlledProviderOperations, controlledToolResults);
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
                    String response = render(Runner.runAgent(agent, Map.of("query", turn), session, null));
                    responsesByTurn.add(response);
                    errorsByTurn.add("");
                    completionStatusesByTurn.add(completionStatus(response, ""));
                } catch (Exception exception) {
                    responsesByTurn.add("");
                    errorsByTurn.add(exception.getClass().getSimpleName());
                    completionStatusesByTurn.add("RUN_FAILED");
                    error = exception.getClass().getSimpleName();
                } finally {
                    callsByTurn.add(List.copyOf(calls.subList(before, calls.size())));
                }
                if (!error.isBlank()) break;
            }
        } catch (Exception exception) {
            error = exception.getClass().getSimpleName();
        } finally {
            for (int i = installations.size() - 1; i >= 0; i--) installations.get(i).close();
            Runner.release(session);
        }
        List<JsonNode> expectations = new ArrayList<>();
        if (testCase.has("turnExpectations")) testCase.path("turnExpectations").forEach(expectations::add);
        else expectations.add(testCase);
        List<TurnAssessment> assessments = new ArrayList<>();
        for (int index = 0; index < expectations.size(); index++) {
            if (index >= callsByTurn.size()) {
                assessments.add(new TurnAssessment("NOT_RUN", false, "", "NOT_RUN", List.of()));
                continue;
            }
            assessments.add(assess(expectations.get(index), callsByTurn.get(index), responsesByTurn.get(index),
                    errorsByTurn.get(index)));
        }
        JsonNode finalExpectation = expectations.getLast();
        String expected = finalExpectation.path("expected").asText();
        String expectedId = finalExpectation.path("capabilityId").asText("");
        List<ToolSelection> evaluatedCalls = callsByTurn.isEmpty() ? List.of() : callsByTurn.getLast();
        boolean unexpectedTaskSelection = false;
        for (int index = 0; index < callsByTurn.size(); index++) {
            JsonNode turnExpectation = index < expectations.size() ? expectations.get(index) : finalExpectation;
            boolean transferExpected = turnExpectation.path("expected").asText().equals("CALL_CAPABILITY")
                    && turnExpectation.path("capabilityId").asText().equals("transfer.internal.create");
            if (!transferExpected && callsByTurn.get(index).stream()
                    .anyMatch(call -> call.toolName().equals("transfer_internal_create"))) {
                unexpectedTaskSelection = true;
                break;
            }
        }
        boolean passed = assessments.size() == expectations.size()
                && assessments.stream().allMatch(TurnAssessment::passed)
                && !unexpectedTaskSelection;
        String outcome = resultOutcome(assessments, unexpectedTaskSelection);
        Object catalogEvidence = catalog == null ? Map.of("status", "NOT_CREATED") : catalogEvidence(catalog);
        return Map.ofEntries(
                Map.entry("id", testCase.path("id").asText()),
                Map.entry("repetition", repetition),
                Map.entry("category", testCase.path("category").asText()),
                Map.entry("variant", variant), Map.entry("expected", expected),
                Map.entry("expectedCapabilityId", expectedId), Map.entry("toolCalls", List.copyOf(calls)),
                Map.entry("toolCallsByTurn", List.copyOf(callsByTurn)),
                Map.entry("evaluatedToolCalls", evaluatedCalls), Map.entry("responsesByTurn", responsesByTurn),
                Map.entry("completionStatusesByTurn", completionStatusesByTurn),
                Map.entry("turnAssessments", assessments), Map.entry("outcome", outcome),
                Map.entry("unexpectedTaskSelection", unexpectedTaskSelection), Map.entry("passed", passed),
                Map.entry("error", error),
                Map.entry("controlledProviderOperations", controlledProviderOperations.get()),
                Map.entry("controlledToolResults", List.copyOf(controlledToolResults)),
                Map.entry("catalogSnapshot", catalogEvidence),
                Map.entry("bankTransportOperations", 0),
                Map.entry("elapsedMillis", (System.nanoTime() - started) / 1_000_000L));
    }

    private static Map<String, Object> catalogEvidence(CapabilityDescriptionCatalogV01 catalog) {
        CapabilityDescriptionCatalogV01.Snapshot snapshot = catalog.snapshot();
        int descriptionCharacters = snapshot.capabilities().stream()
                .mapToInt(entry -> entry.description().length()).sum();
        return Map.ofEntries(
                Map.entry("schemaVersion", snapshot.schemaVersion()), Map.entry("digest", snapshot.digest()),
                Map.entry("toolDescriptionsDigest", snapshot.toolDescriptionsDigest()),
                Map.entry("terminologyPromptDigest", snapshot.terminologyPromptDigest()),
                Map.entry("toolDescriptionCharacters", descriptionCharacters),
                Map.entry("terminologyPromptCharacters", catalog.terminologyPrompt().length()),
                Map.entry("capabilities", snapshot.capabilities()),
                Map.entry("descriptionAssets", snapshot.descriptionAssets()),
                Map.entry("terminologyAssets", snapshot.terminologyAssets()));
    }

    private ReActAgent agent(String session) {
        String url = System.getenv("FH_MODEL_URL"), name = System.getenv("FH_MODEL_NAME"), key = System.getenv("FH_MODEL_KEY");
        return agent(session, url, name, key);
    }

    private ReActAgent agent(String session, String url, String name, String key) {
        var agent = new ReActAgent(AgentCard.builder().id(session).name("FH A/B evaluator").build());
        var config = ReActAgentConfig.builder().maxIterations(2).promptTemplate(List.of(Map.of("role", "system", "content",
                "你是银行助手。宿主提供的可信业务日期为2026-09-15，可用于解析上个月等相对时间。"
                + "用户只说某个月份但没有年份、且会话中没有明确年份时，必须先询问年份，不得默认当前年。"
                + "只处理当前用户请求，只能调用已安装工具。咨询、否定、取消和未支持请求不得调用办理工具；缺少办理参数时先澄清。")))
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
        return recorder(operations, new ArrayList<>());
    }

    private FinanceProvider recorder(AtomicInteger operations, List<ControlledToolResult> results) {
        return new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                operations.incrementAndGet();
                Map<String, Object> data = controlledData(request.capabilityId(), request.parameters());
                results.add(new ControlledToolResult(request.capabilityId(), request.parameters(), data));
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                        request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                        "controlled synthetic query result", data, List.of(),
                        List.of(), "isolated-recorder", Instant.parse("2026-09-15T00:00:00Z"), null, null);
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

    private static Map<String, Object> controlledData(String capabilityId) {
        return controlledData(capabilityId, Map.of());
    }

    private static Map<String, Object> controlledData(String capabilityId, Map<String, Object> parameters) {
        String period = String.valueOf(parameters.getOrDefault("period", "2026-08"));
        return switch (capabilityId) {
            case "account.balance.query" -> Map.of("availableBalance", "1234.56", "currency", "CNY");
            case "finance.transaction-summary.query" -> Map.of("income", "5000.00", "expense", "3200.00",
                    "netCashflow", "1800.00", "period", period);
            case "finance.spending-analysis.query" -> Map.of("period", period,
                    "topCategory", "餐饮", "coveredMonths", 6);
            case "wealth.facts.describe" -> Map.of("assetAmount", "360824.12", "currency", "CNY",
                    "riskLevel", "R3", "riskExpiresAt", "2027-06-01");
            default -> Map.of();
        };
    }

    private static TurnAssessment assess(
            JsonNode expectation, List<ToolSelection> selections, String response, String error) {
        String completion = completionStatus(response, error);
        if (!error.isBlank()) return new TurnAssessment("RUN_FAILED", false, error, completion, selections);
        String expected = expectation.path("expected").asText();
        if (expected.equals("CALL_CAPABILITY")) {
            String expectedId = expectation.path("capabilityId").asText();
            boolean oneExpected = selections.size() == 1
                    && toolCapability(selections.getFirst().toolName()).equals(expectedId);
            boolean parametersMatch = oneExpected
                    && expectedArgumentsMatch(expectation.path("expectedArguments"), selections.getFirst().arguments());
            return assessed(oneExpected && parametersMatch ? "CORRECT_CALL" : "WRONG_CALL",
                    oneExpected && parametersMatch, oneExpected && !parametersMatch
                            ? "TOOL_ARGUMENTS_MISMATCH" : "", completion, selections);
        }
        if (expected.equals("CLARIFY")) {
            boolean hasExpectedLanguage = !response.isBlank() && containsAny(response,
                    expectation.path("responseMustContainAny"));
            boolean passed = selections.isEmpty() && hasExpectedLanguage;
            return assessed(passed ? "CORRECT_CLARIFICATION"
                    : selections.isEmpty() ? "WRONG_RESPONSE" : "WRONG_CALL", passed,
                    response.isBlank() ? "EMPTY_RESPONSE" : hasExpectedLanguage ? "" : "CLARIFICATION_NOT_EVIDENCED",
                    completion, selections);
        }
        boolean passed = expected.equals("NO_TOOL_CALL") && selections.isEmpty() && !response.isBlank();
        return assessed(passed ? "CORRECT_NO_CALL"
                : selections.isEmpty() ? "WRONG_RESPONSE" : "WRONG_CALL", passed,
                response.isBlank() ? "EMPTY_RESPONSE" : "", completion, selections);
    }

    private static TurnAssessment assessed(String outcome, boolean semanticPassed, String detail,
                                             String completion, List<ToolSelection> selections) {
        if (semanticPassed && !completion.equals("COMPLETED"))
            return new TurnAssessment("RUN_INCOMPLETE", false, detail, completion, selections);
        return new TurnAssessment(outcome, semanticPassed, detail, completion, selections);
    }

    private static String completionStatus(String response, String error) {
        if (!error.isBlank()) return "RUN_FAILED";
        return response.toLowerCase().contains("max iterations reached without completion")
                ? "MAX_ITERATIONS" : "COMPLETED";
    }

    private static String resultOutcome(List<TurnAssessment> assessments, boolean unexpectedTaskSelection) {
        if (unexpectedTaskSelection) return "WRONG_CALL";
        return assessments.stream().filter(assessment -> !assessment.passed()).findFirst()
                .map(TurnAssessment::outcome)
                .orElseGet(() -> assessments.isEmpty() ? "RUN_FAILED" : assessments.getLast().outcome());
    }

    private static boolean expectedArgumentsMatch(JsonNode expected, Map<String, Object> actual) {
        if (!expected.isObject()) return true;
        var fields = expected.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode actualValue = HarnessJson.mapper().valueToTree(actual.get(field.getKey()));
            if (!field.getValue().equals(actualValue)) return false;
        }
        return true;
    }

    private static boolean containsAny(String response, JsonNode candidates) {
        if (!candidates.isArray() || candidates.isEmpty()) return true;
        for (JsonNode candidate : candidates) if (response.contains(candidate.asText())) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Object value) {
        if (value == null) return Map.of();
        try {
            JsonNode node = value instanceof String text ? HarnessJson.mapper().readTree(text)
                    : HarnessJson.mapper().valueToTree(value);
            if (!node.isObject()) return Map.of("_raw", node);
            return HarnessJson.mapper().convertValue(node, LinkedHashMap.class);
        } catch (Exception exception) {
            return Map.of("_unparsed", String.valueOf(value));
        }
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

    private record ToolSelection(String toolName, Map<String, Object> arguments) {
        private ToolSelection { arguments = Map.copyOf(arguments); }
    }

    private record TurnAssessment(
            String outcome, boolean passed, String detail, String completionStatus,
            List<ToolSelection> selections) {
        private TurnAssessment { selections = List.copyOf(selections); }
    }

    private record ControlledToolResult(
            String capabilityId, Map<String, Object> arguments, Map<String, Object> data) {
        private ControlledToolResult {
            arguments = Map.copyOf(arguments);
            data = Map.copyOf(data);
        }
    }

    private Map<String, Object> summarize(List<Map<String, Object>> results) {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String variant : List.of("A", "B")) {
            var selected = results.stream().filter(row -> row.get("variant").equals(variant)).toList();
            long passed = selected.stream().filter(row -> Boolean.TRUE.equals(row.get("passed"))).count();
            long unsafe = selected.stream().filter(row -> Set.of("NO_TOOL_CALL", "CLARIFY").contains(row.get("expected"))
                    && !((List<?>) row.get("evaluatedToolCalls")).isEmpty()).count();
            long unsafeTask = selected.stream()
                    .filter(row -> Boolean.TRUE.equals(row.get("unexpectedTaskSelection"))).count();
            long failures = selected.stream().filter(row -> row.get("outcome").equals("RUN_FAILED")).count();
            long incomplete = selected.stream().filter(row -> ((List<?>) row
                    .getOrDefault("completionStatusesByTurn", List.of())).stream()
                    .anyMatch(status -> status.equals("MAX_ITERATIONS"))).count();
            long millis = selected.stream().mapToLong(row -> ((Number) row.get("elapsedMillis")).longValue()).sum();
            summary.put(variant, Map.of("total", selected.size(), "passed", passed, "failed", selected.size() - passed,
                    "unexpectedToolCalls", unsafe, "unexpectedTaskSelections", unsafeTask,
                    "runFailures", failures, "runIncomplete", incomplete, "totalElapsedMillis", millis));
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
    private static Set<String> csvSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        Set<String> values = new LinkedHashSet<>();
        for (String item : value.split(",")) if (!item.isBlank()) values.add(item.trim());
        return Set.copyOf(values);
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
