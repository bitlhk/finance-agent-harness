package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.LinkedHashMap;
import java.util.Map;

/** A deterministic assessment of a submitted artifact; it never asserts document authenticity. */
public record AuditEvidenceAssessment(
        String sourceCategory,
        String locator,
        AuditEvidenceStatus status,
        String reason) {
    public AuditEvidenceAssessment {
        sourceCategory = required(sourceCategory, "sourceCategory");
        locator = required(locator, "locator");
        if (status == null) throw new IllegalArgumentException("status is required");
        reason = required(reason, "reason");
    }

    public Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceCategory", sourceCategory);
        result.put("locator", locator);
        result.put("status", status.name());
        result.put("reason", reason);
        return Map.copyOf(result);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
