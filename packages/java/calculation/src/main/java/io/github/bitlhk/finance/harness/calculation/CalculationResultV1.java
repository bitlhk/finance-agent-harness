package io.github.bitlhk.finance.harness.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/** Immutable original value evidence. Calculation time is not a source data date. */
public record CalculationResultV1(String schemaVersion, String runId, int generation,
        String assetId, String operationId, String inputDigest, String parameterDigest,
        JsonNode source, JsonNode tool, Instant calculatedAt, String dataAsOf,
        JsonNode rawValue, String rawDigest, JsonNode projection, JsonNode timings) {
    public static final String SCHEMA_VERSION="finance-original-calculation-result.v1";
    public CalculationResultV1 {
        if(!SCHEMA_VERSION.equals(schemaVersion) || generation<1) invalid();
        text(runId);text(assetId);text(operationId);hash(inputDigest);hash(parameterDigest);hash(rawDigest);
        Objects.requireNonNull(calculatedAt);
        if(dataAsOf!=null && dataAsOf.length()>64) invalid();
        for(JsonNode n:new JsonNode[]{source,tool,rawValue,projection,timings}) if(n==null || !n.isObject()) invalid();
        source=source.deepCopy();tool=tool.deepCopy();rawValue=rawValue.deepCopy();projection=projection.deepCopy();timings=timings.deepCopy();
    }
    @Override public JsonNode source(){return source.deepCopy();}
    @Override public JsonNode tool(){return tool.deepCopy();}
    @Override public JsonNode rawValue(){return rawValue.deepCopy();}
    @Override public JsonNode projection(){return projection.deepCopy();}
    @Override public JsonNode timings(){return timings.deepCopy();}
    public static void text(String v){if(v==null || v.isBlank() || v.length()>256 || v.chars().anyMatch(c->c<32))invalid();}
    public static void hash(String v){if(v==null || !v.matches("[a-f0-9]{64}"))invalid();}
    public static void invalid(){throw new IllegalArgumentException("ORIGINAL_CALCULATION_RESULT_INVALID");}
}
