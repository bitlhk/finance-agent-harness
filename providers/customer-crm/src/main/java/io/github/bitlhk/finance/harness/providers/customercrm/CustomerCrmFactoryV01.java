package io.github.bitlhk.finance.harness.providers.customercrm;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.time.Clock;
import java.util.*;

/** Standard managed-component declaration; deployment authority remains outside the asset. */
public final class CustomerCrmFactoryV01 implements ManagedProviderFactoryV01 {
    @Override public String providerId() { return CustomerCrmProvider.PROVIDER_ID; }
    @Override public String connectionKind() { return "customer-crm-readonly.v1"; }
    @Override public Map<String, Set<String>> requirements() {
        return Map.of(CustomerCrmProvider.CAPABILITY, Set.of("wealth:previsit:read"),
                CustomerCrmProvider.ALLOCATION_CAPABILITY, Set.of("wealth:allocation:read"));
    }
    @Override public DescribedFinanceProvider create(Connection connection,
            Map<String, BoundFinanceProvider> dependencies) {
        if (!dependencies.isEmpty()) throw new IllegalArgumentException("CRM_PROVIDER_DEPENDENCIES_UNEXPECTED");
        var session = (java.util.function.Supplier<ManagedProviderFactoryV01.SourceSession>) connection.currentSession();
        var client = new CustomerCrmClient(connection.endpoint(), connection.timeout(), () -> session.get().credential());
        return new CustomerCrmProvider("managed-owner", client, () -> {
            var current = session.get();
            return new CustomerCrmAuthority("managed-owner", current.principalId(), current.hostSessionRef(),
                    Set.of(current.subjectRef()), current.expiresAt(), FinanceContextV1.AuthenticationLevel.STRONG);
        }, Clock.systemUTC());
    }
}
