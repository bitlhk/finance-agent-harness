package io.github.bitlhk.finance.harness.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InsuranceDomainPackAssetTest {
    private static final Path ROOT = findRepositoryRoot();

    @Test
    void employeeAssistedInsurancePackKeepsProfessionalJudgmentOutOfCore() throws IOException {
        Path packRoot = ROOT.resolve("domain-packs/insurance/employee-assisted");
        String pack = Files.readString(packRoot.resolve("pack.yaml"));
        String skill = Files.readString(
                packRoot.resolve("skills/insurance-previsit-diagnosis/SKILL.md"));

        assertTrue(pack.contains("profile: employee-assisted"));
        assertTrue(pack.contains("insurance.customer-context.query"));
        assertTrue(skill.contains("已确认缺口、可能缺口、不适用和无法判断"));
        assertTrue(skill.contains("首轮最多提出三个"));
        assertTrue(skill.contains("不承诺最低价、一定承保、必赔"));
        assertTrue(skill.contains("本 Pack 只负责专业判断"));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("domain-packs/insurance"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
