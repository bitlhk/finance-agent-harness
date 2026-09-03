package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;

/** Provider SPI required by declarative Solution assembly and conformance testing. */
public interface DescribedFinanceProvider extends FinanceProvider {
    ProviderDescriptorV1 descriptor();
}
