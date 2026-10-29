package io.github.bitlhk.finance.harness.providers.fraud;

import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;

import java.util.Map;
import java.util.Set;

/** Managed construction for the deployment-owned, read-only fraud source connection. */
public final class FraudMiningFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String CONNECTION_KIND = "fraud-mining-readonly.v1";

    @Override public String providerId() { return FraudMiningProvider.PROVIDER_ID; }

    @Override public String connectionKind() { return CONNECTION_KIND; }

    @Override public Map<String, Set<String>> requirements() {
        return Map.of(
                FraudMiningProvider.RISK_EVIDENCE_QUERY, Set.of("fraud-risk:read"),
                FraudMiningProvider.TRANSFER_RISK_ASSESS, Set.of("fraud:read"));
    }

    @Override public DescribedFinanceProvider create(
            Connection connection, Map<String, BoundFinanceProvider> dependencies) {
        if (!dependencies.isEmpty()) {
            throw new IllegalArgumentException("FRAUD_PROVIDER_DEPENDENCIES_UNEXPECTED");
        }
        if (!CONNECTION_KIND.equals(connection.kind())) {
            throw new IllegalArgumentException("FRAUD_CONNECTION_KIND_UNSUPPORTED");
        }
        return new FraudMiningProvider(new FraudMiningProvider.Config(connection.endpoint(), connection.timeout()),
                connection.currentSession());
    }
}
