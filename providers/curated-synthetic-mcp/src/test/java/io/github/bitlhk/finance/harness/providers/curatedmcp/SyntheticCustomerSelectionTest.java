package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticCustomerSelectionTest {
    private CuratedSourceConfig config(String version, String mode, String customer) throws Exception {
        String customerField = customer == null ? "" : "\"customerId\":\"" + customer + "\",";
        return HarnessJson.mapper().readValue("""
            {"schemaVersion":"%s","endpoint":"http://127.0.0.1:12345/mcp","contractVersion":"1.0.0",
             "toolSchemaPins":{"get_customer_profile":"%s"},
             "subjects":{"operator-subject":{%s"customerSelection":"%s",
               "resources":{"get_customer_profile":["C005","C006"]}}},
             "runtimePolicy":{"mode":"STATELESS_BOUNDED","sourceIdentitySha256":"%s"}}
            """.formatted(version, "a".repeat(64), customerField, mode, "b".repeat(64)), CuratedSourceConfig.class);
    }

    @Test void explicitSyntheticSelectionPreservesOriginalCustomerAndNeverDefaults() throws Exception {
        var config = config("curated-synthetic-source.v3", "SYNTHETIC_ALLOWLIST", null);
        var policy = config.subjects().get("operator-subject");
        var tool = CuratedTools.named("get_customer_profile");
        for (String id : Set.of("C005", "C006"))
            assertEquals(Map.of("customer_id", id), CuratedTools.arguments(tool,
                    Map.of("customer_id", id), "operator-subject", policy));
        for (String id : Set.of("I001", "operator-subject", "C007"))
            assertThrows(SecurityException.class, () -> CuratedTools.arguments(tool,
                    Map.of("customer_id", id), "operator-subject", policy));
        assertThrows(IllegalArgumentException.class, () -> CuratedTools.arguments(tool, Map.of(), "operator-subject", policy));
        assertThrows(IllegalArgumentException.class, () -> CuratedTools.arguments(tool,
                Map.of("customer_id", "C005", "customerSelection", "SYNTHETIC_ALLOWLIST"), "operator-subject", policy));
    }

    @Test void legacyBoundSubjectStillRejectsDirectSourceIds() {
        var policy = new CuratedSourceConfig.Subject("C005", Map.of("get_customer_profile", Set.of("C005")));
        assertEquals(Map.of("customer_id", "C005"), CuratedTools.arguments(CuratedTools.named("get_customer_profile"),
                Map.of("customer_id", "host-subject"), "host-subject", policy));
        assertThrows(SecurityException.class, () -> CuratedTools.arguments(CuratedTools.named("get_customer_profile"),
                Map.of("customer_id", "C005"), "host-subject", policy));
    }

    @Test void selectionRequiresNewVersionAndCannotCoexistWithImplicitMapping() {
        assertThrows(Exception.class, () -> config("curated-synthetic-source.v2", "SYNTHETIC_ALLOWLIST", null));
        assertThrows(Exception.class, () -> config("curated-synthetic-source.v3", "SYNTHETIC_ALLOWLIST", "C005"));
        assertThrows(Exception.class, () -> config("curated-synthetic-source.v3", "AUTO", null));
        assertThrows(Exception.class, () -> config("curated-synthetic-source.v3", "BOUND_SUBJECT", null));
    }

    @Test void policyIsPinnedAndLegacyCanonicalBytesDoNotChange(@TempDir Path directory) throws Exception {
        var selected = config("curated-synthetic-source.v3", "SYNTHETIC_ALLOWLIST", null);
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(selected);
        Path file = directory.resolve("source.json");Files.write(file, bytes);
        assertEquals(selected, CuratedSourceConfig.load(file, CuratedSourceConfig.sha(bytes)));
        var single = new CuratedSourceConfig("curated-synthetic-source.v3", selected.endpoint(), "1.0.0", selected.toolSchemaPins(),
                Map.of("operator-subject", new CuratedSourceConfig.Subject(null,
                        Map.of("get_customer_profile", Set.of("C005")), "SYNTHETIC_ALLOWLIST")), selected.runtimePolicy());
        assertNotEquals(selected.canonicalDigest(), single.canonicalDigest());
        Files.write(file, HarnessJson.mapper().writeValueAsBytes(single));
        assertThrows(IllegalArgumentException.class, () -> CuratedSourceConfig.load(file, CuratedSourceConfig.sha(bytes)));
        var legacyJson = HarnessJson.mapper().valueToTree(selected);
        ((com.fasterxml.jackson.databind.node.ObjectNode) legacyJson).put("schemaVersion", "curated-synthetic-source.v2");
        var policy = (com.fasterxml.jackson.databind.node.ObjectNode) legacyJson.at("/subjects/operator-subject");
        policy.remove("customerSelection");policy.put("customerId", "C005");
        ((com.fasterxml.jackson.databind.node.ObjectNode) policy.get("resources")).putArray("get_customer_profile").add("C005");
        var legacy = HarnessJson.mapper().treeToValue(legacyJson, CuratedSourceConfig.class);
        assertEquals(CuratedTools.schemaPin(HarnessJson.mapper().convertValue(legacyJson, Map.class)), legacy.canonicalDigest());
        policy.put("customerSelection", "BOUND_SUBJECT");
        assertEquals(legacy.canonicalDigest(), HarnessJson.mapper().treeToValue(legacyJson, CuratedSourceConfig.class).canonicalDigest());
    }
}
