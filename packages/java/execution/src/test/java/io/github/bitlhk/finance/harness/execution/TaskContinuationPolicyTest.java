package io.github.bitlhk.finance.harness.execution;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskContinuationPolicyTest {
    private static final Instant START = Instant.parse("2026-09-21T00:00:00Z");
    private static final String BINDING = "a".repeat(64);

    @Test void confirmationBoundariesAreExact() {
        TaskConfirmationPolicy.validate("event", BINDING, START, START.plusSeconds(300));
        assertTrue(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), BINDING, START));
        assertTrue(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), BINDING, START.plusSeconds(299)));
        assertFalse(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), BINDING, START.plusSeconds(300)));
        assertFalse(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), BINDING, START.minusNanos(1)));
        assertFalse(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), "b".repeat(64), START));
        assertFalse(TaskConfirmationPolicy.validFor(BINDING, START, START.plusSeconds(300), null, START));
    }

    @Test void malformedEventsAreRejected() {
        for (String event : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> TaskConfirmationPolicy.validate(event, BINDING, START, START.plusSeconds(1)));
        }
        for (String binding : new String[]{null, "", "A".repeat(64), "a".repeat(63)}) {
            assertThrows(IllegalArgumentException.class, () -> TaskConfirmationPolicy.validate("event", binding, START, START.plusSeconds(1)));
        }
        for (Instant end : new Instant[]{null, START, START.minusSeconds(1), START.plusSeconds(300).plusNanos(1)}) {
            assertThrows(IllegalArgumentException.class, () -> TaskConfirmationPolicy.validate("event", BINDING, START, end));
        }
        assertThrows(IllegalArgumentException.class, () -> TaskConfirmationPolicy.validate("event", BINDING, null, START));
    }

    @Test void savedOwnerAndCapabilityBothMustMatch() {
        var checkpoint = Map.<String, Object>of(CK_OWNER, "trusted-owner", CK_CAPABILITY, "cap");
        assertTrue(matchesOwner(Map.of(), "cap", "trusted-owner"));
        assertTrue(matchesOwner(checkpoint, "cap", "trusted-owner"));
        assertFalse(matchesOwner(checkpoint, "other", "trusted-owner"));
        assertFalse(matchesOwner(checkpoint, "cap", "other"));
        assertFalse(matchesOwner(Map.of(CK_CAPABILITY, "cap"), "cap", "trusted-owner"));
    }

    @Test void frozenParametersCannotBeChangedOrPartiallyReplaced() {
        var checkpoint = Map.<String, Object>of(CK_PARAMETERS, Map.of("amount", 10, "account", "a"));
        assertTrue(acceptsParameters(checkpoint, Map.of()));
        assertTrue(acceptsParameters(checkpoint, Map.of("account", "a", "amount", 10.0)));
        assertFalse(acceptsParameters(checkpoint, Map.of("amount", 11, "account", "a")));
        assertFalse(acceptsParameters(checkpoint, Map.of("amount", 10)));
        assertFalse(acceptsParameters(checkpoint, Map.of("amount", 10, "account", "a", "extra", true)));
    }

    @Test void supplementationCannotCrossSubmittedOrUnknownBoundary() {
        var checkpoint = new java.util.LinkedHashMap<String, Object>();
        checkpoint.put(CK_PARAMETERS, Map.of("amount", 10));
        checkpoint.put(CK_INPUTS, List.of("account"));
        var supplied = Map.<String, Object>of("amount", 10, "account", "a");
        assertTrue(acceptsParameters(checkpoint, supplied));
        checkpoint.put(CK_CONTINUATION, Map.of("submitOutcomeUnknown", true));
        assertFalse(acceptsParameters(checkpoint, supplied));
        checkpoint.remove(CK_CONTINUATION);
        checkpoint.put(CK_TASK_REF, "bank-task");
        assertFalse(acceptsParameters(checkpoint, supplied));
    }
}
