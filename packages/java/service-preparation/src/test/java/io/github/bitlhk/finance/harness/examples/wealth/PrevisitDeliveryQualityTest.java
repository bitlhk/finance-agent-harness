package io.github.bitlhk.finance.harness.examples.wealth;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrevisitDeliveryQualityTest {
    private ObjectNode facts() throws Exception {
        return (ObjectNode) HarnessJson.mapper().readTree("""
          {"dataAsOf":"2026-08-27T12:00:00Z","profiles":[{"profileType":"wealth","detail":{
            "portfolioSummary":{"currency":"CNY","totalAssets":1800000,"availableCash":360000,"investedAssets":1440000},
            "holdings":[{"productName":"合成组合","balance":900000,"currency":"CNY","dataAsOf":"2026-08-27T12:00:00Z"}]
          }}]}
          """);
    }

    @Test void ratiosKeepOperandsRoundingAndSnapshotBoundary() throws Exception {
        var source=facts();String before=source.toString();
        var derived=PrevisitDerivedFacts.derive(source);
        assertEquals(20,derived.at("/items/0/percent").asDouble());
        assertEquals(62.5,derived.at("/items/1/percent").asDouble());
        assertEquals("/profiles/0/detail/holdings/0/balance",derived.at("/items/1/operandRefs/0").asText());
        assertTrue(derived.at("/items/1/boundary").asText().contains("不代表当前资产"));
        assertEquals(before,source.toString());
        source.set("derivedObservations",derived);
        assertDoesNotThrow(()->ReportNumbers.validate("快照现金占比20%，组合占比62.5%",source,""));
        assertThrows(IllegalArgumentException.class,()->ReportNumbers.validate("组合占比62%",source,""));
    }

    @Test void missingCurrencyCannotBeAssumedFromOtherAmounts() throws Exception {
        var source=facts();((ObjectNode)source.at("/profiles/0/detail/portfolioSummary")).remove("currency");
        assertTrue(PrevisitDerivedFacts.derive(source).path("items").isEmpty());
    }

    @Test void missingOrInvalidDateCannotProduceRatios() throws Exception {
        for(String date:new String[]{"","not-a-date"}) {
            var source=facts();source.put("dataAsOf",date);
            assertTrue(PrevisitDerivedFacts.derive(source).path("items").isEmpty());
        }
    }

    @Test void crossCurrencyAndCrossDateHoldingCannotBeCompared() throws Exception {
        for(String field:new String[]{"currency","dataAsOf"}) {
            var source=facts();((ObjectNode)source.at("/profiles/0/detail/holdings/0")).put(field,"currency".equals(field)?"USD":"2026-09-01T00:00:00Z");
            var derived=PrevisitDerivedFacts.derive(source);
            assertEquals(1,derived.path("items").size());
            assertFalse(derived.path("unavailable").isEmpty());
        }
    }

    @Test void invalidDenominatorAndContradictoryAmountsAreNotRatios() throws Exception {
        for(int total:new int[]{0,-1,100}) {
            var source=facts();((ObjectNode)source.at("/profiles/0/detail/portfolioSummary")).put("totalAssets",total);
            assertFalse(PrevisitDerivedFacts.derive(source).toString().contains("\"code\":\"CASH_SHARE\""));
        }
    }

    @Test void duplicateWealthProfilesAreNotSilentlySelected() throws Exception {
        var source=facts();source.withArray("profiles").add(source.path("profiles").get(0).deepCopy());
        assertTrue(PrevisitDerivedFacts.derive(source).path("items").isEmpty());
    }

    @Test void numericSubstringIsRejectedAndUnitsAreNormalized() throws Exception {
        var source=HarnessJson.mapper().readTree("{\"amount\":1120000,\"cash\":0,\"date\":\"2026-08-27\"}");
        assertDoesNotThrow(()->ReportNumbers.validate("112万元、1,120,000.00元、现金0元，2026-08-27快照",source,""));
        for(String unsupported:new String[]{"12万元","余额112元","占比27%","新增200万元"})
            assertThrows(IllegalArgumentException.class,()->ReportNumbers.validate(unsupported,source,""),unsupported);
        assertDoesNotThrow(()->ReportNumbers.validate("1. 8月27日的快照\n2. 现金0元",source,""));
        assertThrows(IllegalArgumentException.class,()->ReportNumbers.validate("1. 现金12元",source,""));
    }

    @Test void chineseInlineListOrdinalsAreNotFinancialClaims() throws Exception {
        var source=HarnessJson.mapper().readTree("{\"amount\":300000}");
        assertDoesNotThrow(()->ReportNumbers.validate("需核实：1）资金去向；2）期限偏好；3）最新持仓。",source,""));
        assertDoesNotThrow(()->ReportNumbers.validate("1）确认30万元资金去向\n2、确认用途",source,""));
        for(String unsupported:new String[]{"需核实：1）新增2万元", "余额：1.00元", "占比：2%", "资金：2万元"})
            assertThrows(IllegalArgumentException.class,()->ReportNumbers.validate(unsupported,source,""),unsupported);
    }
}
