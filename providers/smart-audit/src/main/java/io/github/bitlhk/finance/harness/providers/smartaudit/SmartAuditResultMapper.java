package io.github.bitlhk.finance.harness.providers.smartaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Maps a structured model response into the standard, advisory-only capability result. */
public final class SmartAuditResultMapper {
    private static final String SOURCE = "smart-audit-model-provider";
    private static final String NON_DECISIONAL_EXPLANATION_LABEL = "审核说明（非审批决定）";
    private static final String DETERMINISTIC_AUDIT_LABEL = "审核建议（非审批决定）";
    private static final Pattern MARKUP_OR_LINK = Pattern.compile("(?i)<[^>]+>|https?://\\S+|```");
    /*
     * The explanation schema is intentionally not a business-decision channel.  These tokens are
     * rejected after NFKC/case/whitespace/punctuation/Unicode-format normalization, rather than
     * attempting to infer whether a nearby noun makes a decision phrase harmless. Structured rule
     * outcomes are the sole place where MET/NOT_MET/CANNOT_DETERMINE may appear.
     */
    private static final Set<String> FORBIDDEN_DECISION_TOKENS = Set.of(
            "审批", "批复", "批准", "同意", "允许", "准予", "核准", "通过", "拒绝", "驳回", "否决", "不予",
            "放款", "发放贷款", "贷款申请", "授信", "额度",
            "approve", "approved", "approval", "reject", "rejected", "rejection", "deny", "denied",
            "allow", "allowed", "grant", "granted", "disburse", "disbursement", "loanapplication",
            "creditapplication");

