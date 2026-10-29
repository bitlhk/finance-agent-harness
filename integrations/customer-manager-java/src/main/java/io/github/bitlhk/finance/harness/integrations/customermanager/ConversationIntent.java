package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;

/** Untrusted model suggestion. This value cannot authorize or execute an action. */
record ConversationIntent(Kind kind, String objectRef, String reportRef, Integer revision) {
    static final String SCHEMA = "finance-conversation-intent.v1";
    enum Kind { GREETING, CAPABILITIES, LIST_OBJECTS, CLARIFY, SELECT_OBJECT, CANCEL_TASK, RESOLVE_OBJECT, CONTINUE_TASK, EXPLAIN_REPORT, PREPARE, PRODUCT_REVIEW, UNSUPPORTED }
    private static final Set<String> FIELDS = Set.of("schema", "kind", "objectRef", "reportRef", "revision");

    static ConversationIntent parse(String output, Set<String> authorizedObjects, Set<Kind> installedActions) {
        if (output == null || output.length() > 4096) throw invalid();
        try (JsonParser parser = HarnessJson.mapper().getFactory().createParser(output)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = HarnessJson.mapper().reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(parser);
            if (root == null || !root.isObject() || root.size() != FIELDS.size()) throw invalid();
            var names = new HashSet<String>(); root.fieldNames().forEachRemaining(names::add);
            if (!names.equals(FIELDS) || !SCHEMA.equals(root.path("schema").textValue())) throw invalid();
            if (!root.path("kind").isTextual()) throw invalid();
            Kind kind = Kind.valueOf(root.path("kind").textValue());
            String object = optionalText(root.get("objectRef"));
            String report = optionalText(root.get("reportRef"));
            JsonNode version = root.get("revision");
            Integer revision = null;
            if (!version.isNull()) {
                if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1) throw invalid();
                revision = version.asInt();
            }
            if (object != null && !authorizedObjects.contains(object)) {
                String hint=object;
                var matches=authorizedObjects.stream().filter(alias ->
                        CustomerManagerScenarioExtension.displayName(alias).equals(hint)).toList();
                if(matches.size()!=1) throw invalid();
                object=matches.getFirst();
            }
            if (kind == Kind.PREPARE || kind == Kind.PRODUCT_REVIEW) {
                if (!installedActions.contains(kind) || report != null || revision != null) throw invalid();
            } else if (kind == Kind.EXPLAIN_REPORT) {
                if ((report == null) != (revision == null)) throw invalid();
            } else if (kind == Kind.RESOLVE_OBJECT || kind == Kind.SELECT_OBJECT) {
                if(object==null||report!=null||revision!=null) throw invalid();
            } else if (kind == Kind.CLARIFY || kind == Kind.CONTINUE_TASK) {
                // A validated object hint is not an action, grant, or report reference.
                if(report!=null||revision!=null) throw invalid();
            } else if (object != null || report != null || revision != null) throw invalid();
            return new ConversationIntent(kind, object, report, revision);
        } catch (Exception failure) {
            // Parser exceptions can include model/customer text; never propagate them publicly.
            throw invalid();
        }
    }

    boolean requiresExecutionConfirmation() { return kind == Kind.PREPARE || kind == Kind.PRODUCT_REVIEW; }

    private static String optionalText(JsonNode value) {
        if (value == null) throw invalid();
        if (value.isNull()) return null;
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length() > 200
                || !value.textValue().equals(value.textValue().strip())) throw invalid();
        return value.textValue();
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("CONVERSATION_INTENT_INVALID");
    }
}
