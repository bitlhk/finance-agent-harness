package io.github.bitlhk.finance.harness.providers.nonaibanking;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;
import static io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider.*;

/** Reference simulator construction; business protocol and current source identity remain in this artifact. */
public final class NonAiBankingFactoryV01 implements ManagedProviderFactoryV01 {
    public String providerId() { return PROVIDER_ID; }
    public String connectionKind() { return "synthetic-bank-http.v1"; }
    public Map<String, Set<String>> requirements() {
        return Map.of(ACCOUNT_BALANCE_QUERY, Set.of("account:read"),
                ACCOUNT_STATEMENT_QUERY, Set.of("account:read"),
                ACCOUNT_TRANSACTION_QUERY, Set.of("account:read"),
                WEALTH_CUSTOMER_CONTEXT_QUERY, Set.of("wealth:customer:read"),
                TRANSACTION_SUMMARY_QUERY, Set.of("cashflow:read"),
                SPENDING_ANALYSIS_QUERY, Set.of("cashflow:read"),
                CHANNEL_PREFERENCES_UPDATE, Set.of("channel:write"),
                TRANSFER_INTERNAL_CREATE, Set.of("transfer:create"));
    }
    public DescribedFinanceProvider create(Connection connection, Map<String, BoundFinanceProvider> dependencies) {
        return new NonAiBankingProvider(Config.hostSession(connection.endpoint(), connection.timeout(), () -> {
            var session = connection.currentSession().get();
            return new HostSession(session.credential(), session.subjectRef(), session.principalId(),
                    session.hostSessionRef(), session.expiresAt());
        }, call -> connection.attempts().accept(new HttpAttempt(call.method(), call.route(), call.status(), call.observedAt())))
                .observations(connection.observation()));
    }
}
