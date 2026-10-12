package io.github.bitlhk.finance.harness.providers.smartaudit;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SmartAuditFactoryV01Test {
    @Test
    void controlledDemoPolicyProvidesOnlyApprovedEffectiveParameters() {
        var parameters = SmartAuditFactoryV01.controlledTestParametersFor("PPL-DEMO-2026-01");

        assertEquals(4, parameters.size());
        assertTrue(parameters.values().stream().allMatch(SmartAuditModelProvider.BankPolicyParameter::approved));
        assertTrue(parameters.values().stream().allMatch(value -> value.policyVersion().equals("PPL-DEMO-2026-01")));
        assertTrue(SmartAuditFactoryV01.controlledTestParametersFor("unknown").isEmpty());
    }
}
