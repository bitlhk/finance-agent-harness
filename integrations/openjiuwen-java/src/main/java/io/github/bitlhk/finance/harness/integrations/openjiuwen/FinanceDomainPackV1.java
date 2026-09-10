package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Runtime-neutral description of a financial domain pack consumed by an integration. */
public record FinanceDomainPackV1(
        String schemaVersion,
        String packId,
        String version,
        String profile,
        List<String> capabilities,
        List<AssetRef> capabilityPackRefs,
        List<Skill> skills,
        List<String> scope,
        List<String> outOfScope,
        List<String> sourceReferences) {
    public static final String SCHEMA_VERSION = "finance-domain-pack.v1";

    private static final Pattern PACK_ID = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    public FinanceDomainPackV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported domain pack schemaVersion: " + schemaVersion);
        }
        packId = matching(packId, "packId", PACK_ID);
        version = required(version, "version");
        profile = required(profile, "profile");
        capabilities = immutableStrings(capabilities);
        capabilityPackRefs = capabilityPackRefs == null ? List.of() : List.copyOf(capabilityPackRefs);
        capabilityPackRefs.forEach(ref -> Objects.requireNonNull(ref, "capabilityPackRef"));
        skills = skills == null ? List.of() : List.copyOf(skills);
        scope = immutableStrings(scope);
        outOfScope = immutableStrings(outOfScope);
        sourceReferences = immutableStrings(sourceReferences);
        skills.forEach(skill -> Objects.requireNonNull(skill, "skill"));
    }

    public record AssetRef(String id, String version) {
        public AssetRef {
            id = matching(id, "capabilityPackRef.id", PACK_ID);
            version = required(version, "capabilityPackRef.version");
        }
    }

    public record Skill(String directory, String entry) {
        public Skill {
            directory = required(directory, "skill.directory");
            entry = required(entry, "skill.entry");
        }
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> required(value, "list item")).toList();
    }

    private static String matching(String value, String name, Pattern pattern) {
        String normalized = required(value, name);
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(name + " has invalid format: " + normalized);
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
