package io.github.bitlhk.finance.harness.analysis;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Pure bounded metadata analysis. Snapshot digest identifies provenance, not permission. */
public final class ObservationAnalyzer {
    public static final String RULE_VERSION = "finance-single-run.v1";
    public static final int MAX_INPUT = 1_048_576, MAX_OUTPUT = 262_144;
    private static final long MAX_COUNT = 9_007_199_254_740_991L;
    private static final Set<String> OUTCOMES = Set.of("PENDING", "RETURNED", "COMPLETED", "ERROR",
            "DENIED", "SKIPPED", "INCOMPLETE", "CANCEL_REQUESTED", "CANCELLED", "UNKNOWN");
    private static final Set<String> GAPS = Set.of("MISSING_TERMINAL", "MISSING_PARENT", "TRUNCATED",
            "ARCHIVE_DEGRADED", "HISTORY_INCOMPLETE", "NATIVE_UNAVAILABLE", "NATIVE_LINK_UNVERIFIED",
            "NATIVE_LIFECYCLE_GAP", "NATIVE_PARENT_UNVERIFIED", "NATIVE_SEQUENCE_GAP",
            "NATIVE_COVERAGE_MISSING", "NATIVE_CAPTURE_GAP", "NATIVE_ARCHIVE_GAP");
    private static final List<String> BINDING = List.of("requestId", "instanceId", "processBootId",
            "runtimeLockDigest", "snapshotDigest", "ruleVersion");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                    .maxStringLength(512).maxNumberLength(16).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ObservationAnalyzer() { }

