package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Safe scalar extraction shared by trusted display projections. */
final class DisplayFields {
    private DisplayFields() { }
    static String decimalText(JsonNode value) {
        if (value.isTextual()) return value.asText();
        if (!value.isNumber()) return "";
        try {
            var decimal = value.decimalValue();
            // Jackson can normalize 50000.00 to 5E+4. Keep exact decimals, not floating-point formatting.
            // Bound expansion of an untrusted exponent before creating a display string.
            if (decimal.precision() > 128 || decimal.scale() < -128 || decimal.scale() > 128) return "";
            return decimal.toPlainString();
        } catch (ArithmeticException | NumberFormatException invalid) {
            return "";
        }
    }
    static Map<String, Object> pick(JsonNode value, String... keys) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : keys) if (value.path(key).isTextual()) result.put(key, value.path(key).asText());
        return result;
    }
    static List<String> strings(JsonNode value) {
        var result = new ArrayList<String>(); value.forEach(v -> { if (v.isTextual()) result.add(v.asText()); }); return result;
    }
}
