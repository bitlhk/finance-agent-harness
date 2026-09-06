package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Declarative financial capabilities that an integration installs into an Agent Runtime. */
public record FinanceCapabilityPackV1(
        String schemaVersion,
        String packId,
        String version,
        String systemPrompt,
        List<Capability> capabilities) {
    public static final String SCHEMA_VERSION = "finance-capability-pack.v1";

    private static final Pattern PACK_ID = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");
    private static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    public FinanceCapabilityPackV1 {
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported capability pack schemaVersion: " + schemaVersion);
        }
        packId = matching(packId, "packId", PACK_ID);
        version = required(version, "version");
        systemPrompt = required(systemPrompt, "systemPrompt");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        if (capabilities.isEmpty()) {
            throw new IllegalArgumentException("capabilities must not be empty");
        }
        Set<String> ids = new LinkedHashSet<>();
        Set<String> toolNames = new LinkedHashSet<>();
        for (Capability capability : capabilities) {
            Objects.requireNonNull(capability, "capability");
            if (!ids.add(capability.capabilityId())) {
                throw new IllegalArgumentException("Duplicate capabilityId: " + capability.capabilityId());
            }
            if (!toolNames.add(capability.toolName())) {
                throw new IllegalArgumentException("Duplicate toolName: " + capability.toolName());
            }
        }
    }

    public enum Operation {
        QUERY,
        TASK
    }

    public record Capability(
            String capabilityId,
            String toolName,
            String name,
            String description,
            Operation operation,
            Map<String, Object> inputSchema,
            Set<String> requiredScopes) {
        public Capability {
            capabilityId = matching(capabilityId, "capabilityId", CAPABILITY_ID);
            toolName = matching(toolName, "toolName", TOOL_NAME);
            name = required(name, "name");
            description = required(description, "description");
            operation = Objects.requireNonNull(operation, "operation");
            inputSchema = immutableMap(inputSchema);
            if (!"object".equals(inputSchema.get("type"))) {
                throw new IllegalArgumentException("inputSchema.type must be object for " + capabilityId);
            }
            requiredScopes = requiredScopes == null
                    ? Set.of()
                    : Set.copyOf(new LinkedHashSet<>(requiredScopes));
        }
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

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> target = new LinkedHashMap<>();
        source.forEach((key, value) -> target.put(key, immutable(value)));
        return Map.copyOf(target);
    }

    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> target = new LinkedHashMap<>();
            map.forEach((key, nested) -> target.put(String.valueOf(key), immutable(nested)));
            return Map.copyOf(target);
        }
        if (value instanceof List<?> list) {
            List<Object> target = new ArrayList<>();
            list.forEach(item -> target.add(immutable(item)));
            return List.copyOf(target);
        }
        return value;
    }
}
