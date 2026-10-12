package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.github.bitlhk.finance.builder.local.DisplayFields.*;

/** Business-specific, allowlisted projection of the existing result contract. */
final class CashflowDisplay {
    private CashflowDisplay() { }
    static List<Object> project(JsonNode data) {
        var cards = new ArrayList<Object>();
        var source = pick(data, "sourceSystem", "asOf");
        String capability = data.path("capabilityId").asText();
        JsonNode facts = data.path("facts");
        String section = capability.equals("finance.transaction-summary.query") ? "SUMMARY" : "ANALYSIS";
        if (!"finance-cashflow-facts.v0.1".equals(facts.path("schemaVersion").asText())
                || !section.equals(facts.path("section").asText())) {
            cards.add(Map.of("type", "status", "status", "UNSUPPORTED_RESULT_VERSION")); return cards;
        }
        var card = pick(facts, "section", "period", "currency", "coverage");
        card.put("type", "cashflow"); card.put("version", "0.1");
        card.put("totals", pick(facts.path("totals"), "income", "expense", "netCashflow"));
        card.put("source", pick(facts.path("source"), "system", "recordVersion", "asOf", "observedAt", "policyRef", "policyAsOf"));
        card.put("gaps", strings(facts.path("gaps")));
        var categories = new ArrayList<Object>();
        for (JsonNode category : facts.path("categories")) {
            var row = pick(category, "name", "classificationVersion");
            row.put("totals", pick(category.path("totals"), "income", "expense", "netCashflow"));
            if (category.path("transactionCount").isIntegralNumber()) row.put("transactionCount", category.path("transactionCount").intValue());
            categories.add(row);
        }
        card.put("categories", categories);
        var trend = new ArrayList<Object>();
        for (JsonNode point : facts.path("trend")) {
            var row = pick(point, "period");
            row.put("totals", pick(point.path("totals"), "income", "expense", "netCashflow"));
            trend.add(row);
        }
        card.put("trend", trend); cards.add(card);

        return cards;
    }
}
