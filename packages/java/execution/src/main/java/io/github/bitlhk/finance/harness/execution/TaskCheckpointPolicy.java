package io.github.bitlhk.finance.harness.execution;

import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Checkpoint value rules only; does not execute tasks, authorize calls, or persist state. */
public final class TaskCheckpointPolicy {
    public static final String CK_TASK_REF = "financeHarness.taskRef";
    public static final String CK_STATUS = "financeHarness.status";
    public static final String CK_CAPABILITY = "financeHarness.capabilityId";
    public static final String CK_IDEMPOTENCY = "financeHarness.idempotencyKey";
    public static final String CK_CONTINUATION = "financeHarness.continuation";
    public static final String CK_PARAMETERS = "financeHarness.parameters";
    public static final String CK_BINDING = "financeHarness.confirmationBinding";
    public static final String CK_OWNER = "financeHarness.owner";
    public static final String CK_INPUTS = "financeHarness.collectableInputs";

    private TaskCheckpointPolicy() {
    }

    /** Retains the legacy Map/List copying and null omission; other values pass through. */
    public static Map<String, Object> freeze(Map<String, Object> values) {
        return sanitizeMap(values);
    }

    public static String fingerprint(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    HarnessJson.mapper().writeValueAsBytes(canonical(value))));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot fingerprint task binding", exception);
        }
    }

    /** Recovery discards old confirmation and collection authority, not task identity or parameters. */
    public static Map<String, Object> recoveryCheckpoint(Map<String, Object> checkpoint) {
        Map<String, Object> prior = new LinkedHashMap<>(checkpoint);
        prior.remove(CK_BINDING);
        prior.remove(CK_INPUTS);
        return Map.copyOf(prior);
    }

    public static boolean canSupplement(Map<String, Object> checkpoint, Map<String, Object> merged) {
        if (!(checkpoint.get(CK_INPUTS) instanceof List<?> fields) || fields.isEmpty()
                || checkpoint.containsKey(CK_TASK_REF)
                || Boolean.TRUE.equals(continuation(checkpoint).get("submitOutcomeUnknown"))) return false;
        Map<String, Object> original = parameters(checkpoint);
        if (original == null || !merged.keySet().containsAll(original.keySet())) return false;
        for (var field : merged.entrySet()) {
            if (original.containsKey(field.getKey())) {
                Object previous = original.get(field.getKey());
                boolean missingValue = previous == null || previous instanceof String s && s.isBlank();
                if (!fingerprint(previous).equals(fingerprint(field.getValue()))
                        && !(fields.contains(field.getKey()) && missingValue)) return false;
            } else if (!fields.contains(field.getKey())) return false;
        }
        return true;
    }

    /** Compares trusted host identity with the saved binding; does not grant access. */
    public static boolean matchesOwner(Map<String, Object> checkpoint, String capabilityId, String owner) {
        return checkpoint.isEmpty() || capabilityId.equals(checkpoint.get(CK_CAPABILITY))
                && owner.equals(checkpoint.get(CK_OWNER));
    }

    public static boolean acceptsParameters(Map<String, Object> checkpoint, Map<String, Object> supplied) {
        return !checkpoint.containsKey(CK_PARAMETERS) || supplied.isEmpty()
                || fingerprint(supplied).equals(fingerprint(checkpoint.get(CK_PARAMETERS)))
                || canSupplement(checkpoint, supplied);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Map<String, Object> checkpoint) {
        return (Map<String, Object>) checkpoint.get(CK_PARAMETERS);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> continuation(Map<String, Object> checkpoint) {
        Object value = checkpoint.get(CK_CONTINUATION);
        return value instanceof Map<?, ?> map
                ? sanitizeMap((Map<String, Object>) map)
                : Map.of();
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(TaskCheckpointPolicy::canonical).toList();
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Object clean = sanitize(value);
            if (key != null && clean != null) sanitized.put(key, clean);
        });
        return Map.copyOf(sanitized);
    }

    private static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                Object clean = sanitize(nested);
                if (key != null && clean != null) sanitized.put(String.valueOf(key), clean);
            });
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                Object clean = sanitize(item);
                if (clean != null) sanitized.add(clean);
            }
            return List.copyOf(sanitized);
        }
        return value;
    }
}
