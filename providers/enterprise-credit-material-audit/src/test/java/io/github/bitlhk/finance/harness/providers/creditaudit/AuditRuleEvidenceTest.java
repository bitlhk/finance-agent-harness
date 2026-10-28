package io.github.bitlhk.finance.harness.providers.creditaudit;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AuditRuleEvidenceTest {
    @TempDir Path root;
    private static final Path RULE = Path.of("../../domain-packs/credit/enterprise-material-review/rules/demo-enterprise-material-verification.v0.1.json");
    private static final String FIN = EnterpriseCreditMaterialAuditProvider.FINANCIAL;
    private static final String CROSS = EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL;

    @Test void shippedRulesBindApplicableClausesAndContentNotBankApproval() throws Exception {
        var rules = DemoRuleSet.load(RULE);
        var financial = rules.evidence(FIN);
        var cross = rules.evidence(CROSS);
        assertEquals("DEMO", financial.get("classification"));
        assertEquals("0.2.0", financial.get("version"));
        assertEquals(financial.get("contentDigest"), cross.get("contentDigest"));
        assertTrue(financial.get("contentDigest").toString().matches("sha256:[a-f0-9]{64}"));
        assertEquals(List.of("FIN-01", "LIMIT-01"), ids(financial));
        assertEquals(List.of("CROSS-01", "LIMIT-01"), ids(cross));
        assertThrows(IllegalArgumentException.class, () -> rules.evidence("uninstalled.tool"));
        assertTrue(rules.activeOn(LocalDate.of(2026, 10, 29)));
        assertFalse(rules.activeOn(LocalDate.of(2028, 1, 1)));
    }

    @Test void legacyHasNoFabricatedCitation() throws Exception {
        var json = HarnessJson.mapper().readTree(RULE.toFile()).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove("clauses");
        Path file = root.resolve("legacy.json");
        HarnessJson.mapper().writeValue(file.toFile(), json);
        assertEquals(Map.of("status", "NOT_PROVIDED"), DemoRuleSet.load(file).evidence(FIN));
    }

    @Test void malformedUnknownOrIncompleteRulesFailClosed() throws Exception {
        for (String mutation : List.of("schema", "classification", "empty", "duplicate", "scope", "blank")) {
            var json = (com.fasterxml.jackson.databind.node.ObjectNode) HarnessJson.mapper().readTree(RULE.toFile());
            var clauses = (com.fasterxml.jackson.databind.node.ArrayNode) json.get("clauses");
            switch (mutation) {
                case "schema" -> json.put("schemaVersion", "unknown");
                case "classification" -> json.put("classification", "BANK_APPROVED");
                case "empty" -> clauses.removeAll();
                case "duplicate" -> clauses.add(clauses.get(0).deepCopy());
                case "scope" -> ((com.fasterxml.jackson.databind.node.ObjectNode) clauses.get(0))
                        .putArray("capabilities").add("uninstalled.tool");
                case "blank" -> ((com.fasterxml.jackson.databind.node.ObjectNode) clauses.get(0)).put("text", "");
            }
            Path file = root.resolve(mutation + ".json");
            HarnessJson.mapper().writeValue(file.toFile(), json);
            assertThrows(Exception.class, () -> DemoRuleSet.load(file), mutation);
        }
    }

    @Test void savedEvidenceRemainsIndependentOfNewRuleVersion() throws Exception {
        var original = DemoRuleSet.load(RULE).evidence(FIN);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) HarnessJson.mapper().readTree(RULE.toFile());
        json.put("version", "0.3.0");
        ((com.fasterxml.jackson.databind.node.ObjectNode) json.get("clauses").get(0)).put("text", "替换后的演示说明");
        Path file = root.resolve("new.json");
        HarnessJson.mapper().writeValue(file.toFile(), json);
        var updated = DemoRuleSet.load(file).evidence(FIN);
        assertNotEquals(original.get("contentDigest"), updated.get("contentDigest"));
        assertEquals("0.2.0", original.get("version"));
        assertFalse(original.toString().contains("替换后的"));
        assertThrows(UnsupportedOperationException.class, () -> original.put("version", "changed"));
    }

    private static List<?> ids(Map<String, Object> evidence) {
        return ((List<Map<String, Object>>) evidence.get("clauses")).stream().map(c -> c.get("id")).toList();
    }
}
