package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Runtime-facing invocation passed by an OpenJiuwen host adapter. */
public record OpenJiuwenInvocationV1(
        String taskId,
        String capabilityId,
        String ownerAgentId,
        Set<String> requiredScopes,
        Map<String, Object> arguments,
        Map<String, Object> checkpoint) {

    public OpenJiuwenInvocationV1 {
        taskId = required(taskId, "taskId");
        capabilityId = required(capabilityId, "capabilityId");
        ownerAgentId = ownerAgentId == null ? "" : ownerAgentId.trim();
        requiredScopes = requiredScopes == null
                ? Set.of()
                : Set.copyOf(new LinkedHashSet<>(requiredScopes));
        arguments = immutableMap(arguments);
        checkpoint = immutableMap(checkpoint);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) copy.put(key, value);
        });
        return Map.copyOf(copy);
    }
}
