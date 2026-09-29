package io.github.bitlhk.finance.harness.execution;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskCheckpointPolicyTest {
    @Test void checkpointKeysRetainTheirExistingWireNames() {
        assertEquals(List.of("financeHarness.taskRef", "financeHarness.status", "financeHarness.capabilityId",
                        "financeHarness.idempotencyKey", "financeHarness.continuation", "financeHarness.parameters",
                        "financeHarness.confirmationBinding", "financeHarness.owner", "financeHarness.collectableInputs"),
                List.of(CK_TASK_REF, CK_STATUS, CK_CAPABILITY, CK_IDEMPOTENCY, CK_CONTINUATION,
                        CK_PARAMETERS, CK_BINDING, CK_OWNER, CK_INPUTS));
    }

    @Test void fingerprintMatchesPreExtractionBridgeGolden() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("values", Arrays.asList(new BigDecimal("1.00"), null, "text"));
        value.put("nested", Map.of("b", 2L));
        value.put("a", 1.0);
        assertEquals("3dd4a296b5bc65e3b7dc0d9a69a7754854bd2981c93707d006b4a1578d57774a", fingerprint(value));
    }

    @Test void fingerprintNormalizesMapOrderAndEquivalentNumbersButNotListOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("amount", new BigDecimal("10.00"));
        first.put("nested", Map.of("x", List.of(1, 2L)));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("nested", Map.of("x", List.of(1.0, new BigDecimal("2.00"))));
        second.put("amount", 10);
        assertEquals(fingerprint(first), fingerprint(second));
        assertNotEquals(fingerprint(List.of(1, 2)), fingerprint(List.of(2, 1)));
        assertNotEquals(fingerprint(1), fingerprint("1"));
        assertNotEquals(fingerprint(null), fingerprint(Map.of()));
    }

    @Test void fingerprintRetainsPrecisionAndExistingNonfiniteFailure() {
        assertEquals(fingerprint(9007199254740993L), fingerprint(new BigDecimal("9007199254740993.00")));
        assertNotEquals(fingerprint(9007199254740993L), fingerprint(9007199254740992L));
        var error = assertThrows(IllegalArgumentException.class, () -> fingerprint(Double.NaN));
        assertEquals("Cannot fingerprint task binding", error.getMessage());
        assertInstanceOf(NumberFormatException.class, error.getCause());
    }

    @Test void nullAndEmptyFreezeRemainEmpty() {
        assertEquals(Map.of(), freeze(null));
        assertEquals(Map.of(), freeze(Map.of()));
    }

    @SuppressWarnings("unchecked")
    @Test void nestedMapListFreezeCopiesOmitsNullsAndPreservesNumericScale() {
        Map<Object, Object> nested = new LinkedHashMap<>();
        nested.put(7, new BigDecimal("1.00"));
        nested.put(null, "omit");
        nested.put("null-value", null);
        List<Object> values = new ArrayList<>(Arrays.asList(nested, null, "keep"));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("values", values);source.put(null, "omit");source.put("null-value", null);
        var frozen = freeze(source);
        assertEquals(Map.of("values", List.of(Map.of("7", new BigDecimal("1.00")), "keep")), frozen);
        nested.put(7, 9);values.clear();source.clear();
        List<Object> copied = (List<Object>) frozen.get("values");
        Map<String, Object> copiedMap = (Map<String, Object>) copied.getFirst();
        assertEquals(new BigDecimal("1.00"), copiedMap.get("7"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("new", 1));
        assertThrows(UnsupportedOperationException.class, () -> copied.add("new"));
        assertThrows(UnsupportedOperationException.class, () -> copiedMap.put("new", 1));
    }

    @Test void freezePreservesLegacyNonMapNonListPassThrough() {
        Object opaque = new Object();
        byte[] bytes = {1, 2};
        var set = new LinkedHashSet<>(List.of("existing"));
        var frozen = freeze(Map.of("opaque", opaque, "bytes", bytes, "set", set));
        assertSame(opaque, frozen.get("opaque"));
        assertSame(bytes, frozen.get("bytes"));
        assertSame(set, frozen.get("set"));
    }

    @Test void recoveryOnlyRemovesOldConfirmationAndCollectionAuthority() {
        var parameters = new LinkedHashMap<String, Object>(Map.of("amount", 10));
        var prior = new LinkedHashMap<String, Object>();
        prior.put(CK_PARAMETERS, parameters);prior.put(CK_BINDING, "approval");
        prior.put(CK_INPUTS, List.of("recipient"));prior.put(CK_OWNER, "owner");
        prior.put(CK_TASK_REF, "task");prior.put(CK_IDEMPOTENCY, "idempotency");
        prior.put(CK_CAPABILITY, "transfer");prior.put(CK_STATUS, "UNKNOWN");
        prior.put(CK_CONTINUATION, Map.of("submitOutcomeUnknown", true));
        prior.put("financeHarness.attempted", true);
        var recovered = recoveryCheckpoint(prior);
        var expected = new LinkedHashMap<>(prior);expected.remove(CK_BINDING);expected.remove(CK_INPUTS);
        assertEquals(expected, recovered);
        assertSame(parameters, recovered.get(CK_PARAMETERS));
        assertTrue(prior.containsKey(CK_BINDING));assertTrue(prior.containsKey(CK_INPUTS));
        assertThrows(UnsupportedOperationException.class, () -> recovered.put(CK_BINDING, "replay"));
    }

    @Test void declaredMissingFieldMayBeAddedWithoutChangingFrozenValue() {
        var checkpoint = collection(Map.of("amount", 10), "recipient");
        assertTrue(canSupplement(checkpoint, Map.of("amount", new BigDecimal("10.00"), "recipient", "new")));
        assertTrue(canSupplement(checkpoint, Map.of("amount", 10)));
    }

    @Test void declaredBlankAndNullFieldsMayBeFilled() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("amount", 10);parameters.put("recipient", " \t");parameters.put("memo", null);
        assertTrue(canSupplement(collection(parameters, "recipient", "memo"),
                Map.of("amount", 10, "recipient", "new", "memo", "filled")));
        assertFalse(canSupplement(collection(parameters, "recipient"),
                Map.of("amount", 10, "recipient", "new", "memo", "filled")));
    }

    @Test void changedValuesRemovedKeysAndUndeclaredFieldsAreRejected() {
        var checkpoint = collection(Map.of("amount", 10, "recipient", "original"), "amount", "recipient", "memo");
        assertFalse(canSupplement(checkpoint, Map.of("amount", 20, "recipient", "original")));
        assertFalse(canSupplement(checkpoint, Map.of("amount", 10, "recipient", "replacement")));
        assertFalse(canSupplement(checkpoint, Map.of("amount", 10)));
        assertFalse(canSupplement(checkpoint, Map.of("amount", 10, "recipient", "original", "other", 1)));
    }

    @Test void unknownSubmissionAndExistingTaskNeverPermitSupplementation() {
        var checkpoint = new LinkedHashMap<>(collection(Map.of("amount", 10), "recipient"));
        var supplied = Map.<String, Object>of("amount", 10, "recipient", "new");
        checkpoint.put(CK_STATUS, "UNKNOWN");
        checkpoint.put(CK_CONTINUATION, Map.of("submitOutcomeUnknown", true));
        assertFalse(canSupplement(checkpoint, supplied));
        checkpoint.remove(CK_CONTINUATION);checkpoint.put(CK_TASK_REF, "");
        assertFalse(canSupplement(checkpoint, supplied));
    }

    @Test void supplementationRequiresExplicitNonemptyCollectionAndOriginalParameters() {
        assertFalse(canSupplement(Map.of(CK_PARAMETERS, Map.of()), Map.of("recipient", "new")));
        assertFalse(canSupplement(collection(Map.of()), Map.of("recipient", "new")));
        assertFalse(canSupplement(Map.of(CK_INPUTS, List.of("recipient")), Map.of("recipient", "new")));
    }

    private static Map<String, Object> collection(Map<String, Object> parameters, String... fields) {
        return Map.of(CK_PARAMETERS, parameters, CK_INPUTS, List.of(fields));
    }
}
