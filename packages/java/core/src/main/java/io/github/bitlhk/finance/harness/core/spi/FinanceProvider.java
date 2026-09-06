package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;

/** Southbound contract implemented by bank-system or simulator adapters. */
public interface FinanceProvider {
    CapabilityResultV1 query(CapabilityRequestV1 request);

    CapabilityResultV1 executeTask(CapabilityRequestV1 request);
}
