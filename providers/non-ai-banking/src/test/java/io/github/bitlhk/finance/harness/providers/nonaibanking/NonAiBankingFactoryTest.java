package io.github.bitlhk.finance.harness.providers.nonaibanking;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NonAiBankingFactoryTest {
    @Test void packagedFactoryIsDiscoveredAndConstructedWithoutBankCallOrGrant() {
        var connection = new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1",
                URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1),
                () -> { throw new AssertionError("No source identity during assembly"); }, () -> null,
                ignored -> { throw new AssertionError("No bank HTTP during assembly"); });
        var result = ManagedProviderFactoriesV01.installed().create(connection,
                () -> { throw new AssertionError("No grant during assembly"); }, Set.of(NonAiBankingProvider.PROVIDER_ID));
        assertEquals(Set.of(NonAiBankingProvider.PROVIDER_ID), result.keySet());
        var described = (DescribedFinanceProvider) result.get(NonAiBankingProvider.PROVIDER_ID);
        assertEquals(6, described.descriptor().capabilities().size());
    }
}
