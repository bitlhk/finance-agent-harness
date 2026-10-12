package io.github.bitlhk.finance.harness.providers.smartaudit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Deterministic enterprise-credit material assessment. Submission establishes only that an
 * artifact was supplied; it never authenticates it, calculates a bank threshold, or produces a
 * credit decision.
 */
public final class EnterpriseCreditDeterministicEvaluator {
    private static final String POLICY_VERSION = "enterprise-credit-document-contract.v1";

    public Evaluation evaluate(SmartAuditCapabilityCatalog.TaskDefinition task,
                               List<SmartAuditModelRequest.Material> materials) {
        if (task == null || task.personal()) throw new IllegalArgumentException("enterprise task is required");
        List<SmartAuditModelRequest.Material> submitted = materials == null ? List.of() : List.copyOf(materials);
        List<AuditEvidenceAssessment> evidence = new ArrayList<>();
        for (int index = 0; index < submitted.size(); index++) {
            SmartAuditModelRequest.Material material = submitted.get(index);
            boolean unbalancedBalanceSheet = hasUnbalancedBalanceSheetEquation(material);
            evidence.add(new AuditEvidenceAssessment(material.category(), material.category() + "#" + (index + 1),
                    unbalancedBalanceSheet ? AuditEvidenceStatus.CONFLICTING : AuditEvidenceStatus.UNVERIFIED,
                    unbalancedBalanceSheet
                            ? "结构化资产负债表的资产总计不等于负债总计与所有者权益合计之和，须人工复核。"
                            : "材料已提交，但真实性、完整性、有效性和一致性尚未经过独立核验。"));
        }
        List<String> evidenceReferences = evidence.stream().map(AuditEvidenceAssessment::locator).toList();
        List<String> submittedCategories = submitted.stream().map(SmartAuditModelRequest.Material::category)
                .distinct().toList();
        List<String> missingCategories = task.materialCategories().stream()
                .filter(category -> !submittedCategories.contains(category)).toList();

        List<AuditRuleOutcome> outcomes = List.of(
                documentCollectionOutcome(task, evidenceReferences, submittedCategories, missingCategories),
                new AuditRuleOutcome(rulePrefix(task) + "_EVIDENCE_VERIFICATION_V1", POLICY_VERSION,
                        AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                        Map.of("unverifiedEvidenceCount", evidence.size()),
                        "当前材料未完成独立真实性、完整性、有效性和一致性核验，须由具备权限的人工调查人员复核。"),
                balanceSheetEquationOutcome(task, evidenceReferences, submitted),
                new AuditRuleOutcome(rulePrefix(task) + "_TASK_ANALYSIS_V1", POLICY_VERSION,
                        analysisStatus(missingCategories), evidenceReferences,
                        Map.of("taskId", task.taskId(), "missingCategories", missingCategories),
                        analysisReason(task, missingCategories)));
        return new Evaluation(evidence, outcomes, missingCategories, true);
    }

    private static AuditRuleOutcome balanceSheetEquationOutcome(
            SmartAuditCapabilityCatalog.TaskDefinition task,
            List<String> evidenceReferences,
            List<SmartAuditModelRequest.Material> submitted) {
        boolean conflict = submitted.stream().anyMatch(EnterpriseCreditDeterministicEvaluator::hasUnbalancedBalanceSheetEquation);
        return new AuditRuleOutcome(rulePrefix(task) + "_BALANCE_SHEET_EQUATION_V1", POLICY_VERSION,
                AuditRuleOutcome.Status.CANNOT_DETERMINE, evidenceReferences,
                Map.of("objectiveConflictDetected", conflict),
                conflict
                        ? "已发现结构化资产负债表内部勾稽不一致；系统不判断财务事实真伪，须人工调查复核。"
                        : "未发现可重算的资产负债表内部勾稽不一致；该结果不验证材料真实性或财务事实。"
        );
    }

    private static boolean hasUnbalancedBalanceSheetEquation(SmartAuditModelRequest.Material material) {
        if (!"资产负债表".equals(material.category()) || !"JSON".equalsIgnoreCase(material.declaredFormat())) return false;
        try {
            JsonNode document = HarnessJson.mapper().readTree(material.content());
            BigDecimal assets = amount(document, "资产总计", "资产总额");
            BigDecimal liabilities = amount(document, "负债总计", "负债总额", "负债合计");
            BigDecimal equity = amount(document, "所有者权益合计", "所有者权益");
            return assets != null && liabilities != null && equity != null
                    && assets.compareTo(liabilities.add(equity)) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static BigDecimal amount(JsonNode document, String... names) {
        for (String name : names) {
            JsonNode value = document.path(name);
            if (!value.isMissingNode() && !value.isNull()) {
                try {
                    return new BigDecimal(value.asText().trim());
                } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private static AuditRuleOutcome documentCollectionOutcome(SmartAuditCapabilityCatalog.TaskDefinition task,
                                                              List<String> evidenceReferences,
                                                              List<String> submittedCategories,
                                                              List<String> missingCategories) {
        if (!missingCategories.isEmpty()) {
            return new AuditRuleOutcome(rulePrefix(task) + "_DOCUMENT_COLLECTION_V1", POLICY_VERSION,
                    AuditRuleOutcome.Status.NEEDS_SUPPLEMENT, evidenceReferences,
                    Map.of("requiredCategories", task.materialCategories(), "submittedCategories", submittedCategories,
                            "missingCategories", missingCategories),
                    "当前固定任务所需材料尚未收齐，须补充缺失材料后再进行人工核验。");
        }
        return new AuditRuleOutcome(rulePrefix(task) + "_DOCUMENT_COLLECTION_V1", POLICY_VERSION,
                AuditRuleOutcome.Status.MET, evidenceReferences,
                Map.of("requiredCategories", task.materialCategories(), "submittedCategories", submittedCategories),
                "已收到当前固定任务要求的材料；本结果仅确认材料收集，不确认材料真实性、财务事实或任何授信处理结论。");
    }

    private static AuditRuleOutcome.Status analysisStatus(List<String> missingCategories) {
        return missingCategories.isEmpty()
                ? AuditRuleOutcome.Status.CANNOT_DETERMINE : AuditRuleOutcome.Status.NEEDS_SUPPLEMENT;
    }

    private static String analysisReason(SmartAuditCapabilityCatalog.TaskDefinition task, List<String> missingCategories) {
        if (!missingCategories.isEmpty()) {
            return "当前任务缺少必需材料，无法开展" + task.taskId() + "分析，须补件并人工复核。";
        }
        return "材料均为未核验提交件，系统未取得经核验的结构化财务、交易或尽调事实，须人工复核。";
    }

    private static String rulePrefix(SmartAuditCapabilityCatalog.TaskDefinition task) {
        return "ENTERPRISE_" + task.taskId().replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    public record Evaluation(List<AuditEvidenceAssessment> evidence,
                             List<AuditRuleOutcome> ruleOutcomes,
                             List<String> missingCategories,
                             boolean manualReviewRequired) {
        public Evaluation {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
            missingCategories = missingCategories == null ? List.of() : List.copyOf(new LinkedHashSet<>(missingCategories));
            if (ruleOutcomes.isEmpty() || !manualReviewRequired) {
                throw new IllegalArgumentException("deterministic enterprise audit must retain outcomes and human review");
            }
        }

        /** This package deliberately cannot return a credit approval, rejection, or disbursement decision. */
        public boolean supportsApprovalOrRejection() {
            return false;
        }
    }
}
