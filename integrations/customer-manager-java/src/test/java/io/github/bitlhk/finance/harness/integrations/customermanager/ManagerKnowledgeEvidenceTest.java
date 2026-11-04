package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import java.time.Instant;
import java.util.Set;

class ManagerKnowledgeEvidenceTest {
    @Test void productProviderUsesItsAuthorizedSourceSubjectNotTheCustomer() {
        var grant = new BoundFinanceProvider.Grant("employee", FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of("product-source"), Set.of("WEALTH_PRODUCT_REVIEW"), Set.of("wealth:products:read"),
                "session", "authority", Instant.now().plusSeconds(60));
        assertEquals("product-source", ManagerKnowledgeEvidence.productSubject(grant));
        assertNotEquals("customer-123", ManagerKnowledgeEvidence.productSubject(grant));
    }

    @Test void productProviderRejectsMissingOrAmbiguousSourceAuthority() {
        assertThrows(SecurityException.class, () -> ManagerKnowledgeEvidence.productSubject(null));
        var ambiguous = new BoundFinanceProvider.Grant("employee", FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of("source-a", "source-b"), Set.of("WEALTH_PRODUCT_REVIEW"), Set.of("wealth:products:read"),
                "session", "authority", Instant.now().plusSeconds(60));
        assertThrows(SecurityException.class, () -> ManagerKnowledgeEvidence.productSubject(ambiguous));
    }
    @Test void noKnowledgeAndAmbiguousDecisionsRemainValidCapabilityResults() {
        var missing = ManagerKnowledgeEvidence.unavailable("request", "trace", "NO_BOUND_PRODUCT_KNOWLEDGE", false);
        assertEquals(io.github.bitlhk.finance.harness.core.CapabilityResultV1.Status.COMPLETED, missing.status());
        assertEquals("KNOWLEDGE_NOT_AVAILABLE", missing.data().get("decision"));
        assertNotNull(missing.asOf());

        var rejected = ManagerKnowledgeEvidence.unavailable("request", "trace", "KNOWLEDGE_PRODUCT_SELECTION_AMBIGUOUS", true);
        assertEquals("KNOWLEDGE_REJECTED", rejected.data().get("decision"));
        assertNotNull(rejected.asOf());
    }
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
            {"name":"招银理财招睿添利日开21号固定收益类理财计划",
             "businessBinding":{"product":{"managerProductCode":"132112"},
               "distribution":[{"shareClasses":["N"]}]}}
            """);
        String question = ManagerKnowledgeEvidence.productQuestion(product);
        assertTrue(question.contains("132112"));
        assertTrue(question.contains("N 份额"));
        assertTrue(question.contains("实时在售"));
        assertFalse(question.contains("客户甲"));
        assertThrows(SecurityException.class, () -> ManagerKnowledgeEvidence.productQuestion(
                HarnessJson.mapper().readTree("{\"name\":\"产品\",\"businessBinding\":{}}")));
    }

    @Test void riskQuestionLeadsWithGroundedConclusionAndLeavesOriginalEvidenceInToolResult() throws Exception {
        var data = HarnessJson.mapper().readTree("""
            {"decision":"EVIDENCE_FOUND","evidence":[{"clause":"产品风险",
              "quote":"管理人对本产品的风险评级为二级，即PR2（中低风险）。销售服务机构与管理人对本产品风险评级结果不一致时，以销售服务机构最终披露结果为准。"}]}
            """);
        String answer = ManagerKnowledgeEvidence.policyAnswer("这款产品是什么风险等级？", data, true);
        assertTrue(answer.contains("PR2（中低风险）"));
        assertTrue(answer.contains("授权业务系统核验"));
        assertFalse(answer.contains("管理人对本产品的风险评级为二级，即PR2"));
    }

}
