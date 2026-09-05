package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;

/** Host-owned check invoked again on recovery, including before returning cached task data. */
public interface InvocationAccess {
    void checkAccess(CapabilityRequestV1 request);
}
