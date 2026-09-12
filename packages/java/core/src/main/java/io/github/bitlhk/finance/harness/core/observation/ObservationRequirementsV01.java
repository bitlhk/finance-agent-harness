package io.github.bitlhk.finance.harness.core.observation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Candidate requirements only. This asset cannot enable capture or authorize an owner. */
public record ObservationRequirementsV01(String schemaVersion, String id, String version,
                                        List<Requirement> requirements) {
    public static final String VERSION = "finance-observation-requirements.v0.1";
    public enum Obligation { REQUIRED, OPTIONAL, AUTHORIZED_DIAGNOSTIC }
    public enum Boundary { HOST, INTEGRATOR, PROVIDER, MODEL_CLIENT, MODEL_HTTP }
    public enum Content { METADATA, DIAGNOSTIC_BODY }
    public record Reference(String id, String version, String source, String digest) {
        public Reference {
            token(id); token(version);
            if (source == null || !source.startsWith("repo://") || digest == null
                    || !digest.matches("sha256:[a-f0-9]{64}"))
                throw new IllegalArgumentException("Explicit local observation source and digest required");
        }
    }

    public ObservationRequirementsV01 {
        if (!VERSION.equals(schemaVersion)) throw new IllegalArgumentException("Observation requirements version");
        token(id); token(version);
        requirements = List.copyOf(requirements);
        if (requirements.isEmpty()) throw new IllegalArgumentException("Empty observation requirements");
        var keys = new HashSet<String>();
        for (var requirement : requirements)
            if (!keys.add(requirement.semanticId())) throw new IllegalArgumentException("Duplicate observation requirement");
    }

    public record Requirement(String semanticId, String semanticVersion, Obligation obligation,
                              Boundary boundary, Content content, Set<String> fields, Set<String> capabilityIds) {
        public Requirement {
            token(semanticId); token(semanticVersion);
            if (obligation == null || boundary == null || content == null)
                throw new IllegalArgumentException("Observation requirement kind");
            fields = Set.copyOf(fields); capabilityIds = Set.copyOf(capabilityIds);
            if (fields.isEmpty()) throw new IllegalArgumentException("Observation fields required");
            fields.forEach(ObservationRequirementsV01::token);
            capabilityIds.forEach(ObservationRequirementsV01::token);
        }
    }

    static void token(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,159}"))
            throw new IllegalArgumentException("Invalid observation identifier");
    }
}
