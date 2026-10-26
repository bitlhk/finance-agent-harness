package io.github.bitlhk.finance.harness.quotation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Objects;

/** Pure fact validation: NOT authentication or proof of a Host disclosure. */
public final class QuoteDisclosureV01 {
    private QuoteDisclosureV01() { }
    public record ExpectedBinding(String subjectRef, String productRef, String currency,
            String contractVersion, String sourceId, String sourceVersion, String snapshotSha256) {
        public ExpectedBinding {
            QuoteFactsV01.id(subjectRef); QuoteFactsV01.id(productRef); QuoteFactsV01.currency(currency);
            QuoteFactsV01.require(QuoteFactsV01.VERSION.equals(contractVersion));
            QuoteFactsV01.id(sourceId); QuoteFactsV01.id(sourceVersion); QuoteFactsV01.digest(snapshotSha256);
        }
    }
    public record Projection(QuoteFactsV01 facts) {
        public Projection { Objects.requireNonNull(facts); QuoteFactsV01.require(facts.state() == QuoteFactsV01.State.AVAILABLE); }
    }
    public record Decision(String reason, Projection projection) {
        public Decision { QuoteFactsV01.require("ALLOWED".equals(reason) == (projection != null)); }
        public boolean allowed() { return projection != null; }
    }
    private static Decision deny(String reason) { return new Decision(reason, null); }
    /** Host must obtain a current real grant before calling at EACH public boundary. */
    public static Decision validateForDisclosure(QuoteFactsV01 facts, ExpectedBinding expected, Instant now) {
        Objects.requireNonNull(facts); Objects.requireNonNull(expected); Objects.requireNonNull(now);
        if (!facts.subjectRef().equals(expected.subjectRef())) return deny("SUBJECT_MISMATCH");
        if (!facts.productRef().equals(expected.productRef())) return deny("PRODUCT_MISMATCH");
        if (!facts.currency().equals(expected.currency())) return deny("CURRENCY_MISMATCH");
        var source = facts.source();
        if (!facts.schemaVersion().equals(expected.contractVersion())
                || !source.sourceId().equals(expected.sourceId())
                || !source.sourceVersion().equals(expected.sourceVersion())
                || !source.snapshotSha256().equals(expected.snapshotSha256())) return deny("SOURCE_MISMATCH");
        if (QuoteFactsV01.instant(source.observedAt()).isAfter(now)) return deny("SOURCE_TIME_INVALID");
        if (facts.state() != QuoteFactsV01.State.AVAILABLE) return deny(facts.state().name());
        var quote = facts.quote();
        var from = QuoteFactsV01.instant(quote.validFrom()); var until = QuoteFactsV01.instant(quote.validUntil());
        if (!from.isBefore(until)) return deny("INVALID_INTERVAL");
        if (now.isBefore(from)) return deny("NOT_YET_VALID");
        if (!now.isBefore(until)) return deny("QUOTE_EXPIRED");
        BigInteger list = quote.listPrice().minorUnits(), price = quote.referencePrice().minorUnits();
        if (!price.multiply(BigInteger.valueOf(10000)).equals(list.multiply(BigInteger.valueOf(quote.priceFactorBps())))
                || (list.signum() == 0 && quote.priceFactorBps() != 10000)) return deny("PRICE_CONFLICT");
        if (quote.claimCodes().contains("CONDITIONAL_PRICE") != (quote.priceFactorBps() < 10000))
            return deny("CLAIM_CONFLICT");
        return new Decision("ALLOWED", new Projection(facts));
    }
}
