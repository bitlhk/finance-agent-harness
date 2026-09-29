package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CheckpointPolicyCompatibilityTest {
    @Test void hostConfirmationRetainsItsPublicShapeAndTimeBoundaries() {
        var start = java.time.Instant.parse("2026-09-21T00:00:00Z");
        var binding = "a".repeat(64);
        var approval = new HostTaskConfirmation("event", binding, start, start.plusSeconds(300));
        assertEquals("event", approval.eventId());
        assertTrue(approval.validFor(binding, start));
        assertFalse(approval.validFor(binding, start.plusSeconds(300)));
        assertFalse(approval.validFor("b".repeat(64), start));
        assertThrows(IllegalArgumentException.class,
                () -> new HostTaskConfirmation("event", binding, start, start.plusSeconds(301)));
    }
    @Test void oldBridgeFingerprintHasFixedCanonicalBytes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("values", Arrays.asList(new BigDecimal("1.00"), null, "text"));
        value.put("nested", Map.of("b", 2L));
        value.put("a", 1.0);
        assertEquals("3dd4a296b5bc65e3b7dc0d9a69a7754854bd2981c93707d006b4a1578d57774a",
                OpenJiuwenFinanceBridge.fingerprint(value));
    }

    @Test void oldBridgeRecoveryRetainsUnknownAndOriginalParameters() {
        Map<String, Object> checkpoint = Map.of(
                OpenJiuwenFinanceBridge.CK_PARAMETERS, Map.of("amount", 1),
                OpenJiuwenFinanceBridge.CK_BINDING, "old-approval",
                OpenJiuwenFinanceBridge.CK_INPUTS, List.of("beneficiary"),
                OpenJiuwenFinanceBridge.CK_CONTINUATION, Map.of("submitOutcomeUnknown", true));
        var invocation = new OpenJiuwenInvocationV1("task", "transfer", "agent", Set.of(), Map.of(), checkpoint);
        var recovered = OpenJiuwenFinanceBridge.recoveryCheckpoint(invocation);
        assertEquals(Map.of("financeHarness.parameters", Map.of("amount", 1),
                "financeHarness.continuation", Map.of("submitOutcomeUnknown", true)), recovered);
        assertFalse(OpenJiuwenFinanceBridge.canSupplement(checkpoint, Map.of("amount", 1, "beneficiary", "new")));
        assertEquals(4, invocation.checkpoint().size());
    }
}
