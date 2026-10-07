package io.github.bitlhk.finance.harness.providers.nonaibanking;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccountHistoryQueriesTest {
    private static Map<String, Object> statement() { return Map.of("accountId", "synthetic-1", "period", "2026-08"); }
    private static Map<String, Object> page() { return Map.of("accountId", "synthetic-1", "dateFrom", "2026-08-01", "dateTo", "2026-08-31"); }
    private static Map<String, Object> row() { return Map.of("accountId", "synthetic-1", "transactionId", "txn-1", "amount", "12.30", "currency", "CNY", "bookedAt", "2026-08-02T00:00:00Z"); }
    @Test void statementRequiresExplicitScopeAndNeverInventsMissingAmounts() {
        var selected = AccountHistoryQueries.select(true, statement());
        var source = new HashMap<String, Object>(statement());
        source.put("currency", "CNY"); source.put("totalIn", "12.30"); source.put("privateField", "not-exposed");
        var facts = AccountHistoryQueries.map(selected, source);
        assertEquals("12.30", facts.get("totalIn"));
        assertFalse(facts.containsKey("closingBalance")); assertFalse(facts.containsKey("privateField"));
        assertEquals("MONTHLY_SUMMARY_NOT_OFFICIAL_STATEMENT", facts.get("scope"));
        source.put("period", "2026-07");
        assertThrows(IllegalArgumentException.class, () -> AccountHistoryQueries.map(selected, source));
    }
    @Test void rejectsInvalidParametersBeforeTransport() {
        for (var value : List.<Map<String, Object>>of(Map.of(), Map.of("accountId", "../other", "period", "2026-08"),
                Map.of("accountId", "synthetic-1", "period", "2026-13"),
                Map.of("accountId", "synthetic-1", "period", "2026-08", "customerId", "other")))
            assertThrows(RuntimeException.class, () -> AccountHistoryQueries.select(true, new HashMap<>(value)));
        for (Object limit : List.of(0, 101, 1.5, "10")) {
            var parameters = new HashMap<>(page()); parameters.put("limit", limit);
            assertThrows(RuntimeException.class, () -> AccountHistoryQueries.select(false, parameters));
        }
        var parameters = new HashMap<>(page()); parameters.put("dateTo", "2026-07-31");
        assertThrows(RuntimeException.class, () -> AccountHistoryQueries.select(false, parameters));
    }
    @Test void onePageIsNotCompleteHistory() {
        var selection = AccountHistoryQueries.select(false, page());
        var facts = AccountHistoryQueries.map(selection, Map.of("items", List.of(row()), "hasMore", true, "nextCursor", "Y3Vyc29y"));
        assertEquals(true, facts.get("hasMore")); assertEquals("CURRENT_PAGE_ONLY", facts.get("scope"));
        assertEquals("10", selection.query().get("limit"));
        assertEquals(List.of(), AccountHistoryQueries.map(selection, Map.of("items", List.of(), "hasMore", false)).get("items"));
        assertThrows(IllegalArgumentException.class, () -> AccountHistoryQueries.map(selection, Map.of("items", List.of(row()), "hasMore", true)));
    }
    @Test void rejectsCrossAccountDateAndOversizedResults() {
        var selection = AccountHistoryQueries.select(false, page());
        for (var change : List.of(Map.of("accountId", "other"), Map.of("bookedAt", "2026-07-31T00:00:00Z"), Map.of("amount", "NaN"))) {
            var row = new HashMap<>(row()); row.putAll(change);
            assertThrows(RuntimeException.class, () -> AccountHistoryQueries.map(selection, Map.of("items", List.of(row), "hasMore", false)));
        }
        assertThrows(IllegalArgumentException.class, () -> AccountHistoryQueries.map(selection, Map.of("items", Collections.nCopies(11, row()), "hasMore", false)));
    }
}
