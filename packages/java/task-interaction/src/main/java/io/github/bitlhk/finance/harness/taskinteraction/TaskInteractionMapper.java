package io.github.bitlhk.finance.harness.taskinteraction;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;

/** Strict source boundary; rejects extra fields and binds every snapshot to Host-selected identity. */
public final class TaskInteractionMapper {
    private static final ObjectMapper JSON = HarnessJson.mapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> SNAPSHOT = Set.of("schemaVersion", "taskRef", "sourceObjectRef", "sourceCommit", "sourceRelease",
        "version", "materialVersion", "originalState", "displayState", "allowedActions", "inputRequired", "rawResult", "unresolvedRequestIds");
    private TaskInteractionMapper() { }
    public record Expected(String taskRef, String sourceObjectRef, String sourceRelease) {
        public Expected { id(taskRef); id(sourceObjectRef); hash(sourceRelease); }
    }
    public static JsonNode parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 262144) invalid();
        try { return JSON.readTree(bytes); }
        catch (Exception failure) { throw new IllegalArgumentException("TASK_INTERACTION_JSON_INVALID"); }
    }
    public static ObjectMapper json() { return JSON.copy(); }
    public static TaskInteractionV1 snapshot(byte[] bytes, Expected expected) {
        JsonNode n = parse(bytes); fields(n, SNAPSHOT);
        if (!TaskInteractionV1.SCHEMA_VERSION.equals(text(n, "schemaVersion"))
                || !expected.taskRef().equals(text(n, "taskRef"))
                || !expected.sourceObjectRef().equals(text(n, "sourceObjectRef"))
                || !expected.sourceRelease().equals(text(n, "sourceRelease"))) invalid();
        return new TaskInteractionV1(text(n, "taskRef"), text(n, "sourceObjectRef"), text(n, "sourceCommit"),
            text(n, "sourceRelease"), text(n, "version"), text(n, "materialVersion"), text(n, "originalState"),
            text(n, "displayState"), strings(n.get("allowedActions")), strings(n.get("inputRequired")),
            n.get("rawResult"), strings(n.get("unresolvedRequestIds")));
    }
    public static void fields(JsonNode n, Set<String> expected) {
        if (n == null || !n.isObject()) invalid();
        Set<String> actual = new HashSet<>(); n.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) invalid();
    }
    public static String text(JsonNode n, String key) {
        var v = n.get(key);
        if (v == null || !v.isTextual() || v.asText().isEmpty() || v.asText().length() > 2048) invalid();
        return v.asText();
    }
    public static List<String> strings(JsonNode value) {
        if (value == null || !value.isArray() || value.size() > 1000) invalid();
        List<String> values = new ArrayList<>();
        for (var item : value) {
            if (!item.isTextual()) invalid();
            values.add(item.asText());
        }
        return List.copyOf(values);
    }
    public static void id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) invalid();
    }
    public static void hash(String value) {
        if (value == null || !value.matches("sha256:[0-9a-f]{64}")) invalid();
    }
    public static void invalid() { throw new IllegalArgumentException("TASK_INTERACTION_INVALID"); }
}
