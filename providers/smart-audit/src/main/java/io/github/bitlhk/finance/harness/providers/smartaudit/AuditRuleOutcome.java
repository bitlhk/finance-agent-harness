package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, advisory-only result of a single versioned audit rule. */
public record AuditRuleOutcome(
        String ruleId,
        String policyVersion,
        Status status,
        List<String> evidence,
        Map<String, Object> inputs,
        String reason) {
    public AuditRuleOutcome {
        ruleId = required(ruleId, "ruleId");
        policyVersion = required(policyVersion, "policyVersion");
        if (status == null) throw new IllegalArgumentException("status is required");
        evidence = evidence == null ? List.of() : freezeEvidence(evidence);
        inputs = inputs == null ? Map.of() : freezeMap(inputs, "inputs");
        reason = required(reason, "reason");
    }

    /** Explicitly exposes rule evidence and inputs without making an approval assertion. */
    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId);
        result.put("policyVersion", policyVersion);
        result.put("status", status.name());
        result.put("evidence", evidence);
        result.put("inputs", freezeMap(inputs, "inputs"));
        result.put("reason", reason);
        return Collections.unmodifiableMap(result);
    }

    public enum Status {
        MET,
        NOT_MET,
        NEEDS_SUPPLEMENT,
        CANNOT_DETERMINE
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static Map<String, Object> freezeMap(Map<?, ?> source, String path) {
        Map<String, Object> frozen = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                throw new IllegalArgumentException(path + " keys must be nonblank strings");
            }
            frozen.put(key, freezeValue(entry.getValue(), path + "." + key));
        }
        return Collections.unmodifiableMap(frozen);
    }

    private static List<String> freezeEvidence(List<?> source) {
        List<String> frozen = new ArrayList<>(source.size());
        for (Object reference : source) {
            if (!(reference instanceof String value) || value.isBlank()) {
                throw new IllegalArgumentException("evidence entries must be nonblank strings");
            }
            frozen.add(value.trim());
        }
        return List.copyOf(frozen);
    }

    private static List<Object> freezeList(List<?> source, String path) {
        List<Object> frozen = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            frozen.add(freezeValue(source.get(index), path + "[" + index + "]"));
        }
        return Collections.unmodifiableList(frozen);
    }

    private static Object freezeValue(Object value, String path) {
        if (value instanceof Double floating) {
            if (!Double.isFinite(floating)) throw new IllegalArgumentException(path + " must be finite");
            return floating;
        }
        if (value instanceof Float floating) {
            if (!Float.isFinite(floating)) throw new IllegalArgumentException(path + " must be finite");
            return floating;
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Map<?, ?> map) return freezeMap(map, path);
        if (value instanceof List<?> list) return freezeList(list, path);
        throw new IllegalArgumentException(path + " must contain JSON-like values");
    }
}
