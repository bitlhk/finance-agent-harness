package io.github.bitlhk.finance.harness.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskDomainPackAssetTest {
    private static final Path ROOT = findRepositoryRoot();

    @Test
    void riskReviewPackKeepsBankRiskRulesOutOfCore() throws Exception {
        Path packRoot = ROOT.resolve("domain-packs/risk/internal-control");
        String pack = Files.readString(packRoot.resolve("pack.yaml"));
        String skill = Files.readString(
                packRoot.resolve("skills/transfer-risk-review/SKILL.md"));

        assertTrue(pack.contains("profile: internal-control"));
        assertTrue(pack.contains("fraud.transfer-risk.assess"));
        assertTrue(pack.contains("在 Domain Pack 中固化银行风险阈值"));
        assertTrue(skill.contains("命中已知涉诈风险、未命中已知涉诈风险、风险系统不可用"));
        assertTrue(skill.contains("未命中已知记录"));
        assertTrue(skill.contains("银行系统或有权审核人员决定"));
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("domain-packs/risk"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
