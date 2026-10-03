package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.bitlhk.finance.harness.assembly.LocalSchemaPolicy;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.Map;
/** Uses the MCP-compatible 2.x validator without changing the local-only schema policy. */
public final class QueryInputValidator {
    private final Schema schema;
    public QueryInputValidator(Map<String, Object> definition) {
        var node = HarnessJson.mapper().valueToTree(definition);
        LocalSchemaPolicy.requireLocalReferences(node);
        schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder ->
                builder.resourceLoaders(loaders -> loaders.values(values -> {
                    values.clear(); values.add(iri -> { throw new IllegalArgumentException("SCHEMA_RESOURCE_LOADING_DISABLED"); });
                }))).getSchema(node);
    }
    public void validate(Map<String, Object> arguments) {
        if (arguments == null || !schema.validate(HarnessJson.mapper().valueToTree(arguments)).isEmpty())
            throw new IllegalArgumentException("CAPABILITY_ARGUMENTS_INVALID");
    }
}
