package io.github.bitlhk.finance.harness.providers.nonaibanking;

import io.github.bitlhk.finance.harness.cashflow.CashflowFactsV1;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import static io.github.bitlhk.finance.harness.cashflow.CashflowFactsV1.*;

/** Mapping for the documented non-ai-banking response plus its observed policy endpoint. */
public final class CashflowMapper {
    private CashflowMapper() { }
    public static CashflowFactsV1 map(Section section, String period, String subject, String sourceSystem,
            Map<String, Object> data, Map<String, Object> readiness, Instant observedAt) {
        YearMonth anchor = month(period);
        Map<String, Object> policy = validatePolicy(section, readiness, observedAt);
        boolean analysis = section == Section.ANALYSIS;
        String versionKey = analysis ? "analysisVersion" : "summaryVersion";
        String version = string(policy.get(versionKey));
        if (!period.equals(data.get("period")) || !version.equals(data.get(versionKey)))
            throw new IllegalArgumentException("CASHFLOW_SOURCE_IDENTITY_MISMATCH");
        return mapFacts(section, period, subject, sourceSystem, data, readiness, observedAt, anchor, policy, analysis, version);
    }

    static Map<String, Object> validatePolicy(Section section, Map<String, Object> readiness, Instant observedAt) {
        if (!"PASS".equals(readiness.get("gate"))) throw new IllegalArgumentException("CASHFLOW_SOURCE_POLICY_BLOCKED");
        List<Map<String, Object>> policies = rows(readiness.get("policies"));
        if (policies.size() != 1) throw new IllegalArgumentException("CASHFLOW_SOURCE_POLICY_AMBIGUOUS");
        Map<String, Object> policy = policies.getFirst();
        boolean analysis = section == Section.ANALYSIS;
        if (!"CNY".equals(policy.get("currency"))
                || !"BOOKED".equals(policy.get(analysis ? "transactionStatus" : "accountTransactionStatus"))
                || !Boolean.TRUE.equals(policy.get("customerScopeRequired"))
                || !Boolean.FALSE.equals(policy.get("ledgerWriteAllowed"))
                || !Boolean.FALSE.equals(policy.get(analysis ? "riskConclusionAllowed" : "feeReinterpretationAllowed")))
            throw new IllegalArgumentException("CASHFLOW_SOURCE_POLICY_UNSUPPORTED");
        String version = string(policy.get(analysis ? "analysisVersion" : "summaryVersion"));
        if (!version.equals(analysis ? "spending-analysis-v1" : "financial-summary-v2"))
            throw new IllegalArgumentException("CASHFLOW_SOURCE_VERSION_UNSUPPORTED");
        if (analysis && integer(policy.get("trendMonths")) != 6)
            throw new IllegalArgumentException("CASHFLOW_TREND_POLICY_UNSUPPORTED");
        string(policy.get("evidenceRef"));
        if (Instant.parse(string(readiness.get("asOf"))).isAfter(observedAt))
            throw new IllegalArgumentException("CASHFLOW_POLICY_TIME_INVALID");
        return policy;
    }

