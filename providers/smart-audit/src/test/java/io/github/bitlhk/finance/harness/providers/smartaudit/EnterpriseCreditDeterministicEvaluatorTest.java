package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Enterprise uploaded artifacts prove submission only; every conclusion remains advisory and review-bound. */
class EnterpriseCreditDeterministicEvaluatorTest {

    @Test
    void financialReportCollectsBalanceSheetWithoutTreatingItAsVerifiedFinancialFact() {
        assertTask(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT,
                "ENTERPRISE_FINANCIAL_REPORT_DOCUMENT_COLLECTION_V1",
                "ENTERPRISE_FINANCIAL_REPORT_EVIDENCE_VERIFICATION_V1");
    }

    @Test
    void financialSummaryCollectsItsRequiredDocumentsWithoutTreatingMetricsAsVerified() {
        assertTask(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_SUMMARY,
                "ENTERPRISE_FINANCIAL_SUMMARY_DOCUMENT_COLLECTION_V1",
                "ENTERPRISE_FINANCIAL_SUMMARY_EVIDENCE_VERIFICATION_V1");
    }

    @Test
    void transactionAnalysisCollectsItsRequiredDocumentsWithoutCreatingAnAccessDecision() {
        assertTask(SmartAuditCapabilityCatalog.ENTERPRISE_TRANSACTION_ANALYSIS,
                "ENTERPRISE_TRANSACTION_ANALYSIS_DOCUMENT_COLLECTION_V1",
                "ENTERPRISE_TRANSACTION_ANALYSIS_EVIDENCE_VERIFICATION_V1");
    }

    @Test
    void transactionReportCollectsItsRequiredDocumentsWithoutCreatingAnAuditRecommendation() {
        assertTask(SmartAuditCapabilityCatalog.ENTERPRISE_TRANSACTION_REPORT,
                "ENTERPRISE_TRANSACTION_REPORT_DOCUMENT_COLLECTION_V1",
                "ENTERPRISE_TRANSACTION_REPORT_EVIDENCE_VERIFICATION_V1");
    }

    @Test
    void dueDiligenceCollectsItsRequiredDocumentsWithoutCompletingDueDiligence() {
        assertTask(SmartAuditCapabilityCatalog.ENTERPRISE_DUE_DILIGENCE,
                "ENTERPRISE_DUE_DILIGENCE_DOCUMENT_COLLECTION_V1",
                "ENTERPRISE_DUE_DILIGENCE_EVIDENCE_VERIFICATION_V1");
    }

    @Test
    void flagsAnInternallyUnbalancedStructuredBalanceSheetWithoutMakingACreditDecision() {
        SmartAuditCapabilityCatalog.TaskDefinition task =
                SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT);
        EnterpriseCreditDeterministicEvaluator.Evaluation evaluation =
                new EnterpriseCreditDeterministicEvaluator().evaluate(task, List.of(
                        new SmartAuditModelRequest.Material("资产负债表", """
                                {"主体":"星澜实业（合成样本）","资产总计":1000,
                                 "负债总计":400,"所有者权益合计":500}
                                """, "JSON")));

        assertEquals(AuditEvidenceStatus.CONFLICTING, evaluation.evidence().getFirst().status());
        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE,
                outcome(evaluation, "ENTERPRISE_FINANCIAL_REPORT_BALANCE_SHEET_EQUATION_V1").status());
        assertTrue(evaluation.manualReviewRequired());
        assertFalse(evaluation.supportsApprovalOrRejection());
    }

    @Test
    void detectsBalanceSheetConflictWhenAnEarlierAliasIsInvalidButALaterAliasIsUsable() {
        SmartAuditCapabilityCatalog.TaskDefinition task =
                SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT);
        EnterpriseCreditDeterministicEvaluator.Evaluation evaluation =
                new EnterpriseCreditDeterministicEvaluator().evaluate(task, List.of(
                        new SmartAuditModelRequest.Material("资产负债表", """
                                {"资产总计":"无法解析","资产总额":"1000.00",
                                 "负债合计":"400.00","所有者权益":"500.00"}
                                """, "JSON")));

        assertEquals(AuditEvidenceStatus.CONFLICTING, evaluation.evidence().getFirst().status());
        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE,
                outcome(evaluation, "ENTERPRISE_FINANCIAL_REPORT_BALANCE_SHEET_EQUATION_V1").status());
        assertTrue(evaluation.manualReviewRequired());
        assertFalse(evaluation.supportsApprovalOrRejection());
    }

    @Test
    void leavesBalancedOrIncompleteStructuredBalanceSheetsUnverifiedRatherThanMakingFinancialClaims() {
        SmartAuditCapabilityCatalog.TaskDefinition task =
                SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT);
        EnterpriseCreditDeterministicEvaluator.Evaluation evaluation =
                new EnterpriseCreditDeterministicEvaluator().evaluate(task, List.of(
                        new SmartAuditModelRequest.Material("资产负债表", """
                                {"资产总额":"1000.50","负债合计":"400.25","所有者权益":"600.25"}
                                """, "JSON"),
                        new SmartAuditModelRequest.Material("资产负债表", """
                                {"资产总计":1000,"负债总计":"not-a-number"}
                                """, "JSON")));

        assertTrue(evaluation.evidence().stream()
                .allMatch(evidence -> evidence.status() == AuditEvidenceStatus.UNVERIFIED));
        assertTrue(evaluation.manualReviewRequired());
        assertFalse(evaluation.supportsApprovalOrRejection());
    }

    private static void assertTask(String capabilityId, String collectionRuleId, String evidenceRuleId) {
        SmartAuditCapabilityCatalog.TaskDefinition task = SmartAuditCapabilityCatalog.require(capabilityId);
        List<SmartAuditModelRequest.Material> materials = task.materialCategories().stream()
                .map(category -> new SmartAuditModelRequest.Material(category, category + "示例材料", "TEXT"))
                .toList();

        EnterpriseCreditDeterministicEvaluator.Evaluation evaluation =
                new EnterpriseCreditDeterministicEvaluator().evaluate(task, materials);

        assertEquals(task.materialCategories().size(), evaluation.evidence().size());
        assertTrue(evaluation.evidence().stream()
                .allMatch(evidence -> evidence.status() == AuditEvidenceStatus.UNVERIFIED));
        assertEquals(AuditRuleOutcome.Status.MET, outcome(evaluation, collectionRuleId).status());
        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE, outcome(evaluation, evidenceRuleId).status());
        assertTrue(evaluation.manualReviewRequired());
        assertFalse(evaluation.supportsApprovalOrRejection());
    }

    private static AuditRuleOutcome outcome(EnterpriseCreditDeterministicEvaluator.Evaluation evaluation, String ruleId) {
        return evaluation.ruleOutcomes().stream().filter(outcome -> ruleId.equals(outcome.ruleId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing rule outcome " + ruleId));
    }
}
