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
        assertEquals(4, catalog.snapshot().descriptionAssets().size());
        assertEquals(List.of("demo-bank-terminology-zh-cn@0.1.0"), catalog.snapshot().terminologyAssets());
        assertTrue(catalog.description("transfer.internal.create").contains("转账前要核对什么"));
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
