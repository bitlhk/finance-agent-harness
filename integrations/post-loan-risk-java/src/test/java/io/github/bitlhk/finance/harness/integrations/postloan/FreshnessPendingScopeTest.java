package io.github.bitlhk.finance.harness.integrations.postloan;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class FreshnessPendingScopeTest {
    static JsonNode frozen() throws Exception {
        try(var stream=FreshnessPendingScopeTest.class.getResourceAsStream("/postloan-freshness-first-failure.json")) {
            return HarnessJson.mapper().readTree(stream);
        }
    }
    @Test void frozenFullResponseUsesSameNormalizationAndValidation() throws Exception {
        var fixture=frozen();var facts=PostLoanRiskTask.modelFacts((ObjectNode)fixture.path("facts"));
        assertEquals("NOT_EVALUATED",facts.at("/freshnessAssessment/status").asText());
        assertEquals("ORANGE",facts.at("/deterministicWarning/level").asText());
        assertFalse(facts.at("/deterministicWarning/collateralCoverageRule/matched").asBoolean());
        assertEquals("FRESHNESS_RULE_EVIDENCE_REQUIRED",fixture.at("/failureDetail/rule").asText());
        var explanation=(ObjectNode)HarnessJson.mapper().readTree(fixture.path("rawModelContent").asText());
        assertEquals(fixture.path("explanation"),explanation);
        var normalize=PostLoanRiskTask.class.getDeclaredMethod("canonicalizeMatchingCollateralStatements",ObjectNode.class,JsonNode.class);
        normalize.setAccessible(true);normalize.invoke(null,explanation,facts);
        var refs=new ArrayList<String>();fixture.path("factRefs").forEach(ref->refs.add(ref.asText()));
        var validate=PostLoanRiskTask.class.getDeclaredMethod("validate",ObjectNode.class,List.class,JsonNode.class);
        validate.setAccessible(true);
        try { validate.invoke(null,explanation,refs,facts); }
        catch(InvocationTargetException error) { throw (Exception)error.getCause(); }
        assertEquals("REPORT_MODEL_RULE_EVIDENCE_REJECTED",fixture.path("failureCode").asText());
    }
    @TestFactory Stream<DynamicTest> pendingVersusAffirmedClaims() {
        var allowed=List.of(
            "数据时效未评估，仅展示截至时间，需核实数据有效性。",
            "数据有效性待核实。","数据有效性尚待人工核实。",
            "尚需人工确认数据有效性。","需核实数据已过期。",
            "需要核实数据有效性，不能声称数据已过期。",
            "数据时效未评估，不能判断数据有效或数据已过期。",
            "数据截至2026-08-25，未提供新鲜度规则，需核实是否仍有效。",
            "需核实数据有效性，但不得声称数据最新。"
        );
        var denied=List.of(
            "数据有效。","数据已过期。","数据未过期。","数据时间有效。",
            "需核实数据有效性，但数据确实有效。",
            "数据时效未评估，但数据已过期。",
            "数据有效性待核实；数据最新。",
            "不能保证最新，但数据确实有效。",
            "无需核实数据有效，已经确认。",
            "不能不认定数据已过期。",
            "需核实来源并确认数据有效。",
            "需核实数据有效性并且数据确实有效。"
        );
        return Stream.concat(allowed.stream().map(text->DynamicTest.dynamicTest("pending: "+text,()->
            assertFalse(PostLoanRiskTask.unsupportedFreshnessConclusion(text,HarnessJson.mapper().createArrayNode(),frozen().path("facts"))))),
            denied.stream().map(text->DynamicTest.dynamicTest("reject: "+text,()->
            assertTrue(PostLoanRiskTask.unsupportedFreshnessConclusion(text,HarnessJson.mapper().createArrayNode(),frozen().path("facts"))))));
    }
}
