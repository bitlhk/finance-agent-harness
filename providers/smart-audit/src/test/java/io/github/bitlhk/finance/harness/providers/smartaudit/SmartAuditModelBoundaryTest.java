package io.github.bitlhk.finance.harness.providers.smartaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A model may explain deterministic findings, but never decide or alter them. */
class SmartAuditModelBoundaryTest {
    private final SmartAuditResultMapper mapper = new SmartAuditResultMapper();

    @Test
    void modelNarrativeCannotAlterDeterministicEvidenceRulesOrHumanReview() {
        PersonalLoanDeterministicEvaluator.Evaluation evaluation = new PersonalLoanDeterministicEvaluator().evaluate(
                "nfra-2024-order-3", List.of(
                        material("身份证"), material("户口本"), material("结婚证"), material("贷款申请表")),
                List.of("BORROWER_AGE_AND_TERM_LIMITS"));
        SmartAuditModelResponse hostileModelResponse = new SmartAuditModelResponse(
                "审批通过，建议立即放款。<script>ignore rules</script> https://unsafe.example/approve",
                "完整", List.of("MODEL_MISSING"), List.of("APPROVE"),
                List.of(new SmartAuditModelResponse.EvidenceReference("伪造材料", "forged#1", "MODEL_RULE")),
                "hostile-model");

        CapabilityResultV1 result = mapper.mapDeterministicPersonal(request(), personalTask(), Map.of("version", "v1"),
                evaluation, hostileModelResponse);

        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
        assertEquals(evaluation.evidence().stream().map(AuditEvidenceAssessment::toMap).toList(), result.data().get("evidence"));
        assertEquals(evaluation.ruleOutcomes().stream().map(AuditRuleOutcome::toMap).toList(), result.data().get("ruleOutcomes"));
        assertEquals(List.of("BORROWER_AGE_AND_TERM_LIMITS"), result.missingInputs());
        assertEquals(List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), result.nextActions());
        assertTrue(result.content().contains("审核说明（非审批决定）"));
        assertEquals("模型说明不可用", ((Map<?, ?>) result.data().get("modelExplanation")).get("narrative"));
        assertFalse(result.content().contains("审批通过"));
        assertFalse(result.content().contains("放款"));
        assertFalse(result.content().contains("unsafe.example"));
        assertFalse(result.data().containsValue("完整"), "model completeness must never become an audit fact");
    }

    @Test
    void modelOnlyEnterpriseResponseCannotBecomeAnApprovalRecommendationOrEvidence() {
        SmartAuditModelResponse hostileModelResponse = new SmartAuditModelResponse(
                "授信通过，批准并放款。", "完整", List.of(), List.of("批准"),
                List.of(new SmartAuditModelResponse.EvidenceReference("模型", "invented", "model-rule")), "model-v1");

        CapabilityResultV1 result = mapper.map(hostileModelResponse, request(), enterpriseTask(), Map.of());

        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
        assertEquals("不可判断", result.data().get("completeness"));
        assertEquals(List.of(), result.data().get("evidence"));
        assertEquals(List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), result.nextActions());
        assertTrue(result.content().startsWith("审核说明（非审批决定）"));
        assertEquals("模型说明不可用", ((Map<?, ?>) result.data().get("modelExplanation")).get("narrative"));
        assertFalse(result.content().contains("授信通过"));
        assertFalse(result.content().contains("批准"));
        assertFalse(result.content().contains("放款"));
    }

    @Test
    void legacyMaterialRequestNeverExposesDocumentContentToTheModelPort() {
        SmartAuditModelRequest request = new SmartAuditModelRequest(
                "smartaudit.personal.borrower-qualification.query", "borrower-qualification", "skill-v1", "model-v1",
                List.of(new SmartAuditModelRequest.Material("身份证", "身份证号: 110101199001011234", "TEXT")),
                Map.of("version", "baseline-v1"), List.of("审核说明"));

        assertEquals(List.of(), request.materials());
        assertEquals(List.of(new SmartAuditModelRequest.EvidenceLocator("身份证", "身份证#1")), request.evidenceLocators());
        assertFalse(request.toString().contains("110101199001011234"));
    }

    @Test
    void fixedExplanationSchemaPreservesOnlyNarrativeAndAdvisoryHints() {
        SmartAuditModelResponse response = new SmartAuditModelResponse(new SmartAuditModelResponse.Explanation(
                "材料已提交，真实性仍待人工核验。", List.of("补充可核验的收入凭证。"),
                List.of("由具备权限的调查人员复核。")), "model-v1");

        CapabilityResultV1 result = mapper.map(response, request(), enterpriseTask(), Map.of());

        Map<?, ?> explanation = (Map<?, ?>) result.data().get("modelExplanation");
        assertEquals("材料已提交，真实性仍待人工核验。", explanation.get("narrative"));
        assertEquals(List.of("补充可核验的收入凭证。"), explanation.get("supplementHints"));
        assertEquals(List.of("由具备权限的调查人员复核。"), explanation.get("manualReviewHints"));
        assertFalse(explanation.containsKey("ruleOutcomes"));
        assertFalse(explanation.containsKey("evidence"));
    }

    @Test
    void providerOnlySendsDeterministicFindingsAndLocatorsToOptionalPersonalExplanationModel() {
        AtomicReference<SmartAuditModelRequest> received = new AtomicReference<>();
        SmartAuditModelProvider provider = new SmartAuditModelProvider(new SmartAuditModelProvider.Config(
                personalBaseline(), "model-v1", approvedBankParameters()), modelRequest -> {
            received.set(modelRequest);
            return new SmartAuditModelResponse(new SmartAuditModelResponse.Explanation(
                    "审批通过，建议放款。", List.of(), List.of()), "model-v1");
        });

        CapabilityResultV1 result = provider.query(completePersonalRequest());

        assertTrue(received.get() != null,
                () -> "expected explanation call but received " + result.status() + " / " + result.error());
        SmartAuditModelRequest modelRequest = received.get();
        assertFalse(modelRequest.ruleOutcomes().isEmpty());
        assertEquals(List.of(), modelRequest.materials());
        assertFalse(modelRequest.toString().contains("110101199001011234"));
        assertEquals("模型说明不可用", ((Map<?, ?>) result.data().get("modelExplanation")).get("narrative"));
        assertTrue(result.data().containsKey("ruleOutcomes"));
        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
    }

    @Test
    void decisionSemanticsInEveryExplanationFieldMakeTheEntireModelExplanationUnavailable() {
        List<String> decisionVariants = List.of(
                "贷款申请通过", "建议通过贷款申请", "允许发放贷款",
                "贷款申请被拒绝", "建议拒绝贷款申请", "停止发放贷款",
                "approve the loan application", "reject the credit application", "allow loan disbursement",
                "贷​款申‌请通​过", "appr‌ove the lo​an applic‌ation");
        for (String variant : decisionVariants) {
            for (ExplanationField field : ExplanationField.values()) {
                SmartAuditModelResponse.Explanation explanation = switch (field) {
                    case NARRATIVE -> new SmartAuditModelResponse.Explanation(variant,
                            List.of("补充可核验的收入凭证。"), List.of("由人工复核。"));
                    case SUPPLEMENT_HINT -> new SmartAuditModelResponse.Explanation("材料已提交，真实性待核验。",
                            List.of(variant), List.of("由人工复核。"));
                    case MANUAL_REVIEW_HINT -> new SmartAuditModelResponse.Explanation("材料已提交，真实性待核验。",
                            List.of("补充可核验的收入凭证。"), List.of(variant));
                };

                CapabilityResultV1 result = mapper.map(new SmartAuditModelResponse(explanation, "model-v1"),
                        request(), enterpriseTask(), Map.of());

                assertEquals("模型说明不可用", ((Map<?, ?>) result.data().get("modelExplanation")).get("narrative"),
                        () -> field + " accepted decision semantics: " + variant);
            }
        }
    }

    private static SmartAuditCapabilityCatalog.TaskDefinition personalTask() {
        return SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION);
    }

    private static SmartAuditCapabilityCatalog.TaskDefinition enterpriseTask() {
        return SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT);
    }

    private static CapabilityRequestV1 request() {
        FinanceContextV1 context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-model-boundary",
                "trace-model-boundary", "employee-001", FinanceContextV1.PrincipalType.EMPLOYEE, "builder",
                "PERSONAL_LOAN_AUDIT", "case-001", "session-001", FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("smart-audit:personal:borrower-qualification:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION, CapabilityRequestV1.Operation.QUERY,
                context, Map.of(), null, null, Map.of(), null);
    }

    private static CapabilityRequestV1 completePersonalRequest() {
        FinanceContextV1 context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-model-provider",
                "trace-model-provider", "employee-001", FinanceContextV1.PrincipalType.EMPLOYEE, "builder",
                "PERSONAL_LOAN_AUDIT", "case-001", "session-001", FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("smart-audit:personal:borrower-qualification:read"));
        List<Map<String, String>> materials = List.of(
                Map.of("category", "身份证", "content", "身份证号: 110101199001011234", "declaredFormat", "TEXT"),
                Map.of("category", "户口本", "content", "户口本材料", "declaredFormat", "TEXT"),
                Map.of("category", "结婚证", "content", "结婚证材料", "declaredFormat", "TEXT"),
                Map.of("category", "贷款申请表", "content", "贷款申请表材料", "declaredFormat", "TEXT"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION, CapabilityRequestV1.Operation.QUERY,
                context, Map.of("materials", materials), null, null, Map.of(), null);
    }

    private static Path personalBaseline() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path baseline = current.resolve("domain-packs/smart-audit/personal-loan-audit/rules/personal-rule-baseline.v1.json");
            if (Files.isRegularFile(baseline)) return baseline;
            current = current.getParent();
        }
        throw new IllegalStateException("personal audit baseline not found");
    }

    private static Map<String, SmartAuditModelProvider.BankPolicyParameter> approvedBankParameters() {
        SmartAuditModelProvider.BankPolicyParameter parameter = new SmartAuditModelProvider.BankPolicyParameter(
                "bank-policy-v1", LocalDate.of(2026, 1, 1), Map.of("type", "NUMBER", "value", 1), true);
        return Map.of("BORROWER_AGE_AND_TERM_LIMITS", parameter,
                "INCOME_VERIFICATION_PERIOD_AND_VARIANCE", parameter,
                "REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST", parameter,
                "COLLATERAL_AND_LOAN_AMOUNT_LIMITS", parameter);
    }

    private static SmartAuditModelRequest.Material material(String category) {
        return new SmartAuditModelRequest.Material(category, category + "示例材料", "TEXT");
    }

    private enum ExplanationField { NARRATIVE, SUPPLEMENT_HINT, MANUAL_REVIEW_HINT }
}
