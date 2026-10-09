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
        var assembly = HarnessJson.mapper().readTree(Path.of("assembly.v0.1.json").toFile());var supported = new HashSet<String>();
        assembly.path("supports").forEach(p -> p.path("capabilityIds").forEach(c -> assertTrue(supported.add(c.asText()))));
        assertEquals(capabilities, supported);
        assertEquals(capabilities, new CuratedMcpFactoryV01().requirements().keySet());
        assertEquals(1, ServiceLoader.load(ManagedProviderFactoryV01.class).stream().filter(p -> p.type().equals(CuratedMcpFactoryV01.class)).count());
        assertFalse(Files.readString(root.resolve("catalog/finance-assets.v1.json")).contains("curated-synthetic-mcp"));
    }
}
