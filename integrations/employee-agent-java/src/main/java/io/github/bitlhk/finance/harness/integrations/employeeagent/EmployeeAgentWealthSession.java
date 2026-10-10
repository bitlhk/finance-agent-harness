package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoriesV01;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentIdentityBinding;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeTokenSupplier;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthFactoryV01;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Employee-only host wiring. This consumes authority; it never grants it or resolves users by email. */
public final class EmployeeAgentWealthSession implements AutoCloseable {
    private final String ownerRef;
    private final String sessionRef;
    private final EmployeeAgentRuntimeIdentity runtime;
    private final Supplier<Authority> authority;
    private final Map<String, Set<String>> requirements;
    private final Set<String> installedCapabilities;
    private final FinanceProvider provider;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EmployeeAgentWealthSession(String ownerRef, String sessionRef,
            EmployeeAgentRuntimeIdentity runtime, Supplier<Authority> authority,
            EmployeeAgentRuntimeTokenSupplier tokens, ManagedProviderFactoryV01.Connection connection,
            Set<String> installedCapabilities) {
        this.ownerRef = required(ownerRef);
        this.sessionRef = required(sessionRef);
        this.runtime = Objects.requireNonNull(runtime);
        this.authority = Objects.requireNonNull(authority);
        Objects.requireNonNull(tokens);
        var factory = new EmployeeAgentWealthFactoryV01(runtime, context -> {
            // Token signing/brokering remains in the trusted deployment, not in assets or browser inputs.
            current();
            return tokens.tokenFor(context);
        }, () -> current().binding());
        this.requirements = factory.requirements();
        this.installedCapabilities = Set.copyOf(installedCapabilities);
        if (this.installedCapabilities.isEmpty() || !requirements.keySet().containsAll(this.installedCapabilities)) {
            throw new IllegalArgumentException("EMPLOYEE_QUERY_NOT_INSTALLED");
        }
        this.provider = new ManagedProviderFactoriesV01(List.of(factory)).create(connection,
                () -> current().grant(), Set.of(factory.providerId())).get(factory.providerId());
    }

    public CapabilityResultV1 query(String requestId, String traceId, String customerRef,
            String capabilityId, Map<String, Object> arguments) {
        var scopes = requirements.get(capabilityId);
        if (scopes == null || !installedCapabilities.contains(capabilityId)) {
            throw new IllegalArgumentException("EMPLOYEE_QUERY_NOT_INSTALLED");
        }
        if (arguments.containsKey("customerRef")) {
            throw new IllegalArgumentException("CUSTOMER_SELECTION_IS_HOST_OWNED");
        }
        if (EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY.equals(capabilityId)
                && !arguments.isEmpty()) throw new IllegalArgumentException("PREVISIT_ARGUMENTS_INVALID");
        Authority initial = current();
        var parameters = new LinkedHashMap<String, Object>(arguments);
        parameters.put("customerRef", required(customerRef));
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, requestId, traceId,
                initial.grant().principalId(), FinanceContextV1.PrincipalType.EMPLOYEE,
                "finance-harness", "WEALTH_PREVISIT", customerRef, sessionRef,
                initial.authenticationLevel(), scopes);
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, capabilityId,
                CapabilityRequestV1.Operation.QUERY, context, parameters, null, null, Map.of(), null);
        CapabilityResultV1 result = provider.query(request);
        // Do not expose even valid remote facts after owner, subject grants, or authority changes.
        // The response controller must additionally enforce its own atomic run-generation commit.
        if (!initial.equals(current())) throw new SecurityException("EA_AUTHORITY_CHANGED");
        return result;
    }

    private Authority current() {
        if (closed.get()) throw new SecurityException("EA_SESSION_CLOSED");
        Authority value = authority.get();
        Instant now = Instant.now();
        if (closed.get() || value == null || !ownerRef.equals(value.ownerRef())
                || !runtime.equals(value.binding().runtime())
                || !sessionRef.equals(value.grant().sessionRef())
                || value.grant().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !value.grant().principalId().equals(value.binding().principalId())
                || !value.grant().authorityRef().equals(value.binding().authorityRef())
                || !value.grant().expiresAt().isAfter(now) || !value.binding().expiresAt().isAfter(now)
                || value.authenticationLevel() == FinanceContextV1.AuthenticationLevel.UNKNOWN) {
            throw new SecurityException("EA_HOST_AUTHORITY_REJECTED");
        }
        return value;
    }

    /** Recheck read/save access without issuing a token or contacting CRM. */
    public Authority authorizePrevisit(String customerRef) {
        Authority value = current();
        String capability = EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY;
        if (!installedCapabilities.contains(capability)
                || !value.grant().subjects().contains(required(customerRef))
                || !value.grant().purposes().contains("WEALTH_PREVISIT")
                || !value.grant().scopes().containsAll(requirements.get(capability))) {
            throw new SecurityException("HOST_GRANT_REJECTED");
        }
        return value;
    }

    @Override public void close() { closed.set(true); }

    /** Server-verified snapshot. Never deserialize this record from a chat/build request. */
    public record Authority(String ownerRef, EmployeeAgentIdentityBinding binding,
            BoundFinanceProvider.Grant grant, FinanceContextV1.AuthenticationLevel authenticationLevel) {
        public Authority {
            ownerRef = required(ownerRef);
            Objects.requireNonNull(binding);
            Objects.requireNonNull(grant);
            Objects.requireNonNull(authenticationLevel);
        }

        @Override public String toString() { return "Authority[redacted]"; }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HOST_REFERENCE_REQUIRED");
        return value;
    }
}
