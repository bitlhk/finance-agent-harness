package io.github.bitlhk.finance.harness.core.observation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Supplied by trusted integration code, never deserialized from an uploaded requirement asset. */
public record ObservationCapabilitiesV01(String schemaVersion, String collectorId, String version,
                                        List<Capability> capabilities) {
    public static final String VERSION = "finance-observation-capabilities.v0.1";
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
    public enum Verification { VERIFIED, NOT_CHECKED }
    public enum Availability { ENABLED, DISABLED, UNKNOWN, FAILED }
    public enum Authorization { AUTHORIZED, NOT_AUTHORIZED, NOT_REQUIRED, UNKNOWN }

    public ObservationCapabilitiesV01 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Observation capabilities version");
        ObservationRequirementsV01.token(collectorId); ObservationRequirementsV01.token(version);
        capabilities = List.copyOf(capabilities);
        var keys = new HashSet<String>();
        for (var capability : capabilities)
            if (!keys.add(capability.semanticId())) throw new IllegalArgumentException("Duplicate collector capability");
    }

    public record Capability(String semanticId, String semanticVersion,
                             ObservationRequirementsV01.Boundary boundary, Set<String> fields,
                             Support support, Support sdkSupport, Verification verification, List<String> evidenceRefs,
                             Availability availability, Authorization authorization) {
        public Capability(String semanticId, String semanticVersion, ObservationRequirementsV01.Boundary boundary,
                          Set<String> fields, Support support, Verification verification, List<String> evidenceRefs,
                          Availability availability, Authorization authorization) {
            this(semanticId, semanticVersion, boundary, fields, support, Support.UNKNOWN, verification,
                    evidenceRefs, availability, authorization);
        }
        public Capability {
            ObservationRequirementsV01.token(semanticId); ObservationRequirementsV01.token(semanticVersion);
            fields = java.util.Collections.unmodifiableSet(new java.util.TreeSet<>(fields));
            fields.forEach(ObservationRequirementsV01::token);
            evidenceRefs = List.copyOf(evidenceRefs); evidenceRefs.forEach(ObservationRequirementsV01::token);
            if (boundary == null || support == null || sdkSupport == null || verification == null || availability == null || authorization == null)
                throw new IllegalArgumentException("Incomplete collector capability");
            if (verification == Verification.VERIFIED && (support != Support.SUPPORTED || evidenceRefs.isEmpty()))
                throw new IllegalArgumentException("Verified collection needs support and test evidence references");
        }
    }

    /** Digest of the declaration, not proof of remote behavior or current environment availability. */
    public String identityDigest() {
        var declarations = capabilities.stream().sorted(java.util.Comparator.comparing(Capability::semanticId))
                .map(cap -> java.util.Map.of("semanticId", cap.semanticId(), "semanticVersion", cap.semanticVersion(),
                        "boundary", cap.boundary(), "fields", cap.fields(), "support", cap.support(),
                        "sdkSupport", cap.sdkSupport(), "verification", cap.verification(),
                        "evidenceRefs", cap.evidenceRefs().stream().sorted().toList())).toList();
        try {
            byte[] bytes = io.github.bitlhk.finance.harness.core.HarnessJson.mapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(java.util.Map.of("schemaVersion", schemaVersion, "collectorId", collectorId,
                            "version", version, "capabilities", declarations));
            return "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Collector declaration digest unavailable", impossible);
        }
    }
}
