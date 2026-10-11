package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerManagerTaskContextTest {
    @Test void keepsSourceVerificationCompletenessAndValiditySeparate() {
        JsonNode context=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "45岁，讨论100万元，12个月内可能用30万元，风险测评及持仓未知");
        JsonNode amount=find(context,"visit.liquidityAmount");
        assertEquals(300000,amount.path("value").asDouble());
        assertEquals("USER_STATEMENT",amount.at("/source/kind").asText());
        assertEquals("UNVERIFIED",amount.at("/verification/status").asText());
        assertEquals("PRESENT",amount.at("/completeness/status").asText());
        assertEquals("UNKNOWN",amount.at("/validity/status").asText());
        assertTrue(context.path("questions").size()<=3);
    }

    @Test void oralRiskDoesNotBecomeVerifiedAndUnknownsRemainUnknown() {
        JsonNode first=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "45岁，讨论100万元，12个月内可能用30万元，风险测评及持仓未知");
        JsonNode second=CustomerManagerTaskContext.update(first,"口述C3、偏稳健，有效期未给、持仓未知");
        assertEquals("C3",find(second,"riskAssessment.level").path("value").asText());
        assertEquals("UNVERIFIED",find(second,"riskAssessment.level").at("/verification/status").asText());
        assertEquals("UNKNOWN",find(second,"riskAssessment.validUntil").at("/completeness/status").asText());
        assertEquals("UNKNOWN",find(second,"portfolio.holdings").at("/completeness/status").asText());
    }

    @Test void correctionCreatesNewVersionAndOnlyUpdatesDeterministicAmounts() {
        JsonNode first=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "讨论100万元，12个月内可能用30万元");
        JsonNode second=CustomerManagerTaskContext.update(first,"30万元改成40万元");
        assertEquals(300000,find(first,"visit.liquidityAmount").path("value").asDouble());
        assertEquals(400000,find(second,"visit.liquidityAmount").path("value").asDouble());
        assertEquals(600000,find(second,"visit.remainingDiscussionAmount").path("value").asDouble());
        assertEquals("DERIVED_FROM_UNVERIFIED_INPUT",
                find(second,"visit.remainingDiscussionAmount").at("/verification/status").asText());
        assertFalse(find(second,"visit.remainingDiscussionAmount").has("horizonMonths"));
        assertTrue(second.path("revision").asInt()>first.path("revision").asInt());
    }

    @Test void independentCustomerContextsDoNotShareStatements() {
        JsonNode firstCustomer=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),"讨论100万元，可能使用30万元");
        JsonNode secondCustomer=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),"口述C3");
        assertEquals(300000,find(firstCustomer,"visit.liquidityAmount").path("value").asDouble());
        assertTrue(find(secondCustomer,"visit.liquidityAmount").isMissingNode());
        assertEquals("C3",find(secondCustomer,"riskAssessment.level").path("value").asText());
        assertTrue(find(firstCustomer,"riskAssessment.level").isMissingNode());
    }

    @Test void testLabelsAndVersionMarkersDoNotBecomeRiskFacts() {
        JsonNode context=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "DOT-R41 合成测试新任务：讨论100万元，其中30万元保留流动性");
        assertTrue(find(context,"riskAssessment.level").isMissingNode());
        assertEquals(700000,find(context,"visit.remainingDiscussionAmount").path("value").asDouble());
    }

    @Test void explicitRiskStatementsStillRemainUnverifiedUserStatements() {
        for(String query:java.util.List.of("风险等级R4","风险测评结果为C3","客户口述 R2")) {
            JsonNode context=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),query);
            JsonNode risk=find(context,"riskAssessment.level");
            assertFalse(risk.isMissingNode(),query);
            assertEquals("USER_STATEMENT",risk.at("/source/kind").asText(),query);
            assertEquals("UNVERIFIED",risk.at("/verification/status").asText(),query);
        }
    }

    @Test void labeledCorrectionsUpdateBothAmountsAndInvalidateAnImpossibleRemainder() {
        JsonNode first=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "讨论100万元，12个月内可能用30万元");
        JsonNode second=CustomerManagerTaskContext.update(first,
                "讨论总额100万元改为120万元，流动需求维持40万元");
        assertEquals(1200000,find(second,"visit.discussionAmount").path("value").asDouble());
        assertEquals(400000,find(second,"visit.liquidityAmount").path("value").asDouble());
        assertEquals(800000,find(second,"visit.remainingDiscussionAmount").path("value").asDouble());
        JsonNode conflicting=CustomerManagerTaskContext.update(second,
                "总额仍为120万元，流动需求改为150万元");
        assertTrue(find(conflicting,"visit.remainingDiscussionAmount").isMissingNode());
        assertEquals("CONFLICT",find(conflicting,"visit.amountConflict").at("/verification/status").asText());
        assertTrue(conflicting.path("questions").get(0).asText().contains("金额冲突"));
    }

    @Test void liquidityTimingAndRemainingFundsHorizonStayIndependent() {
        JsonNode first=CustomerManagerTaskContext.update(CustomerManagerTaskContext.empty(),
                "12个月内可能使用30万元");
        JsonNode clarified=CustomerManagerTaskContext.update(first,
                "12个月仅指流动用款时点，剩余资金期限从未提供");
        assertEquals(12,find(clarified,"visit.liquidityTimingMonths").path("value").asInt());
        assertEquals("UNKNOWN",find(clarified,"visit.remainingHorizonMonths").at("/completeness/status").asText());
    }

    private static JsonNode find(JsonNode context,String key) {
        for(JsonNode fact:context.path("facts"))if(key.equals(fact.path("key").asText()))return fact;
        return context.path("missing");
    }
}
