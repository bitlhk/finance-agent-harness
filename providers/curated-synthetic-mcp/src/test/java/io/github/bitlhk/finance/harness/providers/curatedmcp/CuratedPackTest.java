package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CuratedPackTest {
    @Test void syntheticTransferQueriesRequireExplicitDistinctObjectRoles() throws Exception {
        var pack = HarnessJson.mapper().readTree(Path.of("../../capability-packs/curated-synthetic-transfer-risk/pack.yaml").toFile());
        for (var capability : pack.path("capabilities")) {
            var input = capability.path("inputSchema");
            assertEquals(List.of("reference"), HarnessJson.mapper().convertValue(input.path("required"), List.class));
            var reference = input.path("properties").path("reference");
            assertFalse(reference.has("default"));
            String role = capability.path("toolName").asText().equals("curated_account_balance_query") ? "payer" : "beneficiary";
            assertTrue(reference.path("description").asText().contains(role));
            assertTrue(reference.path("description").asText().contains("clarify"));
        }
    }
    @Test void draftPacksExactlyCoverContractsWithNoExistingCapabilityAliases() throws Exception {
        Path root = Path.of("../..");var capabilities = new HashSet<String>();var tools = new HashSet<String>();
        try (var paths = Files.list(root.resolve("capability-packs"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("curated-synthetic-")).toList()) {
                var pack = HarnessJson.mapper().readTree(path.resolve("pack.yaml").toFile());
                assertEquals(Set.of("curated-synthetic-customer", "curated-synthetic-wealth", "curated-synthetic-transfer-risk").contains(pack.path("packId").asText())
                        ? "1.1.0" : "1.0.0", pack.path("version").asText());
                for (var cap : pack.path("capabilities")) {
                    var contract = CuratedTools.capability(cap.path("capabilityId").asText());
                    assertTrue(capabilities.add(contract.capabilityId()));assertTrue(tools.add(cap.path("toolName").asText()));
                    assertEquals(contract.toolName(), cap.path("toolName").asText());assertEquals("QUERY", cap.path("operation").asText());
                    assertEquals(contract.scope(), cap.path("requiredScopes").get(0).asText());
                    assertFalse(cap.path("inputSchema").path("additionalProperties").asBoolean());
                    if (contract.selector().equals("customer_id")) {
                        String description = cap.path("inputSchema").path("properties").path("customer_id").path("description").asText();
                        assertTrue(description.contains("subjectRef"));
                        String resource = cap.path("inputSchema").path("properties").path("synthetic_customer_id").path("description").asText();
                        assertTrue(resource.contains("SYNTHETIC_ALLOWLIST") && resource.contains("Do not choose"));
                    }
                }
            }
        }
        assertEquals(16, capabilities.size());
        assertEquals(capabilities, new CuratedMcpFactoryV01().requirements().keySet());
        assertEquals(1, ServiceLoader.load(ManagedProviderFactoryV01.class).stream().filter(p -> p.type().equals(CuratedMcpFactoryV01.class)).count());
    }

    @Test void candidateCatalogContainsNativeValidatedReadonlyCohortNotTask() throws Exception {
        Path root = Path.of("../..");
        var catalog = HarnessJson.mapper().readTree(root.resolve("catalog/finance-assets.v1.json").toFile());
        var expectedPacks = Set.of("customer", "wealth", "insurance", "research", "audit", "transfer-risk", "post-loan");
        var actualPacks = new HashSet<String>();
        var capabilities = new HashSet<String>();
        for (var asset : catalog.path("capabilityPacks")) {
            String id = asset.path("id").asText();
            if (!id.startsWith("curated-synthetic-")) continue;
            actualPacks.add(id.substring("curated-synthetic-".length()));
            assertEquals(List.of("local-read-only"), HarnessJson.mapper().convertValue(
                    asset.path("compatibility").path("profiles"), List.class));
            assertEquals(List.of("java"), HarnessJson.mapper().convertValue(
                    asset.path("compatibility").path("runtimeLanguages"), List.class));
            var pack = HarnessJson.mapper().readTree(root.resolve(asset.path("source").asText().substring(7))
                    .resolve("pack.yaml").toFile());
            assertEquals(pack.path("version"), asset.path("version"));
            var declared = new HashSet<String>();
            for (var cap : pack.path("capabilities")) {
                assertEquals("QUERY", cap.path("operation").asText());
                declared.add(cap.path("capabilityId").asText());
            }
            var listed = new HashSet<String>();
            asset.path("capabilities").forEach(c -> listed.add(c.asText()));
            assertEquals(declared, listed);
            var observation = HarnessJson.mapper().readTree(root.resolve("observation-packs/readonly/curated-"
                    + id.substring("curated-synthetic-".length()) + ".json").toFile());
            assertEquals(1, observation.path("requirements").size());
            var requirement = observation.path("requirements").get(0);
            assertEquals("REQUIRED", requirement.path("obligation").asText());
            assertEquals("capability.result", requirement.path("semanticId").asText());
            var scope = new HashSet<String>();
            requirement.path("capabilityIds").forEach(c -> scope.add(c.asText()));
            assertEquals(declared, scope);
            capabilities.addAll(listed);
        }
        assertEquals(expectedPacks, actualPacks);
        assertEquals(16, capabilities.size());
        var assembly = HarnessJson.mapper().readTree(Path.of("assembly.v0.1.json").toFile());
        var supported = new HashSet<String>();
        var supportedPacks = new HashSet<String>();
        assembly.path("supports").forEach(p -> {
            assertTrue(supportedPacks.add(p.path("pack").path("id").asText().substring("curated-synthetic-".length())));
            assertEquals(Set.of("curated-synthetic-customer", "curated-synthetic-wealth", "curated-synthetic-transfer-risk").contains(p.path("pack").path("id").asText()) ? "1.1.0" : "1.0.0", p.path("pack").path("version").asText());
            assertEquals("QUERY", p.path("operation").asText());
            p.path("capabilityIds").forEach(c -> assertTrue(supported.add(c.asText())));
        });
        assertEquals(expectedPacks, supportedPacks);
        assertEquals(capabilities, supported);
        var providers = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        catalog.path("providers").forEach(p -> {
            if (p.path("id").asText().equals(CuratedMcpProvider.ID)) providers.add(p);
        });
        assertEquals(1, providers.size());
        var providerCaps = new HashSet<String>();
        providers.getFirst().path("capabilities").forEach(c -> providerCaps.add(c.asText()));
        assertEquals(capabilities, providerCaps);
    }

    @Test void projectionCoversCandidateContractsWithoutGrantingCatalogAdmission() throws Exception {
        var projection = HarnessJson.mapper().readTree(Path.of("eval-projection.v1.json").toFile());
        assertEquals("SYNTHETIC_EVALUATION_ONLY", projection.path("scope").asText());
        assertEquals(CuratedMcpProvider.ID, projection.path("providerId").asText());
        var projectedCapabilities = new HashSet<String>();
        var entries = projection.path("tools").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            projectedCapabilities.add(CuratedTools.named(entry.getKey()).capabilityId());
            String pointer = entry.getValue().path("resourcePath").asText();
            if (entry.getKey().equals("get_wealth_policy_basis")) {
                assertEquals("", CuratedTools.named(entry.getKey()).identityPointer());
                assertEquals("/policyVersion", pointer);
                assertEquals(List.of("demo-wealth-policy-v1"), HarnessJson.mapper().convertValue(
                        entry.getValue().path("resourceValues"), List.class));
            } else {
                assertEquals(CuratedTools.named(entry.getKey()).identityPointer(), pointer);
                assertFalse(entry.getValue().has("resourceValues"));
            }
            var fields = entry.getValue().path("fields");
            assertTrue(fields.isObject() && fields.size() > 0 && fields.size() <= 64);
            fields.forEach(p -> assertTrue(p.isTextual() && p.asText().matches("(/[A-Za-z][A-Za-z0-9_]*|/\\*){1,8}")));
            var optional = entry.getValue().path("optionalFields");
            if (!optional.isMissingNode()) {
                assertTrue(optional.isObject() && optional.size() > 0 && fields.size() + optional.size() <= 60);
                optional.fieldNames().forEachRemaining(name -> assertFalse(fields.has(name)));
                optional.forEach(p -> assertTrue(p.isTextual() && p.asText().matches("(/[A-Za-z][A-Za-z0-9_]*|/\\*){1,8}")));
            }
        }
        assertEquals(new CuratedMcpFactoryV01().requirements().keySet(), projectedCapabilities);
    }

    @Test void riskProjectionRetainsCoverageAndUnavailableReasonWithoutRawResults() throws Exception {
        var projection = HarnessJson.mapper().readTree(Path.of("eval-projection.v1.json").toFile());
        var risk = projection.path("tools").path("fraud_transfer_risk_assess");
        assertEquals("/scope", risk.path("optionalFields").path("coverageScope").asText());
        assertEquals("/reason", risk.path("optionalFields").path("unavailableReason").asText());
        assertEquals(2, risk.path("optionalFields").size());
        assertFalse(risk.path("fields").has("coverageScope"));
        assertFalse(risk.path("fields").has("unavailableReason"));
    }
}
