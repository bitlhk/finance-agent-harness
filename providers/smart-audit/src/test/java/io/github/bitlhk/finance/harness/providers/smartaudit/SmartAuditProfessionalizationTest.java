package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SmartAuditProfessionalizationTest {

    @Test
    void evidenceStatusesDistinguishVerificationFromMaterialPresence() {
        assertEquals(List.of(
                        AuditEvidenceStatus.VERIFIED,
                        AuditEvidenceStatus.UNVERIFIED,
                        AuditEvidenceStatus.MISSING,
                        AuditEvidenceStatus.CONFLICTING,
                        AuditEvidenceStatus.EXPIRED),
                List.of(AuditEvidenceStatus.values()));
    }

    @Test
    void outcomeKeepsEvidenceAndNeverMeansApproval() {
        var outcome = new AuditRuleOutcome(
                "PERSONAL_INCOME_PERIOD_V1",
                "policy-v1",
                AuditRuleOutcome.Status.CANNOT_DETERMINE,
                List.of("tax:page-2"),
                Map.of("months", 6),
                "回执缺失");

        assertEquals(AuditRuleOutcome.Status.CANNOT_DETERMINE, outcome.status());
        assertEquals(List.of("tax:page-2"), outcome.evidence());
        assertEquals(Set.of("ruleId", "policyVersion", "status", "evidence", "inputs", "reason"),
                outcome.toMap().keySet());
        assertFalse(outcome.toMap().containsKey("approval"));
    }

    @Test
    void outcomeRejectsBlankRequiredFieldsAndReasons() {
        assertThrows(IllegalArgumentException.class, () -> outcome(" ", "policy-v1", "reason"));
        assertThrows(IllegalArgumentException.class, () -> outcome("RULE_V1", " ", "reason"));
        for (AuditRuleOutcome.Status status : AuditRuleOutcome.Status.values()) {
            assertThrows(IllegalArgumentException.class, () -> new AuditRuleOutcome(
                    "RULE_V1", "policy-v1", status, List.of(), Map.of(), " "));
        }
    }

    @Test
    void outcomeDeepFreezesJsonLikeInputsAndDoesNotExposeSourceMutations() {
        Map<String, Object> sourceNested = new LinkedHashMap<>();
        sourceNested.put("months", 6);
        List<Object> sourceList = new java.util.ArrayList<>(List.of(sourceNested));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("income", sourceList);

        AuditRuleOutcome outcome = new AuditRuleOutcome("PERSONAL_INCOME_PERIOD_V1", "policy-v1",
                AuditRuleOutcome.Status.MET, List.of("tax:page-2"), source, "verified evidence");
        sourceNested.put("months", 12);
        sourceList.add(Map.of("unexpected", true));
        source.put("newInput", "must not leak");

        Map<String, Object> rendered = outcome.toMap();
        Map<?, ?> renderedIncome = (Map<?, ?>) ((List<?>) ((Map<?, ?>) rendered.get("inputs")).get("income")).getFirst();
        assertEquals(6, renderedIncome.get("months"));
        assertFalse(((Map<?, ?>) rendered.get("inputs")).containsKey("newInput"));
        assertThrows(UnsupportedOperationException.class,
                () -> ((Map<Object, Object>) renderedIncome).put("months", 24));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Object>) ((Map<?, ?>) rendered.get("inputs")).get("income")).add(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AuditRuleOutcome("RULE_V1", "policy-v1",
                AuditRuleOutcome.Status.MET, List.of(), Map.of("unsupported", new StringBuilder("mutable")), "reason"));
    }

    @Test
    void outcomeRejectsNonFiniteFloatingPointValuesAtAnyInputDepth() {
        for (Number value : List.of(
                Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, () -> new AuditRuleOutcome(
                    "RULE_V1", "policy-v1", AuditRuleOutcome.Status.MET, List.of(),
                    Map.of("nested", List.of(Map.of("value", value))), "reason"));
        }
    }

    @Test
    void outcomeValidatesAndFreezesEvidenceReferences() {
        List<Object> mutableEvidence = new ArrayList<>(List.of("tax:page-2"));
        AuditRuleOutcome outcome = outcomeWithEvidence(mutableEvidence);
        mutableEvidence.set(0, "changed-after-construction");
        mutableEvidence.add("unexpected");

        assertEquals(List.of("tax:page-2"), outcome.evidence());
        assertThrows(UnsupportedOperationException.class, () -> outcome.evidence().add("must-not-add"));
        assertEquals(List.of(), outcomeWithEvidence(List.of()).evidence());
        assertThrows(IllegalArgumentException.class, () -> outcomeWithEvidence(List.of(" ")));
        assertThrows(IllegalArgumentException.class, () -> outcomeWithEvidence(new ArrayList<>(List.of((Object) 7))));
        List<Object> nullEvidence = new ArrayList<>();
        nullEvidence.add(null);
        assertThrows(IllegalArgumentException.class, () -> outcomeWithEvidence(nullEvidence));
    }

    private static AuditRuleOutcome outcome(String ruleId, String policyVersion, String reason) {
        return new AuditRuleOutcome(ruleId, policyVersion, AuditRuleOutcome.Status.MET,
                List.of("tax:page-2"), Map.of("months", 6), reason);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static AuditRuleOutcome outcomeWithEvidence(List<?> evidence) {
        return new AuditRuleOutcome("RULE_V1", "policy-v1", AuditRuleOutcome.Status.MET,
                (List) evidence, Map.of("months", 6), "reason");
    }
}
