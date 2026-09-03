package io.github.bitlhk.finance.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceAssetCatalogTest {
    private static final Path ROOT = findRepositoryRoot();
    private static final List<String> COLLECTIONS = List.of(
            "runtimeIntegrations",
            "profiles",
            "domainPacks",
            "capabilityPacks",
            "providers",
            "evalPacks");

    @Test
    void catalogHasUniqueResolvableAssetsForBuilderDiscovery() throws Exception {
        JsonNode catalog = new ObjectMapper().readTree(
                ROOT.resolve("catalog/finance-assets.v1.json").toFile());

        assertEquals("finance-asset-catalog.v1", catalog.path("schemaVersion").asText());
        for (String collection : COLLECTIONS) {
            JsonNode assets = catalog.path(collection);
            assertTrue(assets.isArray(), collection + " must be an array");
            assertFalse(assets.isEmpty(), collection + " must not be empty");
            Set<String> identities = new HashSet<>();
            for (JsonNode asset : assets) {
                String identity = asset.path("id").asText() + "@" + asset.path("version").asText();
                assertTrue(identities.add(identity), "duplicate asset: " + collection + "/" + identity);
                String source = asset.path("source").asText();
                assertFalse(source.isBlank(), "asset source is required: " + identity);
                if (source.startsWith("repo://")) {
                    assertTrue(Files.exists(ROOT.resolve(source.substring("repo://".length()))),
                            "local asset source does not exist: " + source);
                }
            }
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("catalog"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
