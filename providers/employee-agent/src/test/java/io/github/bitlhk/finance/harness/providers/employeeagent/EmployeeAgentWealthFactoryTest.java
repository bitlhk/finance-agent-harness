package io.github.bitlhk.finance.harness.providers.employeeagent;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoriesV01;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeAgentWealthFactoryTest {
    private final EmployeeAgentRuntimeIdentity identity = new EmployeeAgentRuntimeIdentity(
            "synthetic-runtime", "synthetic-agent", "lgj-synthetic");

    private ManagedProviderFactoryV01.Connection connection(String kind) {
        return new ManagedProviderFactoryV01.Connection(kind, URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(1), () -> { throw new AssertionError("No simulator identity reuse"); },
                () -> null, ignored -> {});
    }

    @Test void installationDoesNotReadIdentityOrMintTokens() {
        var factory = new EmployeeAgentWealthFactoryV01(identity,
                context -> { throw new AssertionError("Plan must not sign tokens"); },
                () -> { throw new AssertionError("Plan must not resolve employee identity"); });
        var registry = new ManagedProviderFactoriesV01(List.of(factory));
        var providers = registry.create(connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND),
                () -> { throw new AssertionError("Plan must not grant permissions"); }, Set.of(factory.providerId()));
        assertEquals(Set.of(factory.providerId()), providers.keySet());
        assertEquals(Set.of("wealth:allocation:read"), factory.requirements()
                .get(EmployeeAgentWealthProvider.WEALTH_ALLOCATION_CONTEXT_QUERY));
        assertEquals(Set.of("wealth:previsit:read"), factory.requirements()
                .get(EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY));
    }

    @Test void simulatorConnectionCannotBeReusedForEmployeeRequests() {
        var factory = new EmployeeAgentWealthFactoryV01(identity, context -> "unused", () -> null);
        assertThrows(IllegalArgumentException.class, () -> factory.create(connection("synthetic-bank-http.v1"), Map.of()));
    }

    @Test void declaredScopeDoesNotGrantProductAccess() {
        var tokenCalls = new AtomicInteger();
        var factory = new EmployeeAgentWealthFactoryV01(identity, context -> {
            tokenCalls.incrementAndGet();
            throw new AssertionError("Denied access must not sign tokens");
        }, () -> new EmployeeAgentIdentityBinding("synthetic-manager", identity,
                "fixture:authority", Instant.now().plusSeconds(60)));
        var grant = new BoundFinanceProvider.Grant("synthetic-manager", FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of("syn-customer"), Set.of("WEALTH_PREVISIT"), Set.of("wealth:previsit:read"),
                "syn-session", "fixture:authority", Instant.now().plusSeconds(60));
        var providers = new ManagedProviderFactoriesV01(List.of(factory)).create(
                connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND), () -> grant, Set.of(factory.providerId()));
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "syn-request", "syn-trace",
                "synthetic-manager", FinanceContextV1.PrincipalType.EMPLOYEE, "employee-demo", "WEALTH_PREVISIT",
                "syn-customer", "syn-session", FinanceContextV1.AuthenticationLevel.STRONG, Set.of("wealth:allocation:read"));
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentWealthProvider.WEALTH_ALLOCATION_CONTEXT_QUERY, CapabilityRequestV1.Operation.QUERY,
                context, Map.of("customerRef", "syn-customer"), null, "syn-key", Map.of(), null);
        assertThrows(SecurityException.class, () -> providers.get(factory.providerId()).query(request));
        assertEquals(0, tokenCalls.get());
    }
}
