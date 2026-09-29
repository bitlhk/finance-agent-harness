package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.github.bitlhk.finance.harness.assembly.LocalSchemaPolicy;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.Map;
/** Keeps the existing networknt 1.x ABI while sharing the local-only schema policy. */
public final class QueryInputValidator {
    private final JsonSchema schema;
    public QueryInputValidator(Map<String, Object> definition) {
        var node = HarnessJson.mapper().valueToTree(definition);
        LocalSchemaPolicy.requireLocalReferences(node);
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder ->
                builder.schemaLoaders(loaders -> loaders.values(values -> {
                    values.clear(); values.add(iri -> { throw new IllegalArgumentException("SCHEMA_RESOURCE_LOADING_DISABLED"); });
                }))).getSchema(node);
    }
    public void validate(Map<String, Object> arguments) {
        if (arguments == null || !schema.validate(HarnessJson.mapper().valueToTree(arguments)).isEmpty())
            throw new IllegalArgumentException("CAPABILITY_ARGUMENTS_INVALID");
    }
}
