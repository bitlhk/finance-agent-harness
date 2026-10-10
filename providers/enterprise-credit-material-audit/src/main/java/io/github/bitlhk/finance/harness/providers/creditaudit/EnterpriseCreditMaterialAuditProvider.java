package io.github.bitlhk.finance.harness.providers.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class EnterpriseCreditMaterialAuditProvider implements DescribedFinanceProvider {
    public static final String ID = "enterprise-credit-material-audit";
    public static final String VERSION = "0.1.0-SNAPSHOT";
    public static final String FINANCIAL = DeterministicMaterialEvaluator.FINANCIAL;
    public static final String CROSS_MATERIAL = DeterministicMaterialEvaluator.CROSS_MATERIAL;

    private final MaterialSetRepository materials;
    private final DemoRuleSet rules;
    private final DeterministicMaterialEvaluator evaluator = new DeterministicMaterialEvaluator();

    public EnterpriseCreditMaterialAuditProvider(MaterialSetRepository materials, DemoRuleSet rules) {
        this.materials = Objects.requireNonNull(materials, "materials");
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    @Override public ProviderDescriptorV1 descriptor() {
        return descriptorValue();
    }

    static ProviderDescriptorV1 descriptorValue() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, ID, VERSION, List.of(
                new ProviderDescriptorV1.CapabilitySupport(FINANCIAL, CapabilityRequestV1.Operation.QUERY),
                new ProviderDescriptorV1.CapabilitySupport(CROSS_MATERIAL, CapabilityRequestV1.Operation.QUERY)));
    }

    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "enterprise material audit is read-only");
        }
        if (!descriptor().supports(request.capabilityId(), request.operation())) {
            return failure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                    "unsupported capability: " + request.capabilityId());
        }
        String reference = text(request.parameters().get("materialSetRef"));
        if (reference == null) {
            return failure(request, "INVALID_INPUT", "MATERIAL_SET_REF_REQUIRED", false,
                    "materialSetRef is required");
        }
        try {
            MaterialSet set = materials.load(reference, request.context());
            if (!Objects.equals(set.taskRef(), request.context().purpose())) {
                return failure(request, "ACCESS_DENIED", "MATERIAL_TASK_MISMATCH", false,
                        "material set is not bound to the current task");
            }
            if (!rules.capabilities().contains(request.capabilityId()) || !rules.activeOn(set.trustedBusinessDate())) {
                return unavailableRule(request, set);
            }
            DeterministicMaterialEvaluator.Evaluation evaluation = FINANCIAL.equals(request.capabilityId())
                    ? evaluator.evaluateFinancial(set, rules)
                    : evaluator.evaluateCrossMaterial(set, rules);
            return success(request, set, evaluation);
        } catch (SecurityException exception) {
            return failure(request, "ACCESS_DENIED", "MATERIAL_SET_SCOPE_MISMATCH", false,
                    "material set is outside the current owner, principal or session");
        } catch (IllegalArgumentException exception) {
            return failure(request, "INVALID_INPUT", exception.getMessage(), false,
                    "material set or rule input is invalid");
        } catch (Exception exception) {
            return failure(request, "TEMPORARY_FAILURE", "MATERIAL_SET_READ_FAILED", true,
                    "material set could not be read");
        }
    }

    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        return failure(request, "INVALID_INPUT", "READ_ONLY_PROVIDER", false,
                "enterprise material audit does not execute tasks");
    }

    private CapabilityResultV1 success(CapabilityRequestV1 request, MaterialSet set,
                                       DeterministicMaterialEvaluator.Evaluation evaluation) {
        var data = new LinkedHashMap<String, Object>();
        data.put("materialSetRef", set.materialSetId());
        data.put("materialSetDigestRefs", set.materials().stream().map(MaterialSet.Material::sha256).toList());
        data.put("status", evaluation.status().name());
        data.put("checkId", evaluation.checkId());
        data.put("rule", Map.of("id", evaluation.ruleSetId(), "version", evaluation.ruleVersion(),
                "classification", "DEMO", "businessDate", set.trustedBusinessDate().toString()));
        data.put("values", evaluation.values());
        data.put("verificationBasis", evaluation.verificationBasis());
        data.put("evidence", evaluation.evidence());
        data.put("advisoryOnly", true);
        data.put("authenticityVerified", false);
        data.put("approvalDecision", false);
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(), request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                evaluation.summary(), Map.copyOf(data), evaluation.missingInputs(),
                nextActions(evaluation.status()), "synthetic-enterprise-materials", Instant.now(), null, null);
    }

    private CapabilityResultV1 unavailableRule(CapabilityRequestV1 request, MaterialSet set) {
        Map<String, Object> data = Map.of(
                "materialSetRef", set.materialSetId(),
                "status", AuditStatus.NEEDS_HUMAN_REVIEW.name(),
                "rule", Map.of("id", rules.ruleSetId(), "version", rules.version(),
                        "classification", "DEMO", "businessDate", set.trustedBusinessDate().toString()),
                "advisoryOnly", true,
                "approvalDecision", false);
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(), request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                "当前业务日期没有可用的锁定演示规则，保留材料事实并转人工判断。", data, List.of(),
                List.of("CHECK_RULE_VERSION", "HUMAN_REVIEW"), "synthetic-enterprise-materials",
                Instant.now(), null, null);
    }

    private static List<String> nextActions(AuditStatus status) {
        return switch (status) {
            case CONSISTENT -> List.of("HUMAN_REVIEW_OPTIONAL");
            case INCONSISTENT -> List.of("REVIEW_SOURCE_FIELDS", "HUMAN_REVIEW");
            case MISSING_REQUIRED_FIELDS -> List.of("SUPPLEMENT_MATERIALS", "RERUN_CHECK");
            case NOT_COMPARABLE -> List.of("ALIGN_SCOPE_OR_PERIOD", "HUMAN_REVIEW");
            case NEEDS_HUMAN_REVIEW -> List.of("HUMAN_REVIEW");
        };
    }

    private static CapabilityResultV1 failure(CapabilityRequestV1 request, String failureClass,
                                               String sourceCode, boolean retryable, String message) {
        String requestId = request == null || request.context() == null ? "unknown-request" : request.context().requestId();
        String traceId = request == null || request.context() == null ? "unknown-trace" : request.context().traceId();
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, requestId, traceId,
                CapabilityResultV1.Status.FAILED, "材料核验未完成。", Map.of(), List.of(),
                List.of(retryable ? "RETRY_OR_HUMAN_REVIEW" : "CHECK_REQUEST"),
                "synthetic-enterprise-materials", Instant.now(), null,
                new CapabilityResultV1.Failure(failureClass,
                        sourceCode == null || sourceCode.isBlank() ? "INVALID_MATERIAL_INPUT" : sourceCode,
                        retryable, message, List.of()));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String result = String.valueOf(value).trim();
        return result.isEmpty() ? null : result;
    }
}
