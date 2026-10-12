package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.github.bitlhk.finance.builder.local.DisplayFields.*;

/** Business-specific, allowlisted projection of the existing result contract. */
final class WealthDisplay {
    private WealthDisplay() { }
    static List<Object> project(JsonNode data) {
        var cards = new ArrayList<Object>();
        var source = pick(data, "sourceSystem", "asOf");
        String capability = data.path("capabilityId").asText();
        JsonNode facts = data.path("facts");
        if (!"wealth-facts.v0.1".equals(facts.path("schemaVersion").asText())) {
            cards.add(Map.of("type", "status", "status", "UNSUPPORTED_RESULT_VERSION")); return cards;
        }
        source = pick(facts.path("source"), "reference", "contract", "observedAt", "kind");
        var amounts = new ArrayList<Object>();
        for (JsonNode amount : facts.path("assets").path("amounts")) {
            var row = pick(amount, "scope", "valuationAt");
            row.put("money", pick(amount.path("money"), "value", "currency")); amounts.add(row);
        }
        cards.add(Map.of("type", "wealth", "version", "0.1", "amounts", amounts, "source", source,
                "availability", facts.path("assets").path("availability").asText("MISSING"),
                "missingSources", strings(facts.path("assets").path("missingSources"))));
        cards.add(Map.of("type", "risk", "version", "0.1", "source", source,
                "record", pick(facts.path("risk"), "availability", "level", "scheme", "assessmentVersion", "assessedOn", "expiresOn", "dateZone"),
                "suitability", "NOT_EVALUATED"));
        var gaps = new ArrayList<Object>();
        for (JsonNode gap : data.path("description").path("gaps")) gaps.add(pick(gap, "field", "reason", "code"));
        cards.add(Map.of("type", "gaps", "gaps", gaps,
                "readiness", data.path("description").path("taskReadiness").asText("NOT_CHECKED")));
        return cards;
    }
}
