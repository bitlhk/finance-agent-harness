package io.github.bitlhk.finance.harness.quotation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.cfg.*;
import com.fasterxml.jackson.databind.type.LogicalType;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.charset.StandardCharsets;

/** Strict local copy; does not change Core/Wealth JSON or null semantics. */
public final class QuotationJson {
    private QuotationJson() { }
    public static ObjectMapper mapper() {
        ObjectMapper json = HarnessJson.mapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        json.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        for (CoercionInputShape shape : new CoercionInputShape[] {
                CoercionInputShape.Integer, CoercionInputShape.Float, CoercionInputShape.Boolean })
            json.coercionConfigFor(LogicalType.Textual).setCoercion(shape, CoercionAction.Fail);
        for (CoercionInputShape shape : new CoercionInputShape[] {
                CoercionInputShape.String, CoercionInputShape.Float, CoercionInputShape.Boolean })
            json.coercionConfigFor(LogicalType.Integer).setCoercion(shape, CoercionAction.Fail);
        return json;
    }
    public static QuoteFactsV01 read(String input) {
        try {
            if (input == null || input.getBytes(StandardCharsets.UTF_8).length > 262144)
                throw new IllegalArgumentException();
            return mapper().readValue(input, QuoteFactsV01.class);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("STRUCTURE_INVALID");
        }
    }
}
