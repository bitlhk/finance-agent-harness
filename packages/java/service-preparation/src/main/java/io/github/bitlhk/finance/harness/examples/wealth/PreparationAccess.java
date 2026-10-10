package io.github.bitlhk.finance.harness.examples.wealth;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import java.util.Map;

/** Host-owned employee authority and read-only customer fact access. */
public interface PreparationAccess extends AutoCloseable {
    record Snapshot(String authorityRef, String principalRef,
                    FinanceContextV1.AuthenticationLevel authenticationLevel) {
        public Snapshot {
            if (authorityRef == null || authorityRef.isBlank()
                    || principalRef == null || principalRef.isBlank()
                    || authenticationLevel == null
                    || authenticationLevel == FinanceContextV1.AuthenticationLevel.UNKNOWN) {
                throw new IllegalArgumentException("PREPARATION_AUTHORITY_INVALID");
            }
        }
    }

    Snapshot authorize(String customerRef);

    String providerId();

    CapabilityResultV1 query(String requestId, String traceId, String customerRef,
                             String capabilityId, Map<String, Object> arguments);

    @Override
    void close();
}