    public static JsonNode parse(byte[] bytes) throws java.io.IOException {
        require(bytes.length <= MAX_INPUT); return JSON.readTree(bytes);
    }
    public static byte[] encode(JsonNode value) throws java.io.IOException { return JSON.writeValueAsBytes(value); }
    private static void require(boolean value) {
        if (!value) throw new IllegalArgumentException("ANALYSIS_INPUT_INVALID");
    }
    private static void fields(JsonNode row, Set<String> required, Set<String> optional) {
        require(row != null && row.isObject()); require(required.stream().allMatch(row::has));
        row.fieldNames().forEachRemaining(key -> require(required.contains(key) || optional.contains(key)));
    }
    private static String text(JsonNode value) {
        require(value != null && value.isTextual()); return value.textValue();
    }
    private static String id(JsonNode value) {
        String result = text(value); require(result.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}")); return result;
    }
    private static String member(JsonNode value, Set<String> allowed) {
        String result = text(value); require(allowed.contains(result)); return result;
    }
    private static List<String> refs(JsonNode value, Set<String> evidence, int min, int max) {
        require(value != null && value.isArray() && value.size() >= min && value.size() <= max);
        List<String> result = new ArrayList<>();
        for (JsonNode row : value) { String key = id(row); require(evidence.contains(key) && !result.contains(key)); result.add(key); }
        return result;
    }
    private static void validate(JsonNode input) {
        fields(input, Set.of("schemaVersion", "requestId", "instanceId", "processBootId", "runtimeLockDigest",
                "snapshotDigest", "ruleVersion", "steps", "coverage", "evidence", "terminal"), Set.of());
        require("finance.observation-analysis-input.v1".equals(text(input.get("schemaVersion"))));
        require(RULE_VERSION.equals(text(input.get("ruleVersion"))));
        for (String key : List.of("requestId", "instanceId", "processBootId")) id(input.get(key));
        for (String key : List.of("runtimeLockDigest", "snapshotDigest")) require(text(input.get(key)).matches("[a-f0-9]{64}"));
        JsonNode evidence = input.get("evidence"); require(evidence.isArray() && evidence.size() <= 256);
        Set<String> ids = new HashSet<>();
        for (JsonNode row : evidence) {
            fields(row, Set.of("id", "kind"), Set.of()); require(ids.add(id(row.get("id"))));
            member(row.get("kind"), Set.of("HOST_EVENT", "NATIVE_SPAN", "TERMINAL", "COVERAGE"));
        }
        JsonNode steps = input.get("steps"); require(steps.isArray() && steps.size() <= 128);
        Set<String> stepIds = new HashSet<>();
        for (JsonNode row : steps) {
            fields(row, Set.of("id", "source", "kind", "outcome", "dispatch", "evidenceIds"),
                    Set.of("businessStatus", "parentId", "clockGroup", "durationNanos"));
            require(stepIds.add(id(row.get("id"))));
            member(row.get("source"), Set.of("HOST_FINANCIAL", "SDK_NATIVE"));
            member(row.get("kind"), Set.of("CONTROL", "PROVIDER", "CAPABILITY", "MODEL", "TOOL", "RUN", "WORKFLOW", "OTHER"));
            member(row.get("outcome"), OUTCOMES); member(row.get("dispatch"), Set.of("NOT_OBSERVED", "NOT_SENT", "SENT"));
            if (row.has("businessStatus")) member(row.get("businessStatus"), Set.of("COMPLETED", "FAILED", "DENIED", "CANCELLED", "UNKNOWN"));
            if (row.hasNonNull("parentId")) id(row.get("parentId"));
            if (row.has("clockGroup")) id(row.get("clockGroup"));
            if (row.has("durationNanos")) {
                JsonNode count = row.get("durationNanos");
                require(count.isIntegralNumber() && count.canConvertToLong() && count.longValue() >= 0
                        && count.longValue() <= MAX_COUNT && row.has("clockGroup"));
            }
            refs(row.get("evidenceIds"), ids, 1, 8);
        }
        JsonNode coverage = input.get("coverage");
        fields(coverage, Set.of("mode", "sampled", "complete", "gaps", "evidenceIds"), Set.of());
        member(coverage.get("mode"), Set.of("off", "sample", "full", "UNKNOWN"));
        require(coverage.get("sampled").isBoolean() || coverage.get("sampled").isNull());
        require(coverage.get("complete").isBoolean());
        refs(coverage.get("gaps"), GAPS, 0, 32); refs(coverage.get("evidenceIds"), ids, 0, 8);
        if (!input.get("terminal").isNull()) {
            fields(input.get("terminal"), Set.of("status", "evidenceIds"), Set.of());
            member(input.get("terminal").get("status"), Set.of("COMPLETED", "ERROR", "DENIED", "SKIPPED", "CANCELLED", "INCOMPLETE"));
            refs(input.get("terminal").get("evidenceIds"), ids, 1, 8);
        }
    }
    private static boolean parentGap(JsonNode steps) {
        Map<String, String> parents = new HashMap<>();
        for (JsonNode step : steps) parents.put(step.get("id").textValue(), step.path("parentId").asText(null));
        for (String root : parents.keySet()) {
            Set<String> visited = new HashSet<>(); String current = root;
            while (current != null) {
                if (!parents.containsKey(current) || !visited.add(current)) return true;
                current = parents.get(current);
            }
        }
        return false;
    }
    private static List<JsonNode> matching(JsonNode steps, java.util.function.Predicate<JsonNode> predicate) {
        List<JsonNode> result = new ArrayList<>(); steps.forEach(row -> { if (predicate.test(row)) result.add(row); }); return result;
    }
    private static ArrayNode strings(Iterable<String> values) {
        ArrayNode result = JSON.createArrayNode(); values.forEach(result::add); return result;
    }
    private static void finding(ArrayNode output, String classification, String rule, String reason,
                                List<JsonNode> steps, List<String> extraEvidence) {
        Set<String> ids = new TreeSet<>(), refs = new TreeSet<>(extraEvidence);
        for (JsonNode step : steps) {
            ids.add(step.get("id").textValue()); step.get("evidenceIds").forEach(value -> refs.add(value.textValue()));
        }
        if (refs.isEmpty() && !classification.equals("UNKNOWN")) return;
        ObjectNode item = output.addObject().put("classification", classification).put("ruleId", rule).put("reasonCode", reason);
        item.set("evidenceIds", strings(refs)); item.set("stepIds", strings(ids));
    }

