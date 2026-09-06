package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Installs domain Skill instructions into an OpenJiuwen ReAct Agent prompt. */
public final class OpenJiuwenDomainPackInstaller {
    public Installation install(BaseAgent agent, FinanceDomainPackLoader.LoadedDomainPack loaded) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(loaded, "loaded");
        if (!(agent instanceof ReActAgent reactAgent)) {
            throw new IllegalArgumentException("Domain Pack installation requires an OpenJiuwen ReActAgent");
        }
        if (loaded.skills().isEmpty()) {
            throw new IllegalArgumentException("Domain Pack has no installable skills: " + loaded.pack().packId());
        }

        List<String> sections = new ArrayList<>();
        try {
            for (FinanceDomainPackLoader.LoadedSkill skill : loaded.skills()) {
                String section = "finance-domain-pack:" + loaded.pack().packId() + ":" + skill.directory();
                if (reactAgent.getSystemPromptBuilder().hasSection(section)) {
                    throw new IllegalStateException("Agent already contains domain skill section: " + section);
                }
                reactAgent.addPromptBuilderSection(section, skill.instructions(), 70);
                sections.add(section);
            }
        } catch (RuntimeException exception) {
            removeReverse(reactAgent, sections);
            throw exception;
        }

        List<InstalledSkill> installedSkills = loaded.skills().stream()
                .map(skill -> new InstalledSkill(skill.directory(), skill.entry(), skill.digest()))
                .toList();
        Receipt receipt = new Receipt(
                "fh-domain-install-" + UUID.randomUUID(),
                loaded.pack().packId(),
                loaded.pack().version(),
                loaded.pack().profile(),
                loaded.pack().capabilities(),
                installedSkills,
                agent.getCard().getId(),
                Instant.now());
        return new Installation(reactAgent, sections, receipt);
    }

    private static void removeReverse(ReActAgent agent, List<String> sections) {
        for (int index = sections.size() - 1; index >= 0; index--) {
            agent.getSystemPromptBuilder().removeSection(sections.get(index));
        }
    }

    public record InstalledSkill(String directory, String entry, String digest) {
    }

    public record Receipt(
            String installationId,
            String packId,
            String packVersion,
            String profile,
            List<String> capabilities,
            List<InstalledSkill> skills,
            String agentId,
            Instant installedAt) {
        public Receipt {
            capabilities = List.copyOf(capabilities);
            skills = List.copyOf(skills);
        }
    }

    public static final class Installation implements AutoCloseable {
        private final ReActAgent agent;
        private final List<String> sections;
        private final Receipt receipt;
        private boolean closed;

        private Installation(ReActAgent agent, List<String> sections, Receipt receipt) {
            this.agent = agent;
            this.sections = List.copyOf(sections);
            this.receipt = receipt;
        }

        public Receipt receipt() {
            return receipt;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            removeReverse(agent, sections);
            closed = true;
        }
    }
}
