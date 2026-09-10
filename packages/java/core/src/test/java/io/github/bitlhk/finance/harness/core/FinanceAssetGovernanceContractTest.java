package io.github.bitlhk.finance.harness.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinanceAssetGovernanceContractTest {
    private static final Path ROOT = findRepositoryRoot();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> COLLECTION_BY_KIND = Map.of(
            "runtime-integration", "runtimeIntegrations",
            "profile", "profiles",
            "domain-pack", "domainPacks",
            "capability-pack", "capabilityPacks",
            "provider", "providers",
            "eval-pack", "evalPacks");

    @Test
    void catalogPublishesResolvableDependenciesAndGovernance() throws Exception {
        JsonNode catalog = JSON.readTree(ROOT.resolve("catalog/finance-assets.v1.json").toFile());
        assertEquals("finance-asset-catalog.v1", catalog.path("schemaVersion").asText());
        assertFalse(catalog.path("catalogRevision").asText().isBlank());
        JsonNode policies = catalog.path("governancePolicies");
        assertTrue(policies.isObject());

        Map<String, JsonNode> assets = new HashMap<>();
        for (Map.Entry<String, String> entry : COLLECTION_BY_KIND.entrySet()) {
            for (JsonNode asset : catalog.path(entry.getValue())) {
                String identity = entry.getKey() + ":" + asset.path("id").asText()
                        + "@" + asset.path("version").asText();
                assertTrue(assets.put(identity, asset) == null, "duplicate asset: " + identity);
                String governanceRef = asset.path("governanceRef").asText();
                assertTrue(policies.has(governanceRef), "unknown governance policy: " + identity);
                JsonNode policy = policies.path(governanceRef);
                assertFalse(policy.path("owner").asText().isBlank());
                assertEquals("proposal-only", policy.path("changeMode").asText());
                assertFalse(policy.path("approvalPolicy").asText().isBlank());
                assertFalse(policy.path("verification").path("value").asText().isBlank());
            }
        }
        for (Map.Entry<String, JsonNode> entry : assets.entrySet()) {
            for (JsonNode dependency : entry.getValue().path("dependencies")) {
                String identity = dependency.path("kind").asText() + ":"
                        + dependency.path("id").asText() + "@"
                        + dependency.path("version").asText();
                assertTrue(assets.containsKey(identity), entry.getKey() + " requires " + identity);
            }
        }
        JsonNode wealth = assets.get("domain-pack:wealth-employee-assisted@0.1.0");
        assertEquals("wealth-manager-previsit",
                wealth.path("dependencies").path(0).path("id").asText());
    }

    @Test
    void proposalFixtureCarriesReviewablePublicFacts() throws Exception {
        JsonNode proposal = JSON.readTree(ROOT.resolve(
                "examples/governance/finance-asset-change-proposal.v1.json").toFile());
        assertEquals("finance-harness-asset-change-proposal.v1",
                proposal.path("schemaVersion").asText());
        assertEquals("proposed", proposal.path("status").asText());
        assertFalse(proposal.path("target").path("owner").asText().isBlank());
        assertFalse(proposal.path("verification").path("entrypoint").asText().isBlank());
        String serialized = proposal.toString().toLowerCase();
        for (String forbidden : List.of("api_key", "password", "private_key", "chain_of_thought", "c:\\")) {
            assertFalse(serialized.contains(forbidden), "proposal contains private field: " + forbidden);
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
