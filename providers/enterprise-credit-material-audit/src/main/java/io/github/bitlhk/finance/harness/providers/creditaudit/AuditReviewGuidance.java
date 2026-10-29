package io.github.bitlhk.finance.harness.providers.creditaudit;

import java.util.List;
import java.util.Map;

/** Review actions derived from the frozen check, not model-assigned risk or approval. */
final class AuditReviewGuidance {
    private AuditReviewGuidance() { }

    static Map<String, Object> from(DeterministicMaterialEvaluator.Evaluation evaluation) {
        String impact;
        String nextStep;
        switch (evaluation.status()) {
            case CONSISTENT -> {
                impact = "本项字段核对未发现差额；不据此确认材料真实或贷款可获批准。";
                nextStep = "本项无需因金额差额补件；其他审核事项不在本次结论内。";
            }
            case INCONSISTENT -> {
                impact = "当前可比金额存在差额，尚不能以两侧金额一致为依据继续判断。差额本身不是风险等级或虚假认定。";
                nextStep = "核对下列来源字段并解释差额；仅在确认输入有误时补正对应材料，保留原件后重新核验。";
            }
            case MISSING_REQUIRED_FIELDS -> {
                impact = "当前材料不足以完成本项计算；缺失不等于零，也不代表整个案件缺少所有资料。";
                nextStep = "只补充本项列出的必要材料或字段，并保留其主体、期间与来源，再重新核验。";
            }
            case NOT_COMPARABLE -> {
                impact = "两侧材料口径尚未对齐，不能认定金额冲突或计算风险等级。";
                nextStep = "核对列出的口径差异；提供同一主体、期间、币种、单位和业务口径的材料后再比较，不强行折算。";
            }
            default -> {
                impact = "本项尚不能形成确定性结论，不据此判断材料异常。";
                nextStep = "先明确本次采用的材料；在新的材料草稿中保留唯一适用版本后重新冻结，原报告不覆盖。";
            }
        }
        List<?> dimensions = evaluation.verificationBasis().get("dimensions") instanceof List<?> values ? values : List.of();
        return Map.of("schemaVersion", "finance-audit-review-guidance.v1",
                "checkId", evaluation.checkId(), "status", evaluation.status().name(),
                "impact", impact, "nextStep", nextStep, "missingInputs", evaluation.missingInputs(),
                "evidence", evaluation.evidence(), "comparisonDimensions", dimensions,
                "approvalDecision", false);
    }
}
