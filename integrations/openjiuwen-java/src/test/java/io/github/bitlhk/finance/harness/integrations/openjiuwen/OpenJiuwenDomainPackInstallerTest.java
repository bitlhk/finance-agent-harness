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

    @Test
    void completeWealthAssistantMethodDeclaresThreeReadonlySourcesAndBusinessBoundaries() throws Exception {
        FinanceDomainPackLoader.LoadedDomainPack pack = new FinanceDomainPackLoader().load(root().resolve(
                "domain-packs/wealth/assistant-service/pack.yaml"));
        assertEquals("wealth-assistant-service", pack.pack().packId());
        assertEquals(java.util.List.of("wealth-manager-previsit", "wealth-allocation-context", "wealth-product-facts"),
                pack.pack().capabilityPackRefs().stream().map(FinanceDomainPackV1.AssetRef::id).toList());
        assertEquals(java.util.Set.of("wealth.previsit-context.query", "wealth.allocation-context.query",
                        "wealth.products.query"), java.util.Set.copyOf(pack.pack().capabilities()));

        ReActAgent agent = new ReActAgent(AgentCard.builder()
                .id("wealth-assistant-method-agent")
                .name("Wealth Assistant Method Agent")
                .description("Complete wealth assistant method installation test")
                .build());
        String section = "finance-domain-pack:wealth-assistant-service:wealth-assistant-service";
        try (OpenJiuwenDomainPackInstaller.Installation ignored =
                     new OpenJiuwenDomainPackInstaller().install(agent, pack)) {
            assertTrue(agent.getSystemPromptBuilder().hasSection(section));
            String prompt = agent.getSystemPromptBuilder().build();
            assertTrue(prompt.contains("客户经营"));
            assertTrue(prompt.contains("产品服务"));
            assertTrue(prompt.contains("财富服务建议"));
            assertTrue(prompt.contains("不能仅按编号大小比较"));
            assertTrue(prompt.contains("停止后历史回放"));
            assertFalse(prompt.contains("allowed-tools:"));
        }
        assertFalse(agent.getSystemPromptBuilder().hasSection(section));
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
