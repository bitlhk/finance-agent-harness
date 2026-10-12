package com.openjiuwen.intelligence.finance.harness;

import io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelPort;
import io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelProvider;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/** Installs exactly one in-process, query-only Smart Audit scenario. */
public final class FinanceHarnessProfileSupport {
    private FinanceHarnessProfileSupport() {}

    public static Optional<SmartAuditCapabilityAdapter> maybeAdapter(Environment environment) {
        if (!"internal-control".equals(environment.getProperty(
                "three-engine.finance-harness.profile", ""))) return Optional.empty();
        String scenario = property(environment, "three-engine.finance-harness.smart-audit.scenario");
        if (!"personal-loan".equals(scenario) && !"enterprise-credit".equals(scenario)) return Optional.empty();
        Path baseline = Path.of(property(environment,
                "three-engine.finance-harness.smart-audit.personal-rule-baseline"));
        SmartAuditModelPort model = ignored -> {
            throw new IllegalStateException("SMART_AUDIT_MODEL_NOT_CONFIGURED");
        };
        String policyId = environment.getProperty(
                "three-engine.finance-harness.smart-audit.controlled-test-policy-id", "").trim();
        var provider = new SmartAuditModelProvider(new SmartAuditModelProvider.Config(
                baseline, "local-deterministic-v1", controlledTestParameters(policyId)), model);
        return Optional.of(new SmartAuditCapabilityAdapter(provider, scenario,
                property(environment, "three-engine.finance-harness.smart-audit.principal-id"),
                property(environment, "three-engine.finance-harness.smart-audit.subject-ref"),
                property(environment, "three-engine.finance-harness.smart-audit.session-ref")));
    }

    private static String property(Environment environment, String name) {
        return environment.getProperty(name, "").trim();
    }

    /**
     * Preview-only values are intentionally opt-in and have a distinct policy identifier. The
     * provider still returns advisory, manual-review-required results; these values cannot
     * authorize a lending decision.
     */
    private static Map<String, SmartAuditModelProvider.BankPolicyParameter> controlledTestParameters(String policyId) {
        if (!"PPL-DEMO-2026-01".equals(policyId)) return Map.of();
        LocalDate effective = LocalDate.of(2026, 1, 1);
        return Map.of(
                "BORROWER_AGE_AND_TERM_LIMITS", parameter(policyId, effective, "AGE_PLUS_TERM_LE_70"),
                "INCOME_VERIFICATION_PERIOD_AND_VARIANCE", parameter(policyId, effective, "MONTHLY_VARIANCE_LE_15_PERCENT"),
                "REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST", parameter(policyId, effective, "PAYMENT_TO_INCOME_LE_50_PERCENT_STRESS_20_PERCENT"),
                "COLLATERAL_AND_LOAN_AMOUNT_LIMITS", parameter(policyId, effective, "LTV_LE_70_PERCENT"));
    }

    private static SmartAuditModelProvider.BankPolicyParameter parameter(String policyId, LocalDate effective, String value) {
        return new SmartAuditModelProvider.BankPolicyParameter(policyId, effective,
                Map.of("type", "TEXT", "value", value), true);
    }
}
