package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;
import java.util.function.Function;

/** Read-only views from the build-validated, artifact-locked pack manifest; no executable extensions. */
final class StructuredDisplay {
    private StructuredDisplay() { }
    static Map<String, Function<JsonNode, List<Object>>> registrations() {
        try (var stream = StructuredDisplay.class.getResourceAsStream("/capability-presentations.json")) {
            if (stream == null) return Map.of(); // Historical builds have no pack views.
            JsonNode manifest = HarnessJson.mapper().readTree(stream);
            if (!"finance-pack-presentation.v0.1".equals(manifest.path("schemaVersion").asText())
                    || !manifest.path("views").isArray()) throw new IllegalArgumentException("Invalid presentation manifest");
            var registry = new LinkedHashMap<String, Function<JsonNode, List<Object>>>();
            for (JsonNode view : manifest.path("views")) {
                String capability = view.path("capabilityId").asText();
                if (registry.putIfAbsent(capability, data -> project(data, view)) != null)
                    throw new IllegalArgumentException("Duplicate presentation");
            }
            return Map.copyOf(registry);
        } catch (java.io.IOException invalid) {
            throw new IllegalStateException("Cannot load presentation manifest", invalid);
        }
    }

    static List<Object> project(JsonNode data, JsonNode view) {
        JsonNode facts = data.path("facts");
        if (!view.path("factsVersion").asText().equals(facts.path("schemaVersion").asText())) return List.of();
        var columns = new ArrayList<Object>();
        var keys = new ArrayList<String>();
        view.path("fields").fields().forEachRemaining(field -> {
            keys.add(field.getKey()); columns.add(Map.of("key", field.getKey(), "label", field.getValue().asText()));
        });
        var rows = new ArrayList<Object>();
        Iterable<JsonNode> source = "SELF".equals(view.path("rows").asText()) ? List.of(facts) : facts.path("items");
        for (JsonNode item : source) {
            if (rows.size() >= 100) break;
            var row = new LinkedHashMap<String, Object>();
            for (String key : keys) {
                JsonNode value = item.path(key);
                if ((value.isTextual() || value.isNumber() || value.isBoolean()) && value.asText().length() <= 2048)
                    row.put(key, value.asText());
            }
            rows.add(row);
        }
        var card = new LinkedHashMap<String, Object>();
        card.put("type", "records"); card.put("version", "0.1");
        card.put("title", view.path("title").asText()); card.put("scope", view.path("scope").asText());
        card.put("columns", columns); card.put("rows", rows);
        var context = DisplayFields.pick(facts, "period", "dateFrom", "dateTo");
        if (facts.path("hasMore").isBoolean()) context.put("hasMore", facts.path("hasMore").booleanValue());
        card.put("context", context);
        card.put("source", DisplayFields.pick(data, "sourceSystem", "asOf"));
        return List.of(card);
    }
}
