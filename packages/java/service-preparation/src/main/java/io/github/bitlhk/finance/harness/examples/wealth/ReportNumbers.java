package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Numeric membership only; source, temporal and product semantics are checked separately. */
final class ReportNumbers {
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.])(-?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)(亿|万)?(%|％)?");

    static void validate(String text, JsonNode facts, String goal) {
        Set<String> allowed = new HashSet<>();
        collect(facts, allowed);
        tokens(goal, allowed);
        Set<String> actual = new HashSet<>();
        // Markdown list ordinals are layout, not financial assertions.
        tokens(text.replaceAll("(?m)^\\s*[0-9]+[.)、]\\s+", ""), actual);
        if (!allowed.containsAll(actual)) throw new IllegalArgumentException("UNSUPPORTED_NUMBER");
    }

    private static void collect(JsonNode node, Set<String> values) {
        if (node.isNumber()) values.add(key(node.decimalValue(), false));
        else if (node.isTextual()) tokens(node.asText(), values);
        else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, values));
            // A percent is allowed only when explicitly present as a percentage,
            // not because the same digits occur in an ID, date or other amount.
            if (node.isObject() && "SAME_SNAPSHOT_RATIO".equals(node.path("kind").asText())
                    && node.path("percent").isNumber())
                values.add(key(node.path("percent").decimalValue(), true));
        }
    }

    private static void tokens(String text, Set<String> values) {
        if (text == null) return;
        var matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
            if (matcher.group(2) != null)
                value = value.multiply(new BigDecimal("亿".equals(matcher.group(2)) ? "100000000" : "10000"));
            values.add(key(value, matcher.group(3) != null));
        }
    }

    private static String key(BigDecimal value, boolean percent) {
        return (percent ? "percent:" : "number:") + value.stripTrailingZeros().toPlainString();
    }
}
