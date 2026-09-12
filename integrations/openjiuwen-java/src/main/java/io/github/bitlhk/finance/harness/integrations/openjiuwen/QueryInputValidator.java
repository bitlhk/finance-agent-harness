package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.Map;

/** Strict local schema validation; schemas cannot fetch remote resources. */
public final class QueryInputValidator {
    private final JsonSchema schema;

    public QueryInputValidator(Map<String, Object> definition) {
        JsonNode node = HarnessJson.mapper().valueToTree(definition);
        requireLocalReferences(node);
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaLoaders(loaders -> loaders.values(values -> {
                    values.clear();
                    values.add(iri -> { throw new IllegalArgumentException("SCHEMA_RESOURCE_LOADING_DISABLED"); });
                }))).getSchema(node);
    }

    private static void requireLocalReferences(JsonNode node) {
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
        if (node.isContainerNode()) node.forEach(QueryInputValidator::requireLocalReferences);
    }

    public void validate(Map<String, Object> arguments) {
        JsonNode value = HarnessJson.mapper().valueToTree(arguments);
        if (arguments == null || !schema.validate(value).isEmpty())
            throw new IllegalArgumentException("CAPABILITY_ARGUMENTS_INVALID");
    }
}
