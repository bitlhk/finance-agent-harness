package io.github.bitlhk.finance.harness.wealth;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WealthFactsFactoryTest {
    @Test void wealthOwnsItsSourceDependencyAndAdditionalReadRequirement() {
        var factories = ServiceLoader.load(ManagedProviderFactoryV01.class).stream()
                .map(ServiceLoader.Provider::get).toList();
        var factory = factories.stream().filter(f -> f.providerId().equals(WealthFactsProvider.PROVIDER)).findFirst().orElseThrow();
        assertEquals(Set.of("non-ai-banking-reference"), factory.dependencies());
        assertEquals(Set.of("wealth:customer:read"), factory.sourceScopes(Set.of(WealthFactsProvider.CAPABILITY)));
        assertEquals(Set.of(), factory.sourceScopes(Set.of()));
        assertEquals(Map.of(WealthFactsProvider.CAPABILITY, Set.of(WealthFactsProvider.SCOPE)), factory.requirements());
    }
}
