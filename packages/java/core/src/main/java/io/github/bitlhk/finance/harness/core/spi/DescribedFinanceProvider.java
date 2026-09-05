package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;

/** Provider SPI required by declarative Solution assembly and conformance testing. */
public interface DescribedFinanceProvider extends FinanceProvider {
    ProviderDescriptorV1 descriptor();

    /** Local adapter code, never a claim about the remote bank's implementation. */
    default java.util.List<Class<?>> artifactTypes() { return java.util.List.of(getClass()); }
}
