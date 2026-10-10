package io.github.bitlhk.finance.harness.providers.employeeagent;

import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Explicit host registration: asset metadata cannot supply an EA identity or a signing authority. */
public final class EmployeeAgentWealthFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String CONNECTION_KIND = "employee-agent-platform-mcp.v1";

    private final EmployeeAgentRuntimeIdentity identity;
    private final EmployeeAgentRuntimeTokenSupplier tokens;
    private final Supplier<EmployeeAgentIdentityBinding> binding;

    public EmployeeAgentWealthFactoryV01(EmployeeAgentRuntimeIdentity identity,
                                         EmployeeAgentRuntimeTokenSupplier tokens,
                                         Supplier<EmployeeAgentIdentityBinding> binding) {
        this.identity = Objects.requireNonNull(identity);
        this.tokens = Objects.requireNonNull(tokens);
        this.binding = Objects.requireNonNull(binding);
    }

    @Override public String providerId() { return EmployeeAgentWealthProvider.PROVIDER_ID; }
    @Override public String connectionKind() { return CONNECTION_KIND; }
    @Override public Map<String, Set<String>> requirements() {
        return Map.of(EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY, Set.of("wealth:previsit:read"),
                EmployeeAgentWealthProvider.WEALTH_ALLOCATION_CONTEXT_QUERY, Set.of("wealth:allocation:read"));
    }

    @Override
    public DescribedFinanceProvider create(Connection connection, Map<String, BoundFinanceProvider> dependencies) {
        if (!CONNECTION_KIND.equals(connection.kind()) || !dependencies.isEmpty()) {
            throw new IllegalArgumentException("EA_PROVIDER_CONNECTION_UNSUPPORTED");
        }
        return new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
                connection.endpoint(), identity, tokens, connection.timeout(), binding));
    }
}
