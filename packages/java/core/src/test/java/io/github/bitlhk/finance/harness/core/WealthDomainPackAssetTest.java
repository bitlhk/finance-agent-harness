package io.github.bitlhk.finance.harness.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WealthDomainPackAssetTest {
    private static final Path ROOT = findRepositoryRoot();

    @Test
    void employeeAssistedPackKeepsProfessionalJudgmentOutOfCore() throws IOException {
        Path packRoot = ROOT.resolve("domain-packs/wealth/employee-assisted");
        String pack = Files.readString(packRoot.resolve("pack.yaml"));
        String skill = Files.readString(
                packRoot.resolve("skills/wealth-previsit-diagnosis/SKILL.md"));

        assertTrue(pack.contains("profile: employee-assisted"));
        assertTrue(pack.contains("wealth.previsit-context.query"));
        assertTrue(skill.contains("已核验事实、待验证假设、缺失信息和冲突信息"));
        assertTrue(skill.contains("首轮最多提出三个"));
        assertTrue(skill.contains("当前事实不足时不进入正式产品推荐"));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("domain-packs/wealth"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
