package io.github.bitlhk.finance.harness.providers.smartaudit;

import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Managed-host factory for the in-process Smart Audit provider.
 *
 * <p>The host-owned conversational model is deliberately not injected into this
 * provider. Authoritative audit findings remain deterministic; when a personal
 * audit asks for an optional explanation, an unavailable explanation port is
 * handled by the provider as an absent explanation rather than a failed audit.</p>
 */
public final class SmartAuditFactoryV01 implements ManagedProviderFactoryV01 {
    private static final String DEFAULT_PERSONAL_RULE_BASELINE =
            "assets/domain-packs/smart-audit/personal-loan-audit/rules/personal-rule-baseline.v1.json";
    /** Candidate-scoped host binding. Environment remains the deployment default. */
    public static final String CONTROLLED_TEST_POLICY_PROPERTY =
            "finance.harness.smart-audit.controlled-test-policy-id";

    @Override public String providerId() { return SmartAuditModelProvider.PROVIDER_ID; }

    @Override public String connectionKind() { return "in-process-smart-audit.v1"; }

    @Override public Map<String, Set<String>> requirements() {
        var result = new LinkedHashMap<String, Set<String>>();
        for (var task : SmartAuditCapabilityCatalog.tasks())
            result.put(task.capabilityId(), Set.of(task.requiredScope()));
        return Map.copyOf(result);
    }

    @Override public DescribedFinanceProvider create(Connection connection,
                                                       Map<String, BoundFinanceProvider> dependencies) {
        if (!dependencies.isEmpty()) throw new IllegalArgumentException("SMART_AUDIT_DEPENDENCIES_UNSUPPORTED");
        String configured = System.getenv("FH_SMART_AUDIT_PERSONAL_RULE_BASELINE");
        Path baseline = Path.of(configured == null || configured.isBlank() ? DEFAULT_PERSONAL_RULE_BASELINE : configured);
        String modelVersion = System.getenv("FH_MODEL_NAME");
        if (modelVersion == null || modelVersion.isBlank()) modelVersion = "host-configured-model";
        String controlledPolicy = System.getenv("FH_SMART_AUDIT_CONTROLLED_TEST_POLICY_ID");
        if (controlledPolicy == null || controlledPolicy.isBlank()) {
            controlledPolicy = System.getProperty(CONTROLLED_TEST_POLICY_PROPERTY, "");
        }
        SmartAuditModelPort optionalExplanationPort = request -> {
            throw new IllegalStateException("OPTIONAL_EXPLANATION_PORT_UNAVAILABLE");
        };
        return new SmartAuditModelProvider(new SmartAuditModelProvider.Config(
                baseline, modelVersion, controlledTestParametersFor(controlledPolicy)), optionalExplanationPort);
    }

    /**
     * Explicit, preview-only bank-policy fixture. It is activated only by the
     * host environment and identifies itself with a non-production policy ID;
     * audit results remain advisory and require human review.
     */
    static Map<String, SmartAuditModelProvider.BankPolicyParameter> controlledTestParametersFor(String policyId) {
        if (!"PPL-DEMO-2026-01".equals(policyId)) return Map.of();
        LocalDate effectiveDate = LocalDate.of(2026, 1, 1);
        return Map.of(
                "BORROWER_AGE_AND_TERM_LIMITS", parameter(policyId, effectiveDate, "AGE_PLUS_TERM_LE_70"),
                "INCOME_VERIFICATION_PERIOD_AND_VARIANCE", parameter(policyId, effectiveDate, "MONTHLY_VARIANCE_LE_15_PERCENT"),
                "REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST", parameter(policyId, effectiveDate, "PAYMENT_TO_INCOME_LE_50_PERCENT_STRESS_20_PERCENT"),
                "COLLATERAL_AND_LOAN_AMOUNT_LIMITS", parameter(policyId, effectiveDate, "LTV_LE_70_PERCENT"));
    }

    private static SmartAuditModelProvider.BankPolicyParameter parameter(String policyId, LocalDate effectiveDate, String value) {
        return new SmartAuditModelProvider.BankPolicyParameter(policyId, effectiveDate,
                Map.of("type", "TEXT", "value", value), true);
    }
}
