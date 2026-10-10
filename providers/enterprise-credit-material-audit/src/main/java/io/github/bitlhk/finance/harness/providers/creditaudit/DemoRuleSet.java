package io.github.bitlhk.finance.harness.providers.creditaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Locked DEMO rules. A successful check is never represented as bank policy approval. */
public record DemoRuleSet(String schemaVersion, String ruleSetId, String version, String classification,
                          String lifecycle, LocalDate effectiveFrom, LocalDate effectiveTo,
                          int amountScale, String roundingMode, List<String> capabilities) {
    public static DemoRuleSet load(Path file) throws IOException {
        Path normalized = Objects.requireNonNull(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("RULE_SET_NOT_FOUND");
        }
        ObjectMapper json = HarnessJson.mapper();
        DemoRuleSet result = json.readValue(normalized.toFile(), DemoRuleSet.class);
        if (!"finance-audit-demo-rules.v0.1".equals(result.schemaVersion())
                || !"DEMO".equals(result.classification())) {
            throw new IllegalArgumentException("DEMO_RULE_SET_REQUIRED");
        }
        return result;
    }

    public boolean activeOn(LocalDate date) {
        return "ACTIVE".equals(lifecycle) && !date.isBefore(effectiveFrom)
                && (effectiveTo == null || !date.isAfter(effectiveTo));
    }
}
