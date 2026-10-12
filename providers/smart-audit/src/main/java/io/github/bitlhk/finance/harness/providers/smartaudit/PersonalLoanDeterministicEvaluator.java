package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies the public personal-loan baseline deterministically.  Uploaded text is evidence of
 * submission only: it is deliberately not treated as verified identity, income, or eligibility.
 */
public final class PersonalLoanDeterministicEvaluator {
    /**
     * Compatibility entry point for the original borrower-qualification-only evaluator contract.
     * Provider code must call the task-aware overload so a different task cannot inherit this rule.
     */
    public Evaluation evaluate(String regulatoryPolicyVersion,
                               List<SmartAuditModelRequest.Material> materials,
                               List<String> missingBankParameterIds) {
        return evaluate(regulatoryPolicyVersion,
                SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION),
                materials, missingBankParameterIds);
    }

    public Evaluation evaluate(String regulatoryPolicyVersion,
                               SmartAuditCapabilityCatalog.TaskDefinition task,
                               List<SmartAuditModelRequest.Material> materials,
                               List<String> missingBankParameterIds) {
        if (regulatoryPolicyVersion == null || regulatoryPolicyVersion.isBlank()) {
            throw new IllegalArgumentException("regulatoryPolicyVersion is required");
        }
        if (task == null || !task.personal()) throw new IllegalArgumentException("personal task is required");
        List<SmartAuditModelRequest.Material> submitted = materials == null ? List.of() : List.copyOf(materials);
        if (submitted.isEmpty()) throw new IllegalArgumentException("materials are required");
        List<String> missingParameters = missingBankParameterIds == null ? List.of() : missingBankParameterIds.stream()
                .filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().sorted().toList();

        List<AuditEvidenceAssessment> evidence = new ArrayList<>();
        for (int index = 0; index < submitted.size(); index++) {
            SmartAuditModelRequest.Material material = submitted.get(index);
            evidence.add(new AuditEvidenceAssessment(material.category(), material.category() + "#" + (index + 1),
                    AuditEvidenceStatus.UNVERIFIED, "材料已提交，但真实性、准确性和完整性尚未经过独立核验。"));
        }
        List<String> evidenceReferences = evidence.stream().map(AuditEvidenceAssessment::locator).toList();
        List<AuditRuleOutcome> outcomes = List.of(
                bankThresholdOutcome(regulatoryPolicyVersion, evidenceReferences, missingParameters),
                writtenApplicationOutcome(regulatoryPolicyVersion, task, evidenceReferences, submitted.size()),
                new AuditRuleOutcome("PERSONAL_LOAN_DUE_DILIGENCE_EVIDENCE_VERIFICATION_V1", regulatoryPolicyVersion,
                        AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                        Map.of("unverifiedEvidenceCount", evidence.size()),
                        "材料尚未完成真实性、准确性和完整性核验，须由具备权限的人工调查人员复核。"));
        return new Evaluation(evidence, outcomes, missingParameters, true);
    }

    private static AuditRuleOutcome writtenApplicationOutcome(String policyVersion,
                                                               SmartAuditCapabilityCatalog.TaskDefinition task,
                                                               List<String> evidenceReferences,
                                                               int submittedMaterialCount) {
        if (!task.materialCategories().contains("贷款申请表")) {
            return new AuditRuleOutcome("PERSONAL_LOAN_WRITTEN_APPLICATION_AND_EVIDENCE_V1", policyVersion,
                    AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                    Map.of("taskId", task.taskId(), "reasonCode", "TASK_CONTRACT_EXCLUDES_LOAN_APPLICATION"),
                    "当前任务合同不包含书面贷款申请；不得将本任务材料收集标记为已满足书面申请要求，须结合申请材料人工复核。");
        }
        return new AuditRuleOutcome("PERSONAL_LOAN_WRITTEN_APPLICATION_AND_EVIDENCE_V1", policyVersion,
                AuditRuleOutcome.Status.MET, evidenceReferences,
                Map.of("taskId", task.taskId(), "submittedMaterialCount", submittedMaterialCount),
                "已收到当前任务要求的书面申请及证明材料；本结果仅确认材料收集，不构成借款资格或授信结论。");
    }

    private static AuditRuleOutcome bankThresholdOutcome(String policyVersion, List<String> evidenceReferences,
                                                         List<String> missingParameters) {
        if (!missingParameters.isEmpty()) {
            return new AuditRuleOutcome("PERSONAL_LOAN_BANK_THRESHOLD_APPLICATION_V1", policyVersion,
                    AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                    Map.of("missingParameterIds", missingParameters),
                    "缺少已批准且生效的行内政策参数，系统不得形成满足结论，须人工复核。" );
        }
        return new AuditRuleOutcome("PERSONAL_LOAN_BANK_THRESHOLD_APPLICATION_V1", policyVersion,
                AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                Map.of("reasonCode", "STRUCTURED_FACTS_NOT_AVAILABLE"),
                "已配置行内参数，但当前材料尚未形成经核验的结构化事实，须人工复核。" );
    }

    public record Evaluation(List<AuditEvidenceAssessment> evidence,
                             List<AuditRuleOutcome> ruleOutcomes,
                             List<String> missingBankParameterIds,
                             boolean manualReviewRequired) {
        public Evaluation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
            missingBankParameterIds = missingBankParameterIds == null ? List.of() : List.copyOf(missingBankParameterIds);
            if (evidence.isEmpty() || ruleOutcomes.isEmpty() || !manualReviewRequired) {
                throw new IllegalArgumentException("deterministic personal audit must retain evidence, outcomes, and human review");
            }
        }

        /** This package deliberately cannot return a lending decision. */
        public boolean supportsApprovalOrRejection() {
            return false;
        }
    }
}
