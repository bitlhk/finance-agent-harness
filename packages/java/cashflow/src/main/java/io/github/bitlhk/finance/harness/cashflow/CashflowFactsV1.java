package io.github.bitlhk.finance.harness.cashflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

/** Read-only cashflow facts, not an asset valuation, suitability or fraud decision. */
public record CashflowFactsV1(String schemaVersion, Section section, String period, String currency,
        Coverage coverage, Totals totals, List<Category> categories, List<TrendPoint> trend,
        Source source, List<String> gaps) {
    public static final String VERSION = "finance-cashflow-facts.v0.1";
    public enum Section { SUMMARY, ANALYSIS }
    public enum Coverage { OWN_BANK_ACCOUNT_STATEMENT, OWN_BANK_BOOKED_WITH_CATEGORIES }

    public CashflowFactsV1 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("CASHFLOW_VERSION");
        Objects.requireNonNull(section); Objects.requireNonNull(coverage); Objects.requireNonNull(totals);
        Objects.requireNonNull(source); month(period);
        if (!"CNY".equals(currency)) throw new IllegalArgumentException("CASHFLOW_CURRENCY_NOT_SUPPORTED");
        categories = List.copyOf(categories); trend = List.copyOf(trend); gaps = List.copyOf(gaps);
        if (categories.size() > 500 || gaps.stream().distinct().count() != gaps.size()
                || gaps.stream().anyMatch(g -> !g.matches("[A-Z][A-Z0-9_]+")))
            throw new IllegalArgumentException("CASHFLOW_COLLECTION_INVALID");
        if (section == Section.SUMMARY && (coverage != Coverage.OWN_BANK_ACCOUNT_STATEMENT
                || !categories.isEmpty() || !trend.isEmpty())
                || section == Section.ANALYSIS && coverage != Coverage.OWN_BANK_BOOKED_WITH_CATEGORIES)
            throw new IllegalArgumentException("CASHFLOW_COVERAGE_MISMATCH");
        if (!totals.complete() && !gaps.contains("TOTALS_NOT_RETURNED"))
            throw new IllegalArgumentException("CASHFLOW_UNDISCLOSED_MISSING_TOTALS");
        if (source.asOf() == null && !gaps.contains("SOURCE_TIME_NOT_RETURNED"))
            throw new IllegalArgumentException("CASHFLOW_UNDISCLOSED_MISSING_TIME");
        if (section == Section.ANALYSIS) {
            if (trend.isEmpty() && !gaps.contains("TREND_NOT_RETURNED"))
                throw new IllegalArgumentException("CASHFLOW_UNDISCLOSED_MISSING_TREND");
            if (!trend.isEmpty()) {
                if (trend.size() != 6) throw new IllegalArgumentException("CASHFLOW_TREND_COVERAGE");
                for (int i = 0; i < trend.size(); i++)
                    if (!month(period).minusMonths(5L - i).equals(month(trend.get(i).period())))
                        throw new IllegalArgumentException("CASHFLOW_TREND_ORDER");
                if (totals.complete() && !totals.equals(trend.getLast().totals()) && !gaps.contains("TREND_TOTALS_CONFLICT"))
                    throw new IllegalArgumentException("CASHFLOW_UNDISCLOSED_TREND_CONFLICT");
            }
            if (categories.stream().anyMatch(c -> "UNCLASSIFIED".equals(c.name())) && !gaps.contains("CLASSIFICATION_NOT_RETURNED"))
                throw new IllegalArgumentException("CASHFLOW_UNDISCLOSED_CLASSIFICATION_GAP");
            if (categories.stream().map(Category::name).distinct().count() != categories.size())
                throw new IllegalArgumentException("CASHFLOW_DUPLICATE_CATEGORY");
            if (!gaps.contains("CATEGORIES_NOT_RETURNED") && totals.complete()) {
                BigDecimal income = categories.stream().map(c -> new BigDecimal(c.totals.income)).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal expense = categories.stream().map(c -> new BigDecimal(c.totals.expense)).reduce(BigDecimal.ZERO, BigDecimal::add);
                if (income.compareTo(new BigDecimal(totals.income)) != 0 || expense.compareTo(new BigDecimal(totals.expense)) != 0)
                    throw new IllegalArgumentException("CASHFLOW_CATEGORY_TOTAL_CONFLICT");
            }
        }
    }

    public record Totals(@JsonDeserialize(using = DecimalText.class) String income,
                         @JsonDeserialize(using = DecimalText.class) String expense,
                         @JsonDeserialize(using = DecimalText.class) String netCashflow) {
        public Totals {
            decimal(income, false); decimal(expense, false); decimal(netCashflow, true);
            if (income != null && expense != null && netCashflow != null
                    && new BigDecimal(income).subtract(new BigDecimal(expense)).compareTo(new BigDecimal(netCashflow)) != 0)
                throw new IllegalArgumentException("CASHFLOW_TOTALS_CONFLICT");
        }
        public boolean complete() { return income != null && expense != null && netCashflow != null; }
    }
    public static final class DecimalText extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws java.io.IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "CASHFLOW_DECIMAL_STRING_REQUIRED");
            return parser.getText();
        }
    }
    public record Category(String name, Totals totals, int transactionCount, String classificationVersion) {
        public Category {
            text(name); Objects.requireNonNull(totals); text(classificationVersion);
            if (transactionCount < 0 || !totals.complete()) throw new IllegalArgumentException("CASHFLOW_CATEGORY_INVALID");
        }
    }
    public record TrendPoint(String period, Totals totals) {
        public TrendPoint { month(period); Objects.requireNonNull(totals);
            if (!totals.complete()) throw new IllegalArgumentException("CASHFLOW_TREND_INVALID"); }
    }
    public record Source(String namespace, String subjectRef, String system, String recordVersion,
                         Instant asOf, Instant observedAt, String policyRef, Instant policyAsOf) {
        public Source { text(namespace); text(subjectRef); text(system); text(recordVersion); text(policyRef);
            Objects.requireNonNull(observedAt); Objects.requireNonNull(policyAsOf);
            if (policyAsOf.isAfter(observedAt) || asOf != null && asOf.isAfter(observedAt))
                throw new IllegalArgumentException("CASHFLOW_FUTURE_SOURCE_TIME"); }
    }
    public static YearMonth month(String value) {
        if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])"))
            throw new IllegalArgumentException("CASHFLOW_PERIOD_REQUIRED");
        return YearMonth.parse(value);
    }
    static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 200) throw new IllegalArgumentException("CASHFLOW_TEXT_INVALID");
    }
    static void decimal(String value, boolean signed) {
        if (value == null) return;
        if (!value.matches((signed ? "-?" : "") + "(0|[1-9][0-9]{0,29})\\.[0-9]{2}"))
            throw new IllegalArgumentException("CASHFLOW_DECIMAL_INVALID");
    }
}
