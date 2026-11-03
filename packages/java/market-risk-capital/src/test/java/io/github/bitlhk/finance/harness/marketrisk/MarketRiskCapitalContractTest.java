package io.github.bitlhk.finance.harness.marketrisk;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketRiskCapitalContractTest {
    private static final String HASH="sha256:"+"a".repeat(64);
    private static final String REV="b".repeat(40);

    private MarketRiskCapitalMapper.Expected expected() {
        return new MarketRiskCapitalMapper.Expected("request-a","owner-a","portfolio-a","task-a", "session-a",
                "snapshot-a",HASH,HASH,REV,HASH,"2026.09-demo-v1","0.1.0");
    }

    private ObjectNode fixture() throws Exception {
        return (ObjectNode)HarnessJson.mapper().readTree("""
            {"schemaVersion":"finance-original-market-risk-capital-result.v1","status":"COMPLETED",
            "requestId":"request-a","values":{"interest_rate":0,"fx":0,"equity":0,"commodity":0,"total":0},
            "param_version":"2026.09-demo-v1","engine_version":"0.1.0",
            "detail":[{"risk_category":"利率","capital":0,"note":"原利率说明"},
                      {"risk_category":"汇率","capital":0,"note":"原汇率说明"},
                      {"risk_category":"股票","capital":0,"note":"原股票说明"},
                      {"risk_category":"商品","capital":0,"note":"原商品说明"}],
            "ir_breakdown":{"zones":[]},
            "source":{"principalId":"owner-a","subjectRef":"portfolio-a","taskRef":"task-a","sessionRef":"session-a",
              "snapshotRef":"snapshot-a","snapshotDigest":"HASH","asOf":null,"kind":"CONTROLLED_FIXTURE"},
            "calculatedAt":"2026-11-03T05:00:00+00:00","toolReleaseDigest":"HASH","upstreamRevision":"REV",
            "parametersDigest":"HASH","originalFunction":"mktrisk_engine.standard.compute_standard_capital"}
            """.replace("HASH",HASH).replace("REV",REV));
    }

    @Test void zeroAndMissingDateRemainDifferentFromMissingFacts() throws Exception {
        var facts=MarketRiskCapitalMapper.map(fixture(),expected());
        assertEquals(0,facts.values().total()); assertNull(facts.source().asOf());
        assertNotNull(facts.calculatedAt());
        ObjectNode missing=fixture(); missing.remove("values");
        assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(missing,expected()));
    }

    @Test void unknownAndTextNumbersAreRejected() throws Exception {
        ObjectNode extra=fixture();extra.put("unreviewed",true);
        assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(extra,expected()));
        ObjectNode number=fixture();((ObjectNode)number.get("values")).put("total","0");
        assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(number,expected()));
    }

    @Test void currentIdentityAndSnapshotAreRequired() throws Exception {
        for(String field:new String[]{"principalId","subjectRef","taskRef","sessionRef","snapshotRef","snapshotDigest"}) {
            ObjectNode data=fixture();((ObjectNode)data.get("source")).put(field,"wrong");
            assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(data,expected()),field);
        }
    }

    @Test void versionsAndOriginalFunctionAreBound() throws Exception {
        for(String field:new String[]{"param_version","engine_version","toolReleaseDigest","upstreamRevision","parametersDigest","originalFunction"}) {
            ObjectNode data=fixture();data.put(field,"wrong");
            assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(data,expected()),field);
        }
    }

    @Test void inconsistentAndNonFiniteValuesAreNotFacts() {
        assertThrows(IllegalArgumentException.class,()->new MarketRiskCapitalV1.Values(1,2,3,4,11));
        assertThrows(IllegalArgumentException.class,()->new MarketRiskCapitalV1.Values(Double.NaN,0,0,0,0));
        assertThrows(IllegalArgumentException.class,()->new MarketRiskCapitalV1.Values(0,0,0,0,Double.POSITIVE_INFINITY));
    }

    @Test void rawDetailsAreDefensiveAndStillMatchFacts() throws Exception {
        var facts=MarketRiskCapitalMapper.map(fixture(),expected());
        ((ObjectNode)facts.detail().get(0)).put("capital",999);
        assertEquals(0,facts.detail().get(0).get("capital").asDouble());
        ObjectNode wrong=fixture();((ObjectNode)wrong.get("detail").get(0)).put("capital",999);
        assertThrows(IllegalArgumentException.class,()->MarketRiskCapitalMapper.map(wrong,expected()));
    }

    @Test void serializedFactsKeepOriginalNamesForSchemaAndUi() throws Exception {
        var facts=MarketRiskCapitalMapper.map(fixture(),expected());
        var json=HarnessJson.mapper().valueToTree(facts);
        assertTrue(json.has("param_version")); assertTrue(json.has("engine_version"));
        assertTrue(json.has("ir_breakdown")); assertFalse(json.has("irBreakdown"));
        assertTrue(json.get("values").has("interest_rate"));
        assertTrue(json.get("source").has("asOf"));
        assertTrue(json.get("source").get("asOf").isNull());
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/contract-result.json"),json.toString());
    }
}