    public CapabilityResultV1 map(SmartAuditModelResponse response, CapabilityRequestV1 request,
                                  SmartAuditCapabilityCatalog.TaskDefinition task,
                                  Map<String, Object> assetSummary) {
        ExplanationRender rendered = renderExplanation(response);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advisoryOnly", true);
        data.put("taskId", task.taskId());
        data.put("capabilityId", task.capabilityId());
        data.put("completeness", "不可判断");
        data.put("manualReviewRequired", true);
        data.put("assetSummary", assetSummary);
        data.put("evidence", List.of());
        data.put("modelExplanation", rendered.toMap());
        if (response != null && !response.modelVersion().isBlank()) data.put("explanationModelVersion", response.modelVersion());
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                explanationContent(rendered), Map.copyOf(data), List.of(),
                List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), SOURCE, Instant.now(), null, null);
    }

    public CapabilityResultV1 needUser(CapabilityRequestV1 request, List<String> missingInputs) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.NEED_USER,
                "当前审核材料不完整，暂无法形成审核建议。", Map.of("advisoryOnly", true, "completeness", "不可判断"),
                List.copyOf(missingInputs), List.of("SUPPLY_REQUIRED_MATERIALS"), SOURCE, Instant.now(), null, null);
    }

    /** Maps deterministic evidence and rule evaluation for personal-loan tasks before any model explanation. */
    public CapabilityResultV1 mapDeterministicPersonal(
            CapabilityRequestV1 request,
            SmartAuditCapabilityCatalog.TaskDefinition task,
            Map<String, Object> assetSummary,
            PersonalLoanDeterministicEvaluator.Evaluation evaluation) {
        return mapDeterministicPersonal(request, task, assetSummary, evaluation, null);
    }

    /**
     * Maps immutable deterministic findings. A model response is optional explanatory text only and
     * cannot control evidence, rule outcomes, missing inputs, next actions, or review state.
     */
    public CapabilityResultV1 mapDeterministicPersonal(
            CapabilityRequestV1 request,
            SmartAuditCapabilityCatalog.TaskDefinition task,
            Map<String, Object> assetSummary,
            PersonalLoanDeterministicEvaluator.Evaluation evaluation,
            SmartAuditModelResponse explanation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advisoryOnly", true);
        data.put("taskId", task.taskId());
        data.put("capabilityId", task.capabilityId());
        data.put("completeness", "不可判断");
        data.put("manualReviewRequired", evaluation.manualReviewRequired());
        data.put("assetSummary", assetSummary);
        data.put("evidence", evaluation.evidence().stream().map(AuditEvidenceAssessment::toMap).toList());
        data.put("ruleOutcomes", evaluation.ruleOutcomes().stream().map(AuditRuleOutcome::toMap).toList());
        if (explanation != null) {
            data.put("modelExplanation", renderExplanation(explanation).toMap());
            if (!explanation.modelVersion().isBlank()) data.put("explanationModelVersion", explanation.modelVersion());
        }
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                deterministicContent(explanation),
                Map.copyOf(data), evaluation.missingBankParameterIds(),
                List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), SOURCE, Instant.now(), null, null);
    }

    /** Maps enterprise document-collection and evidence findings without granting model authority. */
    public CapabilityResultV1 mapDeterministicEnterprise(
            CapabilityRequestV1 request,
            SmartAuditCapabilityCatalog.TaskDefinition task,
            EnterpriseCreditDeterministicEvaluator.Evaluation evaluation) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("advisoryOnly", true);
        data.put("taskId", task.taskId());
        data.put("capabilityId", task.capabilityId());
        data.put("completeness", "不可判断");
        data.put("manualReviewRequired", evaluation.manualReviewRequired());
        data.put("evidence", evaluation.evidence().stream().map(AuditEvidenceAssessment::toMap).toList());
        data.put("ruleOutcomes", evaluation.ruleOutcomes().stream().map(AuditRuleOutcome::toMap).toList());
        data.put("missingCategories", evaluation.missingCategories());
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                deterministicContent(null), Map.copyOf(data), evaluation.missingCategories(),
                List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), SOURCE, Instant.now(), null, null);
    }

    public CapabilityResultV1 failure(CapabilityRequestV1 request, String failureClass, String sourceCode,
                                      boolean retryable, String message, List<String> nextActions) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.FAILED,
                "当前无法完成智能审核查询。", Map.of("advisoryOnly", true), List.of(), List.copyOf(nextActions),
                SOURCE, Instant.now(), null,
                new CapabilityResultV1.Failure(failureClass, sourceCode, retryable, message, List.of()));
    }

    private static String deterministicContent(SmartAuditModelResponse explanation) {
        String content = DETERMINISTIC_AUDIT_LABEL
                + "\n已完成确定性材料与规则核验；当前结论须人工复核，不形成任何业务处理结论。";
        return explanation == null ? content : content + "\n\n" + explanationContent(renderExplanation(explanation));
    }

    private static String explanationContent(ExplanationRender rendered) {
        return NON_DECISIONAL_EXPLANATION_LABEL + "\n" + rendered.narrative();
    }

    private static ExplanationRender renderExplanation(SmartAuditModelResponse response) {
        if (response == null || response.explanation() == null) return ExplanationRender.unavailable();
        SmartAuditModelResponse.Explanation explanation = response.explanation();
        if (!safeText(explanation.narrative()) || !safeHints(explanation.supplementHints())
                || !safeHints(explanation.manualReviewHints())) return ExplanationRender.unavailable();
        return new ExplanationRender(explanation.narrative().trim(), List.copyOf(explanation.supplementHints()),
                List.copyOf(explanation.manualReviewHints()));
    }

    private static boolean safeHints(List<String> hints) {
        return hints != null && hints.stream().allMatch(SmartAuditResultMapper::safeText);
    }

    private static boolean safeText(String text) {
        if (text == null || text.isBlank() || text.length() > 1_000 || MARKUP_OR_LINK.matcher(text).find()) return false;
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Z}\\p{P}\\p{S}\\p{Cf}\\s]+", "");
        return FORBIDDEN_DECISION_TOKENS.stream().noneMatch(normalized::contains);
    }

    private record ExplanationRender(String narrative, List<String> supplementHints, List<String> manualReviewHints) {
        static ExplanationRender unavailable() {
            return new ExplanationRender("模型说明不可用", List.of(), List.of("请以结构化规则结果和人工复核为准。"));
        }

        Map<String, Object> toMap() {
            return Map.of("label", NON_DECISIONAL_EXPLANATION_LABEL, "narrative", narrative,
                    "supplementHints", supplementHints, "manualReviewHints", manualReviewHints);
        }
    }
}
