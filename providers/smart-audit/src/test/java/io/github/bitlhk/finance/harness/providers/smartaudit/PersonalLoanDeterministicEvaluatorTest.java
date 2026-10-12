package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Deterministic audit findings must be derived before any optional explanatory model is used. */
class PersonalLoanDeterministicEvaluatorTest {
    private static final List<SmartAuditModelRequest.Material> BORROWER_MATERIALS = List.of(
            material("身份证"), material("户口本"), material("结婚证"), material("贷款申请表"));

    @Test
    void submittedTextIsUnverifiedEvidenceAndCannotSatisfyDueDiligence() {
        PersonalLoanDeterministicEvaluator.Evaluation evaluation = new PersonalLoanDeterministicEvaluator().evaluate(
                "nfra-2024-order-3", borrowerTask(), BORROWER_MATERIALS,
                List.of("BORROWER_AGE_AND_TERM_LIMITS"));

        assertEquals(4, evaluation.evidence().size());
        assertTrue(evaluation.evidence().stream().allMatch(evidence -> evidence.status() == AuditEvidenceStatus.UNVERIFIED));
        AuditRuleOutcome dueDiligence = outcome(evaluation, "PERSONAL_LOAN_DUE_DILIGENCE_EVIDENCE_VERIFICATION_V1");
        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE, dueDiligence.status());
        assertEquals(List.of("BORROWER_AGE_AND_TERM_LIMITS"), evaluation.missingBankParameterIds());
        assertFalse(evaluation.supportsApprovalOrRejection());
    }

    @Test
    void completeSubmissionOnlyMeetsDocumentCollectionRuleAndStillRequiresHumanReview() {
        PersonalLoanDeterministicEvaluator.Evaluation evaluation = new PersonalLoanDeterministicEvaluator().evaluate(
                "nfra-2024-order-3", borrowerTask(), BORROWER_MATERIALS, List.of());

        assertEquals(AuditRuleOutcome.Status.MET,
                outcome(evaluation, "PERSONAL_LOAN_WRITTEN_APPLICATION_AND_EVIDENCE_V1").status());
        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE,
                outcome(evaluation, "PERSONAL_LOAN_BANK_THRESHOLD_APPLICATION_V1").status());
        assertTrue(evaluation.manualReviewRequired());
    }

    @Test
    void incomeTaskCannotMeetWrittenApplicationRuleWhenItsContractExcludesAWrittenApplication() {
        var task = SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.PERSONAL_INCOME_VERIFICATION);
        List<SmartAuditModelRequest.Material> materials = task.materialCategories().stream()
                .map(PersonalLoanDeterministicEvaluatorTest::material).toList();

        PersonalLoanDeterministicEvaluator.Evaluation evaluation = new PersonalLoanDeterministicEvaluator().evaluate(
                "nfra-2024-order-3", task, materials, List.of());

        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE,
                outcome(evaluation, "PERSONAL_LOAN_WRITTEN_APPLICATION_AND_EVIDENCE_V1").status());
    }

    private static SmartAuditCapabilityCatalog.TaskDefinition borrowerTask() {
        return SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION);
    }

    private static AuditRuleOutcome outcome(PersonalLoanDeterministicEvaluator.Evaluation evaluation, String ruleId) {
        return evaluation.ruleOutcomes().stream().filter(outcome -> ruleId.equals(outcome.ruleId())).findFirst()
                .orElseThrow(() -> new AssertionError("missing rule outcome " + ruleId));
    }

    private static SmartAuditModelRequest.Material material(String category) {
        return new SmartAuditModelRequest.Material(category, category + "示例材料", "TEXT");
    }
}
