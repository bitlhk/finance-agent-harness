package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenJiuwenDomainPackInstallerTest {
    private static final String SECTION =
            "finance-domain-pack:wealth-employee-assisted:wealth-previsit-diagnosis";

    @Test
    void wealthSkillInstructionsAreInstalledAndRemovedAsRuntimePromptSection() throws Exception {
        FinanceDomainPackLoader.LoadedDomainPack pack = new FinanceDomainPackLoader().load(root().resolve(
                "domain-packs/wealth/employee-assisted/pack.yaml"));
        assertEquals("wealth-manager-previsit", pack.pack().capabilityPackRefs().getFirst().id());
        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id("wealth-domain-pack-agent")
                .name("Wealth Domain Pack Agent")
                .description("Domain Pack installation test")
                .build());

        assertFalse(agent.getSystemPromptBuilder().hasSection(SECTION));
        OpenJiuwenDomainPackInstaller.Installation installation =
                new OpenJiuwenDomainPackInstaller().install(agent, pack);
        try {
            assertTrue(agent.getSystemPromptBuilder().hasSection(SECTION));
            String prompt = agent.getSystemPromptBuilder().build();
            assertTrue(prompt.contains("已核验事实、待验证假设、缺失信息和冲突信息"));
            assertTrue(prompt.contains("首轮最多提出三个"));
            assertFalse(prompt.contains("allowed-tools:"));
            assertTrue(installation.receipt().skills().getFirst().digest()
                    .matches("sha256:[a-f0-9]{64}"));
        } finally {
            installation.close();
        }
        assertFalse(agent.getSystemPromptBuilder().hasSection(SECTION));
    }

    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("domain-packs/wealth"))
                    && Files.isDirectory(current.resolve("integrations/openjiuwen-java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
