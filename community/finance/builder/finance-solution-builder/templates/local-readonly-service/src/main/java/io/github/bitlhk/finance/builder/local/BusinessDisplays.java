package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;

/** Trusted build-time registrations, never loaded from model output or user scripts. */
final class BusinessDisplays {
    private BusinessDisplays() { }
    static final Map<String, Function<JsonNode, List<Object>>> PROJECTIONS = registrations();
    private static Map<String, Function<JsonNode, List<Object>>> registrations() {
        var result = new LinkedHashMap<String, Function<JsonNode, List<Object>>>(Map.of(
            "account.balance.query", BalanceDisplay::project,
            "finance.transaction-summary.query", CashflowDisplay::project,
            "finance.spending-analysis.query", CashflowDisplay::project,
            "wealth.facts.describe", WealthDisplay::project));
        StructuredDisplay.registrations().forEach((capability, projection) -> {
            if (result.putIfAbsent(capability, projection) != null)
                throw new IllegalArgumentException("Pack cannot override an existing presentation");
        });
        return Map.copyOf(result);
    }
}
