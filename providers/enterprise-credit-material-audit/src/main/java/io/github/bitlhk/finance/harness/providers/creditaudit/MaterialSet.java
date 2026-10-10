package io.github.bitlhk.finance.harness.providers.creditaudit;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record MaterialSet(
        String schemaVersion,
        String materialSetId,
        String ownerRef,
        String principalId,
        String taskRef,
        String sessionRef,
        LocalDate trustedBusinessDate,
        List<Material> materials) {

    public MaterialSet {
        required(schemaVersion, "schemaVersion");
        required(materialSetId, "materialSetId");
        required(ownerRef, "ownerRef");
        required(principalId, "principalId");
        required(taskRef, "taskRef");
        required(sessionRef, "sessionRef");
        if (trustedBusinessDate == null) throw new IllegalArgumentException("trustedBusinessDate is required");
        materials = materials == null ? List.of() : List.copyOf(materials);
    }

    public record Material(
            String materialId,
            String category,
            String version,
            String sha256,
            String subject,
            Period period,
            String currency,
            String unit,
            String businessBasis,
            String declaredFormat,
            Map<String, Field> fields) {
        public Material {
            required(materialId, "materialId");
            required(category, "category");
            required(version, "version");
            required(sha256, "sha256");
            required(declaredFormat, "declaredFormat");
            fields = fields == null ? Map.of() : Map.copyOf(fields);
        }
    }

    public record Period(LocalDate start, LocalDate end, String label) { }

    public record Field(Object value, FieldStatus status, Locator locator) {
        public Field {
            status = status == null ? FieldStatus.MISSING : status;
        }
    }

    public enum FieldStatus { EXTRACTED, MISSING, EXTRACTION_FAILED, NEEDS_CONFIRMATION }

    public record Locator(String kind, String value) {
        public Locator {
            required(kind, "locator.kind");
            required(value, "locator.value");
        }
    }

    private static void required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
