package io.github.bitlhk.finance.harness.marketrisk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Finite original capital facts; source date is never replaced by calculation time. */
public record MarketRiskCapitalV1(
        String schemaVersion, String requestId, Values values,
        @JsonProperty("param_version") String paramVersion,
        @JsonProperty("engine_version") String engineVersion,
        JsonNode detail, @JsonProperty("ir_breakdown") JsonNode irBreakdown,
        Source source, Instant calculatedAt, String toolReleaseDigest,
        String upstreamRevision, String parametersDigest, String originalFunction) {

    public static final String SCHEMA_VERSION = "finance-market-risk-capital.v1";
    public static final String ORIGINAL_FUNCTION = "mktrisk_engine.standard.compute_standard_capital";

    public MarketRiskCapitalV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion) || !ORIGINAL_FUNCTION.equals(originalFunction)) invalid();
        requestId = text(requestId); paramVersion = text(paramVersion); engineVersion = text(engineVersion);
        Objects.requireNonNull(values); Objects.requireNonNull(source); Objects.requireNonNull(calculatedAt);
        hash(toolReleaseDigest); hash(parametersDigest);
        if (upstreamRevision == null || !upstreamRevision.matches("[a-f0-9]{40}")) invalid();
        if (detail == null || irBreakdown == null) invalid();
        detail = detail.deepCopy(); irBreakdown = irBreakdown.deepCopy();
    }

    @Override public JsonNode detail() { return detail.deepCopy(); }
    @JsonProperty("ir_breakdown") @Override public JsonNode irBreakdown() { return irBreakdown.deepCopy(); }

    public record Values(@JsonProperty("interest_rate") double interestRate,
                         double fx, double equity, double commodity, double total) {
        public Values {
            for (double v : new double[]{interestRate, fx, equity, commodity, total})
                if (!Double.isFinite(v) || v < 0) invalid();
            double sum = interestRate + fx + equity + commodity;
            if (Math.abs(sum-total) > Math.max(1e-8, Math.abs(total)*1e-12)) invalid();
        }
    }

    public record Source(String principalId, String subjectRef, String taskRef,
                         String sessionRef, String snapshotRef, String snapshotDigest,
                         @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate asOf, String kind) {
        public Source {
            principalId=text(principalId); subjectRef=text(subjectRef); taskRef=text(taskRef);
            sessionRef=text(sessionRef); snapshotRef=text(snapshotRef); hash(snapshotDigest);
            if (!"CONTROLLED_FIXTURE".equals(kind)) invalid();
        }
    }

    static String text(String value) {
        if (value == null || value.isBlank() || value.length() > 200 || !value.equals(value.strip())
                || value.chars().anyMatch(c -> c < 32)) invalid();
        return value;
    }
    static void hash(String value) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) invalid();
    }
    static void invalid() { throw new IllegalArgumentException("MARKET_RISK_SOURCE_INVALID"); }
}