    private static CashflowFactsV1 mapFacts(Section section, String period, String subject, String sourceSystem,
            Map<String, Object> data, Map<String, Object> readiness, Instant observedAt, YearMonth anchor,
            Map<String, Object> policy, boolean analysis, String version) {
        List<String> gaps = new ArrayList<>();
        Totals totals = totals(data, "totalIncome", "totalExpense");
        if (!totals.complete()) gaps.add("TOTALS_NOT_RETURNED");
        Instant asOf = data.get("asOf") == null ? null : Instant.parse(string(data.get("asOf")));
        if (asOf == null) gaps.add("SOURCE_TIME_NOT_RETURNED");
        if (asOf != null && asOf.isAfter(observedAt)) throw new IllegalArgumentException("CASHFLOW_SOURCE_TIME_INVALID");
        List<Category> categories = new ArrayList<>(); List<TrendPoint> trend = new ArrayList<>();
        if (analysis) {
            if (data.get("categories") == null) gaps.add("CATEGORIES_NOT_RETURNED");
            else {
                Set<String> names = new HashSet<>();
                for (var row : rows(data.get("categories"))) {
                    String name = string(row.get("category"));
                    if (!names.add(name)) throw new IllegalArgumentException("CASHFLOW_DUPLICATE_CATEGORY");
                    String income = amount(row.get("income")), expense = amount(row.get("expense"));
                    if (income == null || expense == null) throw new IllegalArgumentException("CASHFLOW_CATEGORY_INCOMPLETE");
                    categories.add(new Category(name, new Totals(income, expense,
                            new BigDecimal(income).subtract(new BigDecimal(expense)).toPlainString()),
                            integer(row.get("transactionCount")), string(row.get("classificationVersion"))));
                }
                if (totals.complete()) {
                    BigDecimal income = categories.stream().map(c -> new BigDecimal(c.totals().income())).reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal expense = categories.stream().map(c -> new BigDecimal(c.totals().expense())).reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (income.compareTo(new BigDecimal(totals.income())) != 0 || expense.compareTo(new BigDecimal(totals.expense())) != 0)
                        throw new IllegalArgumentException("CASHFLOW_CATEGORY_TOTAL_CONFLICT");
                }
            }
            int months = integer(policy.get("trendMonths"));
            if (months != 6) throw new IllegalArgumentException("CASHFLOW_TREND_POLICY_UNSUPPORTED");
            if (data.get("trend") == null) gaps.add("TREND_NOT_RETURNED");
            else {
                List<Map<String, Object>> points = rows(data.get("trend"));
                if (points.size() != months) throw new IllegalArgumentException("CASHFLOW_TREND_COVERAGE_INVALID");
                for (int i = 0; i < months; i++) {
                    var point = points.get(i); String expected = anchor.minusMonths(months - 1L - i).toString();
                    if (!expected.equals(point.get("period"))) throw new IllegalArgumentException("CASHFLOW_TREND_PERIOD_INVALID");
                    trend.add(new TrendPoint(expected, totals(point, "income", "expense")));
                }
            }
            gaps.add("UNCLASSIFIED_TRANSACTIONS_NOT_COVERED");
        } else gaps.add("ACCOUNT_LIST_LIMIT_100");
        gaps.add("OTHER_BANKS_AND_CURRENCIES_NOT_COVERED");
        gaps.add("CREDIT_CARD_BILLS_NOT_ADDED_TO_CASHFLOW");
        Source source = new Source("non-ai-banking", subject, sourceSystem, version, asOf, observedAt,
                string(policy.get("evidenceRef")), Instant.parse(string(readiness.get("asOf"))));
        return new CashflowFactsV1(VERSION, section, period, "CNY",
                analysis ? Coverage.OWN_BANK_CLASSIFIED_BOOKED : Coverage.OWN_BANK_ACCOUNT_BOOKED,
                totals, categories, trend, source, gaps);
    }

    private static Totals totals(Map<String, Object> row, String in, String out) {
        return new Totals(amount(row.get(in)), amount(row.get(out)), amount(row.get("netCashflow")));
    }
    private static String amount(Object value) {
        if (value == null) return null;
        if (!(value instanceof BigDecimal || value instanceof java.math.BigInteger || value instanceof Integer
                || value instanceof Long || value instanceof String)) throw new IllegalArgumentException("CASHFLOW_INEXACT_NUMBER");
        return new BigDecimal(value.toString()).setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
    private static int integer(Object value) {
        if (!(value instanceof Number) || value instanceof Double || value instanceof Float)
            throw new IllegalArgumentException("CASHFLOW_INTEGER_INVALID");
        return new BigDecimal(value.toString()).intValueExact();
    }
    private static String string(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("CASHFLOW_SOURCE_FIELD_MISSING");
        if (text.isBlank() || text.length() > 200) throw new IllegalArgumentException("CASHFLOW_TEXT_INVALID");
        return text;
    }
    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 500) throw new IllegalArgumentException("CASHFLOW_ROWS_INVALID");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(k -> !(k instanceof String)))
                throw new IllegalArgumentException("CASHFLOW_ROW_INVALID");
            Map<String, Object> item = new LinkedHashMap<>(); map.forEach((k,v) -> item.put((String)k,v)); result.add(item);
        }
        return result;
    }
}