    /** Does not mutate input or call filesystem, network, environment, models, tools or banks. */
    public static JsonNode analyze(JsonNode input) {
        validate(input);
        ObjectNode result = JSON.createObjectNode().put("schemaVersion", "finance.observation-analysis-result.v1");
        for (String key : BINDING) result.set(key, input.get(key).deepCopy());
        JsonNode steps = input.get("steps"), coverage = input.get("coverage"), terminal = input.get("terminal");
        result.set("coverage", coverage.deepCopy());
        List<JsonNode> business = matching(steps, row -> row.path("source").asText().equals("HOST_FINANCIAL")
                && Set.of("PROVIDER", "CAPABILITY").contains(row.path("kind").asText()) && row.has("businessStatus"));
        Set<String> states = new HashSet<>(); business.forEach(row -> states.add(row.get("businessStatus").textValue()));
        result.put("business", states.isEmpty() || states.contains("UNKNOWN") ? "UNKNOWN"
                : states.size() == 1 ? states.iterator().next() : "MIXED");
        result.put("technical", terminal.isNull() ? "UNKNOWN" : terminal.get("status").textValue());
        boolean gapped = !coverage.get("complete").booleanValue() || !coverage.get("gaps").isEmpty()
                || terminal.isNull() || parentGap(steps) || !coverage.path("mode").asText().equals("full")
                || !coverage.path("sampled").asBoolean(false);
        result.put("integrity", gapped ? "GAPPED" : "COMPLETE");
        if (coverage.path("mode").asText().equals("UNKNOWN") && coverage.get("gaps").isEmpty()
                && coverage.get("complete").booleanValue() && !terminal.isNull() && !parentGap(steps)) result.put("integrity", "UNKNOWN");
        ArrayNode findings = result.putArray("findings");
        if (result.get("business").textValue().equals("UNKNOWN")) finding(findings, "UNKNOWN", "FIN-001", "BUSINESS_RESULT_UNKNOWN", List.of(), List.of());
        List<JsonNode> failed = business.stream().filter(row -> row.path("businessStatus").asText().equals("FAILED")).toList();
        if (!failed.isEmpty()) {
            finding(findings, "FACT", "FIN-002", "BUSINESS_RESULT_FAILED", failed, List.of());
            finding(findings, "RECOMMENDATION", "FIN-010", "CHECK_BUSINESS_RESPONSE", failed, List.of());
        }
        List<JsonNode> denied = matching(steps, row -> row.path("source").asText().equals("HOST_FINANCIAL")
                && row.path("kind").asText().equals("CONTROL") && row.path("outcome").asText().equals("DENIED"));
        if (!denied.isEmpty()) finding(findings, "FACT", "FIN-003", "CONTROL_DENIED_OBSERVED", denied, List.of());
        List<JsonNode> errors = matching(steps, row -> row.path("outcome").asText().equals("ERROR"));
        if (!errors.isEmpty()) {
            // Group observed errors; never count independent root causes from propagation.
            finding(findings, "FACT", "FIN-004", "TECHNICAL_FAILURE_OBSERVED", errors, List.of());
            finding(findings, "UNKNOWN", "FIN-005", "CAUSE_NOT_ESTABLISHED", errors, List.of());
            finding(findings, "RECOMMENDATION", "FIN-011", "CHECK_FAILURE_EVIDENCE", errors, List.of());
        }
        if (!result.get("integrity").textValue().equals("COMPLETE")) {
            List<String> refs = new ArrayList<>(); coverage.get("evidenceIds").forEach(value -> refs.add(value.textValue()));
            finding(findings, "UNKNOWN", "FIN-006", "RECORD_COVERAGE_GAP", List.of(), refs);
            finding(findings, "RECOMMENDATION", "FIN-012", "CHECK_RECORD_COVERAGE", List.of(), refs);
        }
        Map<String, JsonNode> longest = new TreeMap<>();
        for (JsonNode step : steps) {
            if (!step.has("durationNanos") || Set.of("PENDING", "CANCEL_REQUESTED").contains(step.path("outcome").asText())) continue;
            String group = step.get("source").textValue() + ":" + step.get("clockGroup").textValue();
            JsonNode previous = longest.get(group);
            if (previous == null || step.get("durationNanos").longValue() > previous.get("durationNanos").longValue()
                    || (step.get("durationNanos").longValue() == previous.get("durationNanos").longValue()
                    && step.get("id").textValue().compareTo(previous.get("id").textValue()) < 0)) longest.put(group, step);
        }
        int groups = 0;
        for (JsonNode step : longest.values()) {
            if (groups++ == 16) { finding(findings, "UNKNOWN", "FIN-008", "DURATION_GROUP_LIMIT", List.of(), List.of()); break; }
            finding(findings, "STATISTIC", "FIN-007", "LONGEST_RECORDED_INTERVAL", List.of(step), List.of());
            ((ObjectNode) findings.get(findings.size() - 1)).put("clockGroup", step.get("clockGroup").textValue())
                    .put("durationNanos", step.get("durationNanos").longValue());
        }
        return result;
    }
}
