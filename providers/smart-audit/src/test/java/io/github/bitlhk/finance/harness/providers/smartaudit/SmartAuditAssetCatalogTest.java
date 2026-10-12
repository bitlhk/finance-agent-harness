package io.github.bitlhk.finance.harness.providers.smartaudit;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SmartAuditAssetCatalogTest {
    private static final Path CATALOG = Path.of("..", "..", "catalog", "finance-assets.v1.json");

    @Test
    void registers_the_two_isolated_internal_audit_scenarios_and_provider() throws IOException {
        JsonNode catalog = new ObjectMapper().readTree(CATALOG.toFile());

        assertTrue(ids(catalog.path("domainPacks")).containsAll(Set.of(
                "smart-audit-personal-loan-audit", "smart-audit-enterprise-credit-audit")));
        assertTrue(ids(catalog.path("capabilityPacks")).containsAll(Set.of(
                "smart-audit-personal-loan-audit", "smart-audit-enterprise-credit-audit")));
        assertTrue(ids(catalog.path("providers")).contains("smart-audit-model-provider"));
    }

    private static Set<String> ids(JsonNode assets) {
        Set<String> ids = new HashSet<>();
        for (JsonNode asset : assets) ids.add(asset.path("id").asText());
        return ids;
    }
}
