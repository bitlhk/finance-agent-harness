package io.github.bitlhk.finance.harness.wealth;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** A read-only projection, not authorization, suitability, or a transaction result. */
public record WealthFactsV1(String schemaVersion, Subject subject, Assets assets, Risk risk,
                           List<Gap> gaps, Source source) {
    public static final String VERSION = "wealth-facts.v0.1";
    public WealthFactsV1 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Unsupported wealth schema");
        Objects.requireNonNull(subject); Objects.requireNonNull(assets); Objects.requireNonNull(risk);
        Objects.requireNonNull(source); gaps = List.copyOf(gaps);
    }
    public enum Availability { AVAILABLE, MISSING, UNAVAILABLE, NOT_COVERED, EXPIRED, CONFLICT }
    public enum SourceKind { CONTROLLED_FIXTURE, UPSTREAM_RESPONSE }
    public record Subject(String namespace, String id) {
        public Subject { text(namespace); text(id); }
    }
    public record Money(String value, String currency) {
        public Money {
            if (value == null || !value.matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,8})?"))
                throw new IllegalArgumentException("Non-negative exact decimal amount required; no rounding");
            if (currency == null || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency required");
            new BigDecimal(value);
        }
    }
    public record AssetAmount(Money money, String scope, Instant valuationAt) {
        public AssetAmount { Objects.requireNonNull(money); text(scope); }
    }
    public record Assets(Availability availability, List<AssetAmount> amounts, List<String> missingSources) {
        public Assets {
            Objects.requireNonNull(availability); amounts = List.copyOf(amounts); missingSources = List.copyOf(missingSources);
            missingSources.forEach(WealthFactsV1::text);
            if ((availability == Availability.AVAILABLE || availability == Availability.EXPIRED) != !amounts.isEmpty())
                throw new IllegalArgumentException("Asset state/value mismatch");
        }
    }
    public record Risk(Availability availability, String level, String scheme, String assessmentVersion,
                       String assessedOn, String expiresOn, String dateZone) {
        public Risk {
            Objects.requireNonNull(availability);
            if ((availability == Availability.AVAILABLE || availability == Availability.EXPIRED) != (level != null))
                throw new IllegalArgumentException("Risk state/value mismatch");
            for (String value : new String[]{level, scheme, assessmentVersion, dateZone}) if (value != null) text(value);
            if (assessedOn != null) validateDate(assessedOn);
            if (expiresOn != null) validateDate(expiresOn);
            if (dateZone != null) java.time.ZoneId.of(dateZone);
        }
    }
    public record Gap(String field, Availability reason, String code) {
        public Gap { text(field); text(code); Objects.requireNonNull(reason);
            if (reason == Availability.AVAILABLE) throw new IllegalArgumentException("Gap cannot be AVAILABLE"); }
    }
    public record Source(String reference, String contract, String semanticsBasis, SourceKind kind,
                         Instant observedAt, String payloadSha256) {
        public Source {
            text(reference); text(contract); text(semanticsBasis); Objects.requireNonNull(kind); Objects.requireNonNull(observedAt);
            if (payloadSha256 == null || !payloadSha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Digest required");
        }
    }
    static void text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Nonblank contract text required");
    }
    static void validateDate(String value) {
        if (value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) java.time.LocalDate.parse(value);
        else java.time.OffsetDateTime.parse(value);
    }
}
