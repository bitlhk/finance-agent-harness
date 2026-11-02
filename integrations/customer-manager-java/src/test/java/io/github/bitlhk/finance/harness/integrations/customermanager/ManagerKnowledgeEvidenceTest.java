package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagerKnowledgeEvidenceTest {
    @Test void originalValidatedTextIsProjectedWithoutRegeneration() throws Exception {
        var report = HarnessJson.mapper().readTree("""
            {"customerAlias":"Synthetic customer", "explanationStatus":"READY",
             "modelSuggestion":{"communicationFocus":[{"text":"Original customer facts"}],
               "preparationAdvice":[{"text":"Original product and policy evidence"}],
               "pendingItems":["Not independently verified"],"reviewChecklist":["Human review"]}}
            """);
        String answer = ManagerKnowledgeEvidence.answer(report);
        assertTrue(answer.contains("Original customer facts"));
        assertTrue(answer.contains("Original product and policy evidence"));
        assertTrue(answer.contains("Not independently verified"));
        assertTrue(answer.contains("Human review"));
    }

    @Test void rejectedExplanationIsNotPresentedAsValidatedAdvice() throws Exception {
        var report = HarnessJson.mapper().readTree("""
            {"customerAlias":"Synthetic customer", "explanationStatus":"REJECTED",
             "modelSuggestion":{"preparationAdvice":[{"text":"UNTRUSTED_MODEL_TEXT"}]}}
            """);
        assertFalse(ManagerKnowledgeEvidence.answer(report).contains("UNTRUSTED_MODEL_TEXT"));
    }

    @Test void policyProjectionUsesOnlyReturnedOriginalClauses() throws Exception {
        var data = HarnessJson.mapper().readTree("""
            {"decision":"EVIDENCE_FOUND","evidence":[
              {"clause":"第十条","quote":"销售机构不得向客户销售不符合其风险承受能力的产品。"},
              {"clause":"技术字段","quote":""}]}
            """);
        String answer = ManagerKnowledgeEvidence.policyAnswer(data, true);
        assertTrue(answer.contains("第十条"));
        assertTrue(answer.contains("不得向客户销售"));
        assertFalse(answer.contains("技术字段"));
    }

    @Test void productQuestionUsesOnlyTrustedProductIdentityAndKeepsRealtimeFactsOut() throws Exception {
        var product = HarnessJson.mapper().readTree("""
            {"name":"招银理财招睿添利日开21号固定收益类理财计划N份额",
             "businessIdentity":{"managerProductCode":"132112","shareClass":"N"}}
            """);
        String question = ManagerKnowledgeEvidence.productQuestion(product);
        assertTrue(question.contains("132112"));
        assertTrue(question.contains("N 份额"));
        assertTrue(question.contains("实时在售"));
        assertFalse(question.contains("客户甲"));
        assertThrows(SecurityException.class, () -> ManagerKnowledgeEvidence.productQuestion(
                HarnessJson.mapper().readTree("{\"name\":\"产品\",\"businessIdentity\":{}}")));
    }

}
