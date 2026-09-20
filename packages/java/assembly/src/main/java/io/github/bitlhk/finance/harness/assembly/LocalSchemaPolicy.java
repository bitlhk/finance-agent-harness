package io.github.bitlhk.finance.harness.assembly;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict local schema validation; schemas cannot fetch remote resources. */
public final class LocalSchemaPolicy {
    private LocalSchemaPolicy() {}
    public static void requireLocalReferences(JsonNode node) {
        if (node.isObject()) {
            // V0.1 accepts inline schemas and local JSON Pointer refs, not URI-scoped schemas.
            if (node.has("$id") || node.has("$dynamicRef") || node.has("$recursiveRef"))
                throw new IllegalArgumentException("SCHEMA_URI_SCOPE_UNSUPPORTED");
            for (String field : new String[]{"$ref", "$dynamicRef", "$recursiveRef"}) {
                if (node.has(field) && (!node.path(field).isTextual() || !node.path(field).asText().startsWith("#/")))
                    throw new IllegalArgumentException("REMOTE_SCHEMA_REFERENCE_UNSUPPORTED");
            }
            if (node.has("$schema") && !"https://json-schema.org/draft/2020-12/schema".equals(node.path("$schema").asText()))
                throw new IllegalArgumentException("SCHEMA_DIALECT_UNSUPPORTED");
        }
        if (node.isContainerNode()) node.forEach(LocalSchemaPolicy::requireLocalReferences);
    }
}
