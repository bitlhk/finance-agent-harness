package io.github.bitlhk.finance.harness.quotation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Versioned read-only facts, not permission, source authentication or an executed transaction. */
public record QuoteFactsV01(String schemaVersion, String subjectRef, String productRef,
        String currency, State state, Quote quote, List<String> gaps, Source source) {
    public static final String VERSION = "quote-facts.v0.1";
    public enum State { AVAILABLE, NOT_APPLICABLE, MISSING, UNAVAILABLE, EXPIRED, CONFLICT }
    public static final Set<String> GAP_CODES = Set.of("NOT_APPLICABLE", "MISSING_ELIGIBILITY",
        "MISSING_QUOTE", "SOURCE_UNAVAILABLE", "NOT_YET_VALID", "QUOTE_EXPIRED", "FACT_CONFLICT");
    public QuoteFactsV01 {
        require(VERSION.equals(schemaVersion)); id(subjectRef); id(productRef); currency(currency);
        Objects.requireNonNull(state); Objects.requireNonNull(source); gaps = List.copyOf(gaps);
        require(gaps.size() <= 8 && new HashSet<>(gaps).size() == gaps.size() && GAP_CODES.containsAll(gaps));
        require(state == State.AVAILABLE ? quote != null && gaps.isEmpty() : quote == null && !gaps.isEmpty());
    }
    public record Money(String value, String currency) {
        public Money {
            require(value != null && value.matches("(?:0|[1-9][0-9]{0,15})[.][0-9]{2}"));
            QuoteFactsV01.currency(currency);
        }
        public BigInteger minorUnits() { return new BigInteger(value.replace(".", "")); }
    }
    public record Condition(String code, String state) {
        public Condition { require("DEMO_MEMBER_ELIGIBLE".equals(code) && "SATISFIED".equals(state)); }
    }
    public record Quote(String quoteId, Money listPrice, Money referencePrice, int priceFactorBps,
            String priceBasis, String validFrom, String validUntil, String eligibility,
            List<Condition> conditions, List<String> claimCodes, String effect) {
        public Quote {
            id(quoteId); Objects.requireNonNull(listPrice); Objects.requireNonNull(referencePrice);
            require(priceFactorBps >= 0 && priceFactorBps <= 10000);
            require("PER_UNIT".equals(priceBasis) && "ELIGIBLE".equals(eligibility));
            require("INFORMATION_ONLY".equals(effect)); instant(validFrom); instant(validUntil);
            conditions = List.copyOf(conditions); claimCodes = List.copyOf(claimCodes);
            require(!conditions.isEmpty() && conditions.size() <= 8
                && new HashSet<>(conditions).size() == conditions.size());
            require(!claimCodes.isEmpty() && claimCodes.size() <= 2
                && new HashSet<>(claimCodes).size() == claimCodes.size()
                && claimCodes.contains("REFERENCE_UNIT_PRICE")
                && Set.of("REFERENCE_UNIT_PRICE", "CONDITIONAL_PRICE").containsAll(claimCodes));
        }
    }
    public record Source(String sourceKind, String sourceId, String sourceVersion, String recordRef,
            String observedAt, String snapshotSha256) {
        public Source {
            require("CONTROLLED_FIXTURE".equals(sourceKind)); id(sourceId); id(sourceVersion);
            id(recordRef); instant(observedAt); digest(snapshotSha256);
        }
    }
    public static void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("STRUCTURE_INVALID");
    }
    public static void id(String value) { require(value != null && value.matches("[A-Za-z0-9._:-]{1,128}")); }
    public static void currency(String value) { require("CNY".equals(value)); }
    public static void digest(String value) { require(value != null && value.matches("[a-f0-9]{64}")); }
    public static Instant instant(String value) {
        require(value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z"));
        return java.time.OffsetDateTime.parse(value).toInstant();
    }
}
