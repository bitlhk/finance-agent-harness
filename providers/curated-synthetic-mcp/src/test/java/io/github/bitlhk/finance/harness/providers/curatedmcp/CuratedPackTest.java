package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CuratedPackTest {
    @Test void draftPacksExactlyCoverContractsWithNoExistingCapabilityAliases() throws Exception {
        Path root = Path.of("../..");var capabilities = new HashSet<String>();var tools = new HashSet<String>();
        try (var paths = Files.list(root.resolve("capability-packs"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().startsWith("curated-synthetic-")).toList()) {
                var pack = HarnessJson.mapper().readTree(path.resolve("pack.yaml").toFile());
                assertEquals("1.0.0", pack.path("version").asText());
                for (var cap : pack.path("capabilities")) {
                    var contract = CuratedTools.capability(cap.path("capabilityId").asText());
                    assertTrue(capabilities.add(contract.capabilityId()));assertTrue(tools.add(cap.path("toolName").asText()));
                    assertEquals(contract.toolName(), cap.path("toolName").asText());assertEquals("QUERY", cap.path("operation").asText());
                    assertEquals(contract.scope(), cap.path("requiredScopes").get(0).asText());
                    assertFalse(cap.path("inputSchema").path("additionalProperties").asBoolean());
                    if (contract.selector().equals("customer_id")) assertTrue(cap.path("inputSchema").path("properties").path("customer_id").path("description").asText().contains("subjectRef"));
                }
            }
        }
        assertEquals(16, capabilities.size());
        assertEquals(capabilities, new CuratedMcpFactoryV01().requirements().keySet());
        assertEquals(1, ServiceLoader.load(ManagedProviderFactoryV01.class).stream().filter(p -> p.type().equals(CuratedMcpFactoryV01.class)).count());
    }

    @Test void managedCatalogContainsOnlyTheNativeValidatedReadonlyCohortNotWealthOrTask() throws Exception {
        Path root = Path.of("../..");
        var catalog = HarnessJson.mapper().readTree(root.resolve("catalog/finance-assets.v1.json").toFile());
        var expectedPacks = Set.of("customer", "insurance", "research", "audit", "transfer-risk", "post-loan");
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
        assertEquals(12, capabilities.size());
        var assembly = HarnessJson.mapper().readTree(Path.of("assembly.v0.1.json").toFile());
        var supported = new HashSet<String>();
        var supportedPacks = new HashSet<String>();
        assembly.path("supports").forEach(p -> {
            assertTrue(supportedPacks.add(p.path("pack").path("id").asText().substring("curated-synthetic-".length())));
            assertEquals("1.0.0", p.path("pack").path("version").asText());
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
        var projection = HarnessJson.mapper().readTree(Path.of("eval-projection.v1.json").toFile());
        assertEquals("SYNTHETIC_EVALUATION_ONLY", projection.path("scope").asText());
        assertEquals(CuratedMcpProvider.ID, projection.path("providerId").asText());
        var projectedCapabilities = new HashSet<String>();
        var entries = projection.path("tools").fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            projectedCapabilities.add(CuratedTools.named(entry.getKey()).capabilityId());
            String pointer = entry.getValue().path("resourcePath").asText();
            assertEquals(CuratedTools.named(entry.getKey()).identityPointer(), pointer);
            var fields = entry.getValue().path("fields");
            assertTrue(fields.isObject() && fields.size() > 0 && fields.size() <= 64);
            fields.forEach(p -> assertTrue(p.isTextual() && p.asText().matches("(/[A-Za-z][A-Za-z0-9_]*|/\\*){1,8}")));
        }
        assertEquals(capabilities, projectedCapabilities);
    }
}
