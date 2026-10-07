package io.github.bitlhk.finance.harness.providers.nonaibanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Bank-specific contracts. Neither pagination nor the business period is inferred. */
final class AccountHistoryQueries {
    private AccountHistoryQueries() { }
    record Selection(String accountId, String route, Map<String, String> query, boolean statement) { }

    static Selection select(boolean statement, Map<String, Object> parameters) {
        Set<String> allowed = statement ? Set.of("accountId", "period")
                : Set.of("accountId", "dateFrom", "dateTo", "cursor", "limit");
        if (!allowed.containsAll(parameters.keySet())) throw new IllegalArgumentException("Unknown parameter");
        String account = text(parameters.get("accountId"));
        if (!account.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) throw new IllegalArgumentException("Invalid account");
        Map<String, String> query = new LinkedHashMap<>();
        if (statement) {
            String period = text(parameters.get("period"));
            if (!period.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("Invalid period");
            YearMonth.parse(period);
            query.put("period", period);
        } else {
            String from = text(parameters.get("dateFrom")), to = text(parameters.get("dateTo"));
            if (!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || !to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || LocalDate.parse(from).isAfter(LocalDate.parse(to))) throw new IllegalArgumentException("Invalid dates");
            query.put("dateFrom", from); query.put("dateTo", to);
            if (parameters.containsKey("cursor")) {
                String cursor = text(parameters.get("cursor"));
                if (!cursor.matches("[A-Za-z0-9_-]{1,512}")) throw new IllegalArgumentException("Invalid cursor");
                query.put("cursor", cursor);
            }
            Object value = parameters.getOrDefault("limit", 10);
            if (!(value instanceof Number)) throw new IllegalArgumentException("Invalid limit");
            int limit = new BigDecimal(value.toString()).intValueExact();
            if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid limit");
            query.put("limit", Integer.toString(limit));
        }
        return new Selection(account, "/api/v1/accounts/" + account + (statement ? "/statement" : "/transaction-page"),
                Map.copyOf(query), statement);
    }

    static Map<String, Object> map(Selection selection, Map<String, Object> source) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "account-history-facts.v1");
        facts.put("accountId", selection.accountId());
        if (selection.statement()) {
            equal(selection.accountId(), source.get("accountId"));
            equal(selection.query().get("period"), source.get("period"));
            facts.put("period", source.get("period"));
            facts.put("currency", currency(source.get("currency")));
            for (String field : List.of("openingBalance", "totalIn", "totalOut", "closingBalance")) {
                Object value = source.get(field);
                if (value != null) facts.put(field, decimal(value));
            }
            facts.put("scope", "MONTHLY_SUMMARY_NOT_OFFICIAL_STATEMENT");
        } else {
            if (!(source.get("items") instanceof List<?> items) || !(source.get("hasMore") instanceof Boolean more))
                throw new IllegalArgumentException("Invalid page");
            if (items.size() > Integer.parseInt(selection.query().get("limit"))) throw new IllegalArgumentException("Oversized page");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> row)) throw new IllegalArgumentException("Invalid transaction");
                equal(selection.accountId(), row.get("accountId"));
                String bookedAt = text(row.get("bookedAt"));
                LocalDate date = Instant.parse(bookedAt).atZone(java.time.ZoneOffset.UTC).toLocalDate();
                if (date.isBefore(LocalDate.parse(selection.query().get("dateFrom")))
                        || date.isAfter(LocalDate.parse(selection.query().get("dateTo")))) throw new IllegalArgumentException("Out of scope transaction");
                Map<String, Object> result = new LinkedHashMap<>();
                for (String field : List.of("transactionId", "direction", "summary", "status"))
                    if (row.get(field) != null) result.put(field, text(row.get(field)));
                result.put("bookedAt", bookedAt);
                result.put("currency", currency(row.get("currency")));
                if (row.get("amount") != null) result.put("amount", decimal(row.get("amount")));
                rows.add(Map.copyOf(result));
            }
            facts.put("items", List.copyOf(rows)); facts.put("hasMore", more);
            Object cursor = source.get("nextCursor");
            if (more) {
                String next = text(cursor);
                if (!next.matches("[A-Za-z0-9_-]{1,512}") || next.equals(selection.query().get("cursor")))
                    throw new IllegalArgumentException("Invalid next cursor");
                facts.put("nextCursor", next);
            }
            facts.put("dateFrom", selection.query().get("dateFrom")); facts.put("dateTo", selection.query().get("dateTo"));
            facts.put("scope", "CURRENT_PAGE_ONLY");
        }
        return Map.copyOf(facts);
    }

    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new IllegalArgumentException("Source scope mismatch");
    }
    private static String currency(Object value) {
        String result = text(value);
        if (!result.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid currency");
        return result;
    }
    private static String decimal(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) throw new IllegalArgumentException("Invalid amount");
        String raw = value.toString();
        if (!raw.matches("-?[0-9]{1,24}(\\.[0-9]{1,8})?")) throw new IllegalArgumentException("Invalid amount");
        return new BigDecimal(raw).toPlainString();
    }
    private static String text(Object value) {
        if (!(value instanceof String result) || result.isBlank() || result.length() > 2048)
            throw new IllegalArgumentException("Missing or invalid text");
        return result;
    }
}
