package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityDescriptionCatalogV01Test {
    private final Path root = Path.of("../..").toAbsolutePath().normalize();
    private final FinanceCapabilityPackLoader loader = new FinanceCapabilityPackLoader();

    @Test void compilesFiveInstalledCapabilitiesWithVersionedDescriptionsAndTerms() throws Exception {
        var catalog = fullCatalog();
        assertEquals(Set.of("account.balance.query", "finance.transaction-summary.query",
                "finance.spending-analysis.query", "wealth.facts.describe", "transfer.internal.create"),
                catalog.capabilityIds());
        assertTrue(catalog.snapshot().digest().matches("sha256:[a-f0-9]{64}"));
        assertTrue(catalog.snapshot().toolDescriptionsDigest().matches("sha256:[a-f0-9]{64}"));
        assertTrue(catalog.snapshot().terminologyPromptDigest().matches("sha256:[a-f0-9]{64}"));
        assertEquals(4, catalog.snapshot().descriptionAssets().size());
        assertEquals(List.of("demo-bank-terminology-zh-cn@0.1.0"), catalog.snapshot().terminologyAssets());
        assertTrue(catalog.description("transfer.internal.create").contains("转账前要核对什么"));
        assertTrue(catalog.description("account.balance.query").contains("不代表总资产"));
        assertTrue(catalog.terminologyPrompt().contains("i豆"));
        assertTrue(catalog.terminologyPrompt().contains("当前没有对应能力"));
    }

    @Test void selectedPackProducesOnlyInstalledDirectoryAndNoRemovedCapabilityTerms() throws Exception {
        var pack = loader.load(root.resolve("capability-packs/account-balance-readonly/pack.yaml"));
        var catalog = CapabilityDescriptionCatalogV01.load(List.of(pack), List.of(root.resolve(
                "routing/capability-descriptions/account-balance-readonly.v0.1.json")), List.of(root.resolve(
                "routing/terminology/demo-bank-zh-cn.v0.1.json")));
        assertEquals(Set.of("account.balance.query"), catalog.capabilityIds());
        assertTrue(catalog.terminologyPrompt().contains("可用余额"));
        assertFalse(catalog.terminologyPrompt().contains("行内转账"));
        assertTrue(catalog.terminologyPrompt().contains("i豆"));
        assertThrows(IllegalStateException.class,
                () -> catalog.requireExactInstallation(Set.of("account.balance.query", "transfer.internal.create")));
    }

    @Test void requiredScopesRemainOwnedByCapabilityContract() throws Exception {
        var pack = loader.load(root.resolve("capability-packs/internal-transfer/pack.yaml"));
        var before = pack.capabilities().getFirst().requiredScopes();
        var catalog = CapabilityDescriptionCatalogV01.load(List.of(pack), List.of(root.resolve(
                "routing/capability-descriptions/internal-transfer.v0.1.json")), List.of(root.resolve(
                "routing/terminology/demo-bank-zh-cn.v0.1.json")));
        assertEquals(Set.of("transfer:create"), before);
        assertEquals(before, pack.capabilities().getFirst().requiredScopes());
        assertFalse(catalog.snapshot().toString().contains("granted"));
    }

    @Test void descriptionForAnotherPackIsRejected() throws Exception {
        var balance = loader.load(root.resolve("capability-packs/account-balance-readonly/pack.yaml"));
        assertThrows(IllegalArgumentException.class, () -> CapabilityDescriptionCatalogV01.load(List.of(balance),
                List.of(root.resolve("routing/capability-descriptions/internal-transfer.v0.1.json")), List.of()));
    }

    @Test void descriptionAssetIsProjectedToSelectedCapabilitiesWithinTheSamePack() throws Exception {
        var source = loader.load(root.resolve("capability-packs/cashflow-readonly/pack.yaml"));
        var selected = new FinanceCapabilityPackV1(source.schemaVersion(), source.packId(), source.version(),
                source.systemPrompt(), List.of(source.capabilities().getFirst()));
        var catalog = CapabilityDescriptionCatalogV01.load(List.of(selected), List.of(root.resolve(
                "routing/capability-descriptions/cashflow-readonly.v0.1.json")), List.of());
        assertEquals(Set.of("finance.transaction-summary.query"), catalog.capabilityIds());
        assertThrows(IllegalArgumentException.class,
                () -> catalog.description("finance.spending-analysis.query"));
    }

    @Test void terminologyBodyChangesCatalogAndPromptDigest() throws Exception {
        var pack = loader.load(root.resolve("capability-packs/account-balance-readonly/pack.yaml"));
        var description = new CapabilityDescriptionCatalogV01.Description("account.balance.query",
                List.of("查询余额"), List.of("仅返回单账户余额"),
                List.of("余额多少"), List.of("查看总资产"));
        var asset = new CapabilityDescriptionCatalogV01.DescriptionAsset(
                "finance-capability-descriptions.v0.1", "balance-test-description", "0.1.0",
                "account-balance-readonly", "0.1.0", List.of(description));
        var firstTerm = new CapabilityDescriptionCatalogV01.Term("balance-term", "可用余额", List.of("卡余额"),
                "账户可用余额。", "账户", CapabilityDescriptionCatalogV01.SupportStatus.SUPPORTED,
                List.of("account.balance.query"), "project-convention:test");
        var changedTerm = new CapabilityDescriptionCatalogV01.Term("balance-term", "可用余额", List.of("能用的钱"),
                "当前可支付的账户余额。", "账户", CapabilityDescriptionCatalogV01.SupportStatus.SUPPORTED,
                List.of("account.balance.query"), "project-convention:test");
        var first = CapabilityDescriptionCatalogV01.compile(List.of(pack), List.of(asset), List.of(
                new CapabilityDescriptionCatalogV01.TerminologyAsset("finance-terminology-pack.v0.1",
                        "test-terms", "0.1.0", "test", List.of(firstTerm))));
        var changed = CapabilityDescriptionCatalogV01.compile(List.of(pack), List.of(asset), List.of(
                new CapabilityDescriptionCatalogV01.TerminologyAsset("finance-terminology-pack.v0.1",
                        "test-terms", "0.1.0", "test", List.of(changedTerm))));
        assertEquals(first.snapshot().toolDescriptionsDigest(), changed.snapshot().toolDescriptionsDigest());
        assertFalse(first.snapshot().terminologyPromptDigest().equals(changed.snapshot().terminologyPromptDigest()));
        assertFalse(first.snapshot().digest().equals(changed.snapshot().digest()));
    }

    @Test void outputBoundaryChangesFinalToolDescriptionDigestAndOldV01RemainsReadable() throws Exception {
        var pack = loader.load(root.resolve("capability-packs/account-balance-readonly/pack.yaml"));
        var old = new CapabilityDescriptionCatalogV01.Description("account.balance.query",
                List.of("查询余额"), null, List.of("还有多少钱"), List.of("查总资产"));
        var bounded = new CapabilityDescriptionCatalogV01.Description("account.balance.query",
                List.of("查询余额"), List.of("仅返回单账户余额"),
                List.of("还有多少钱"), List.of("查总资产"));
        var oldCatalog = CapabilityDescriptionCatalogV01.compile(List.of(pack), List.of(descriptionAsset(old)), List.of());
        var boundedCatalog = CapabilityDescriptionCatalogV01.compile(
                List.of(pack), List.of(descriptionAsset(bounded)), List.of());
        assertFalse(oldCatalog.description("account.balance.query").contains("输出边界"));
        assertTrue(boundedCatalog.description("account.balance.query").contains("输出边界"));
        assertFalse(oldCatalog.snapshot().toolDescriptionsDigest()
                .equals(boundedCatalog.snapshot().toolDescriptionsDigest()));
        assertFalse(oldCatalog.snapshot().digest().equals(boundedCatalog.snapshot().digest()));
    }

    private CapabilityDescriptionCatalogV01.DescriptionAsset descriptionAsset(
            CapabilityDescriptionCatalogV01.Description description) {
        return new CapabilityDescriptionCatalogV01.DescriptionAsset(
                "finance-capability-descriptions.v0.1", "balance-test-description", "0.1.0",
                "account-balance-readonly", "0.1.0", List.of(description));
    }

    private CapabilityDescriptionCatalogV01 fullCatalog() throws Exception {
        return CapabilityDescriptionCatalogV01.load(List.of(
                        loader.load(root.resolve("capability-packs/account-balance-readonly/pack.yaml")),
                        loader.load(root.resolve("capability-packs/cashflow-readonly/pack.yaml")),
                        loader.load(root.resolve("capability-packs/wealth-facts-description/pack.yaml")),
                        loader.load(root.resolve("capability-packs/internal-transfer/pack.yaml"))),
                List.of(
                        root.resolve("routing/capability-descriptions/account-balance-readonly.v0.1.json"),
                        root.resolve("routing/capability-descriptions/cashflow-readonly.v0.1.json"),
                        root.resolve("routing/capability-descriptions/wealth-facts-description.v0.1.json"),
                        root.resolve("routing/capability-descriptions/internal-transfer.v0.1.json")),
                List.of(root.resolve("routing/terminology/demo-bank-zh-cn.v0.1.json")));
    }
}
