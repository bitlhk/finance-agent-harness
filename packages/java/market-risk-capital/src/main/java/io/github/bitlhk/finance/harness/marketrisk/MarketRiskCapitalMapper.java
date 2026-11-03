package io.github.bitlhk.finance.harness.marketrisk;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import static io.github.bitlhk.finance.harness.marketrisk.MarketRiskCapitalV1.*;

/** Map only the reviewed original function result and require host-owned identity. */
public final class MarketRiskCapitalMapper {
    private MarketRiskCapitalMapper() { }

    public record Expected(String requestId, String principalId, String subjectRef, String taskRef,
                           String sessionRef, String snapshotRef, String snapshotDigest,
                           String toolReleaseDigest, String upstreamRevision, String parametersDigest,
                           String paramVersion, String engineVersion) {
        public Expected {
            for (String v : List.of(requestId,principalId,subjectRef,taskRef,sessionRef,snapshotRef,paramVersion,engineVersion)) text(v);
            hash(snapshotDigest); hash(toolReleaseDigest); hash(parametersDigest);
            if (upstreamRevision == null || !upstreamRevision.matches("[a-f0-9]{40}")) invalid();
        }
    }

    public static MarketRiskCapitalV1 map(JsonNode raw, Expected expected) {
        try {
            keys(raw, Set.of("schemaVersion","status","requestId","values","param_version","engine_version",
                    "detail","ir_breakdown","source","calculatedAt","toolReleaseDigest","upstreamRevision",
                    "parametersDigest","originalFunction"));
            equal(str(raw,"schemaVersion"),"finance-original-market-risk-capital-result.v1");
            equal(str(raw,"status"),"COMPLETED");
            equal(str(raw,"requestId"),expected.requestId());
            equal(str(raw,"toolReleaseDigest"),expected.toolReleaseDigest());
            equal(str(raw,"upstreamRevision"),expected.upstreamRevision());
            equal(str(raw,"parametersDigest"),expected.parametersDigest());
            equal(str(raw,"param_version"),expected.paramVersion());
            equal(str(raw,"engine_version"),expected.engineVersion());
            equal(str(raw,"originalFunction"),ORIGINAL_FUNCTION);
            JsonNode v=raw.get("values");
            keys(v,Set.of("interest_rate","fx","equity","commodity","total"));
            Values values=new Values(number(v,"interest_rate"),number(v,"fx"),number(v,"equity"),
                    number(v,"commodity"),number(v,"total"));
            JsonNode s=raw.get("source");
            keys(s,Set.of("principalId","subjectRef","taskRef","sessionRef","snapshotRef","snapshotDigest","asOf","kind"));
            equal(str(s,"principalId"),expected.principalId()); equal(str(s,"subjectRef"),expected.subjectRef());
            equal(str(s,"taskRef"),expected.taskRef()); equal(str(s,"sessionRef"),expected.sessionRef());
            equal(str(s,"snapshotRef"),expected.snapshotRef()); equal(str(s,"snapshotDigest"),expected.snapshotDigest());
            Source source=new Source(str(s,"principalId"),str(s,"subjectRef"),str(s,"taskRef"),str(s,"sessionRef"),
                    str(s,"snapshotRef"),str(s,"snapshotDigest"),s.get("asOf").isNull()?null:LocalDate.parse(str(s,"asOf")),str(s,"kind"));
            JsonNode detail=raw.get("detail"), breakdown=raw.get("ir_breakdown");
            validateDetail(detail, values);
            validateBreakdown(breakdown);
            return new MarketRiskCapitalV1(SCHEMA_VERSION,expected.requestId(),values,
                    expected.paramVersion(),expected.engineVersion(),detail,breakdown,source,
                    OffsetDateTime.parse(str(raw,"calculatedAt")).toInstant(),expected.toolReleaseDigest(),
                    expected.upstreamRevision(),expected.parametersDigest(),ORIGINAL_FUNCTION);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("MARKET_RISK_SOURCE_INVALID");
        }
    }

    private static void validateDetail(JsonNode detail, Values values) {
        if (detail == null || !detail.isArray() || detail.size()!=4) invalid();
        Map<String,Double> expected=Map.of("利率",values.interestRate(),"汇率",values.fx(),
                "股票",values.equity(),"商品",values.commodity());
        Set<String> seen=new HashSet<>();
        for (JsonNode row:detail) {
            keys(row,Set.of("risk_category","capital","note"));
            String category=str(row,"risk_category");
            if (!expected.containsKey(category) || !seen.add(category)) invalid();
            double capital=number(row,"capital");
            if (Math.abs(capital-expected.get(category))>Math.max(1e-8,Math.abs(capital)*1e-12)) invalid();
            if (!row.get("note").isTextual() || row.get("note").asText().length()>1000) invalid();
        }
    }

    private static void validateBreakdown(JsonNode node) {
        if (node == null || !node.isObject() || !node.has("zones") || !node.get("zones").isArray()) invalid();
        Set<String> fields=new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
        if (!Set.of("zones","diversification","zone_charges").containsAll(fields)) invalid();
        JsonNode zones=node.get("zones");
        if (zones.size()!=0 && zones.size()!=3) invalid();
        for (JsonNode zone:zones) {
            keys(zone,Set.of("zone","long","short","matched","residual","weight","charge"));
            str(zone,"zone");
            for(String key:List.of("long","short","matched","residual","weight","charge")) number(zone,key);
        }
        if (zones.size()==3) {
            number(node,"diversification");
            JsonNode charges=node.get("zone_charges");
            if(charges==null || !charges.isArray() || charges.size()!=3) invalid();
            for(JsonNode charge:charges) if(!charge.isNumber() || !Double.isFinite(charge.doubleValue())) invalid();
        } else if (fields.size()!=1) invalid();
    }

    private static void keys(JsonNode node, Set<String> expected) {
        if(node==null || !node.isObject()) invalid();
        Set<String> found=new HashSet<>(); node.fieldNames().forEachRemaining(found::add);
        if(!found.equals(expected)) invalid();
    }
    private static String str(JsonNode node,String field) {
        JsonNode value=node.get(field);
        if(value==null || !value.isTextual()) { invalid(); return ""; }
        return text(value.textValue());
    }
    private static double number(JsonNode node,String field) {
        JsonNode value=node.get(field);
        if(value==null || !value.isNumber() || !Double.isFinite(value.doubleValue())) { invalid(); return 0; }
        return value.doubleValue();
    }
    private static void equal(String actual,String expected) { if(!Objects.equals(actual,expected)) invalid(); }
}
