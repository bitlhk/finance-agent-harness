package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Bounded observations over one explicitly dated/currency-bound wealth snapshot. */
final class PrevisitDerivedFacts {
    static ObjectNode derive(JsonNode facts) {
        ObjectNode result = HarnessJson.mapper().createObjectNode();
        var items = result.putArray("items");
        var unavailable = result.putArray("unavailable");
        int wealth = -1;
        for (int i = 0; i < facts.path("profiles").size(); i++)
            if ("wealth".equals(facts.path("profiles").path(i).path("profileType").asText())) {
                if (wealth >= 0) { unavailable.add("AMBIGUOUS_WEALTH_PROFILE"); return result; }
                wealth = i;
            }
        if (wealth < 0) { unavailable.add("WEALTH_PROFILE_MISSING"); return result; }
        JsonNode detail = facts.path("profiles").path(wealth).path("detail");
        boolean nested = detail.path("portfolioSummary").isObject();
        JsonNode summary = nested ? detail.path("portfolioSummary") : detail;
        String root = "/profiles/" + wealth + "/detail";
        String portfolio = root + (nested ? "/portfolioSummary" : "");
        String currency = summary.path("currency").asText("");
        String date = summary.path("dataAsOf").asText(facts.path("dataAsOf").asText(""));
        if (!currency.matches("[A-Z]{3}") || !validDate(date)) {
            unavailable.add("RATIO_CURRENCY_OR_DATE_UNCONFIRMED"); return result;
        }
        ratio(items, unavailable, "CASH_SHARE", summary.path("availableCash"), summary.path("totalAssets"),
                portfolio + "/availableCash", portfolio + "/totalAssets", currency, date);
        for (int i = 0; i < detail.path("holdings").size(); i++) {
            JsonNode holding = detail.path("holdings").path(i);
            if (!currency.equals(holding.path("currency").asText(""))
                    || !date.equals(holding.path("dataAsOf").asText(""))) {
                unavailable.add("HOLDING_" + i + "_BASIS_UNCONFIRMED"); continue;
            }
            int size = items.size();
            ratio(items, unavailable, "HOLDING_SHARE", holding.path("balance"), summary.path("investedAssets"),
                    root + "/holdings/" + i + "/balance", portfolio + "/investedAssets", currency, date);
            if (items.size() > size && holding.path("productName").isTextual())
                ((ObjectNode) items.get(size)).put("productName", holding.path("productName").asText());
        }
        return result;
    }

    private static void ratio(com.fasterxml.jackson.databind.node.ArrayNode items,
            com.fasterxml.jackson.databind.node.ArrayNode unavailable, String code, JsonNode numerator,
            JsonNode denominator, String numeratorRef, String denominatorRef, String currency, String date) {
        if (!numerator.isNumber() || !denominator.isNumber() || numerator.decimalValue().signum() < 0
                || denominator.decimalValue().signum() <= 0
                || numerator.decimalValue().compareTo(denominator.decimalValue()) > 0) {
            unavailable.add(code + "_OPERANDS_INVALID"); return;
        }
        ObjectNode item = items.addObject();
        item.put("code", code); item.put("kind", "SAME_SNAPSHOT_RATIO");
        item.put("percent", numerator.decimalValue().multiply(new BigDecimal("100"))
                .divide(denominator.decimalValue(), 2, RoundingMode.HALF_UP));
        item.putArray("operandRefs").add(numeratorRef).add(denominatorRef);
        item.put("currency", currency); item.put("dataAsOf", date);
        item.put("rounding", "HALF_UP_2_DECIMAL_PLACES");
        item.put("boundary", "历史快照计算，不代表当前资产、政策底线或资金去向；未独立核实来源");
    }

    private static boolean validDate(String value) {
        try { java.time.Instant.parse(value); return true; }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }
}
