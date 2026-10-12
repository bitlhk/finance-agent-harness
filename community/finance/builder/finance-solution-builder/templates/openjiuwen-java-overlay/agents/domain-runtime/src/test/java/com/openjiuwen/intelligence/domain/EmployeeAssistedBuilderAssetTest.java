package com.openjiuwen.intelligence.domain;

import com.openjiuwen.intelligence.catalog.YamlCapabilityCatalog;
import com.openjiuwen.intelligence.execution.SkillPackageAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeAssistedBuilderAssetTest {
    @Test
    void employeeAssistedCapabilityAndSkillAreInstalled() throws Exception {
        Path working = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path agents = Files.isDirectory(working.resolve("agents"))
                ? working.resolve("agents")
                : working.resolve("../..").normalize().resolve("agents");
        YamlCapabilityCatalog catalog = YamlCapabilityCatalog.loadMerged(
                Thread.currentThread().getContextClassLoader(),
                YamlCapabilityCatalog.catalogRoots(agents));

        var tool = catalog.findById("wealth.previsit-context.query").orElseThrow();
        assertEquals("agent.wealth_aggregate", tool.ownerAgentId());
        assertTrue(tool.requiredPermissions().contains("wealth:read"));

        Path skillMd = agents.resolve(
                "wealth-aggregate/assets/skills/wealth-previsit-diagnosis/SKILL.md");
        assertTrue(Files.isRegularFile(skillMd));
        var parsed = SkillPackageAdapter.parse(Files.readString(skillMd));
        assertEquals("wealth-previsit-diagnosis", parsed.name());
        assertTrue(parsed.allowedTools().contains("wealth-previsit-context-query"));
    }
}
