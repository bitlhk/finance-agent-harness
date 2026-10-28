package io.github.bitlhk.finance.harness.providers.creditaudit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.security.MessageDigest;

/** Locked DEMO rules. A successful check is never represented as bank policy approval. */
public record DemoRuleSet(String schemaVersion, String ruleSetId, String version, String classification,
                          String lifecycle, LocalDate effectiveFrom, LocalDate effectiveTo,
                          int amountScale, String roundingMode, List<String> capabilities,
                          List<Clause> clauses) {
    public DemoRuleSet {
        if (!"finance-audit-demo-rules.v0.1".equals(schemaVersion) || !"DEMO".equals(classification)
                || ruleSetId == null || ruleSetId.isBlank() || version == null || version.isBlank()
                || effectiveFrom == null || (effectiveTo != null && effectiveTo.isBefore(effectiveFrom))
                || capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("INVALID_DEMO_RULE_SET");
        }
        capabilities = List.copyOf(capabilities);
        // Older frozen packages have no clauses. Do not invent a policy citation for them.
        if (clauses != null) {
            clauses = List.copyOf(clauses);
            var ids = new HashSet<String>();
            var covered = new HashSet<String>();
            for (Clause clause : clauses) {
                if (!ids.add(clause.id()) || !capabilities.containsAll(clause.capabilities())) {
                    throw new IllegalArgumentException("INVALID_RULE_CLAUSES");
                }
                covered.addAll(clause.capabilities());
            }
            if (!covered.containsAll(capabilities)) throw new IllegalArgumentException("RULE_CLAUSES_INCOMPLETE");
        }
    }

    public DemoRuleSet(String schemaVersion, String ruleSetId, String version, String classification,
                       String lifecycle, LocalDate effectiveFrom, LocalDate effectiveTo,
                       int amountScale, String roundingMode, List<String> capabilities) {
        this(schemaVersion, ruleSetId, version, classification, lifecycle, effectiveFrom, effectiveTo,
                amountScale, roundingMode, capabilities, null);
    }

    public record Clause(String id, String title, String text, List<String> capabilities) {
        public Clause {
            if (id == null || !id.matches("[A-Za-z0-9.-]{1,80}") || title == null || title.isBlank()
                    || text == null || text.isBlank() || text.length() > 4000
                    || capabilities == null || capabilities.isEmpty()) {
                throw new IllegalArgumentException("INVALID_RULE_CLAUSE");
            }
            capabilities = List.copyOf(capabilities);
        }
    }

    /** Frozen reference data; the digest identifies content, not institutional approval. */
    public Map<String, Object> evidence(String capability) {
        if (!capabilities.contains(capability)) throw new IllegalArgumentException("RULE_CAPABILITY_UNSUPPORTED");
        if (clauses == null) return Map.of("status", "NOT_PROVIDED");
        try {
            String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(HarnessJson.mapper().writeValueAsBytes(this)));
            var result = new java.util.LinkedHashMap<String, Object>();
            result.put("schemaVersion", "finance-audit-rule-evidence.v1");
            result.put("status", "AVAILABLE");
            result.put("documentId", ruleSetId);
            result.put("title", "材料核验说明（演示，非银行制度）");
            result.put("version", version);
            result.put("classification", classification);
            result.put("effectiveFrom", effectiveFrom.toString());
            if (effectiveTo != null) result.put("effectiveTo", effectiveTo.toString());
            result.put("contentDigest", digest);
            result.put("clauses", clauses.stream().filter(c -> c.capabilities().contains(capability))
                    .map(c -> Map.of("id", c.id(), "title", c.title(), "text", c.text())).toList());
            return Map.copyOf(result);
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("RULE_EVIDENCE_UNAVAILABLE", error);
        }
    }
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
