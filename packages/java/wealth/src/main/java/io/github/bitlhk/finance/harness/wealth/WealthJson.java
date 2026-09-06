package io.github.bitlhk.finance.harness.wealth;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;

/** Candidate schema reader: no number-to-string coercion and no explicit JSON null fields. */
public final class WealthJson {
    private WealthJson() { }
    public static ObjectMapper mapper() {
        ObjectMapper json = HarnessJson.mapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        json.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return json;
    }
    public static WealthFactsV1 read(String input) throws IOException {
        var json = mapper(); var tree = json.readTree(input); noNull(tree);
        return json.treeToValue(tree, WealthFactsV1.class);
    }
    private static void noNull(JsonNode node) {
        if (node == null || node.isNull()) throw new IllegalArgumentException("Omit unavailable optional fields; explicit null is not supported");
        if (node.isContainerNode()) node.forEach(WealthJson::noNull);
    }
}
