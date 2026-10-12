package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;
import java.util.function.Function;

/** Admission stays in the Host; projections never execute tools or parse model prose. */
final class DisplayProjection {
    private DisplayProjection() { }
    static List<Object> cards(List<Object> tools, Set<String> installed) {
        return cards(tools, installed, BusinessDisplays.PROJECTIONS);
    }
    static List<Object> cards(List<Object> tools, Set<String> installed,
            Map<String, Function<JsonNode, List<Object>>> projections) {
        var cards = new ArrayList<Object>();
        for (Object tool : tools) {
            JsonNode data = HarnessJson.mapper().valueToTree(tool).path("data");
            String capability = data.path("capabilityId").asText();
            if (!installed.contains(capability)) continue;
            if (!"COMPLETED".equals(data.path("resultStatus").asText())) {
                cards.add(Map.of("type", "status", "status", data.path("resultStatus").asText("UNAVAILABLE")));
                continue;
            }
            var projection = projections.get(capability);
            if (projection != null) cards.addAll(projection.apply(data));
        }
        return cards;
    }
}
