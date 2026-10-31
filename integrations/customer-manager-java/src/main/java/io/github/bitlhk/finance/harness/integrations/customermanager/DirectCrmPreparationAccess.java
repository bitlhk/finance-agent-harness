package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.providers.customercrm.*;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct CRM access for the managed Host; it has no Employee Agent application dependency. */
public final class DirectCrmPreparationAccess implements PreparationAccess {
    private final String ownerRef;
    private final String sessionRef;
    private final CustomerCrmProvider provider;
    private final io.github.bitlhk.finance.harness.core.spi.FinanceProvider invocationProvider;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DirectCrmPreparationAccess(String ownerRef, String sessionRef, CustomerCrmProvider provider) {
        this(ownerRef, sessionRef, provider, () -> null);
    }

    public DirectCrmPreparationAccess(String ownerRef, String sessionRef, CustomerCrmProvider provider,
            java.util.function.Supplier<io.github.bitlhk.finance.harness.core.observation.ObservationRun> observation) {
        this.ownerRef = required(ownerRef); this.sessionRef = required(sessionRef);
        this.provider = Objects.requireNonNull(provider);
        this.invocationProvider = new io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider(
                provider, CustomerCrmProvider.PROVIDER_ID, observation);
    }

    io.github.bitlhk.finance.harness.core.spi.FinanceProvider invocationProvider() { return invocationProvider; }

    @Override public Snapshot authorize(String customerRef) {
        if (closed.get()) throw new SecurityException("CRM_SESSION_CLOSED");
        CustomerCrmAuthority value = provider.authorize(customerRef);
        if (closed.get()) throw new SecurityException("CRM_SESSION_CLOSED");
        return new Snapshot(value.authorityRef(), value.principalRef(), value.authenticationLevel());
    }

    @Override public String providerId() { return CustomerCrmProvider.PROVIDER_ID; }

    @Override public CapabilityResultV1 query(String requestId, String traceId, String customerRef,
            String capabilityId, Map<String, Object> arguments) {
        Snapshot before = authorize(customerRef);
        boolean allocation=CustomerCrmProvider.ALLOCATION_CAPABILITY.equals(capabilityId);
        if (!allocation && !arguments.isEmpty()) throw new IllegalArgumentException("PREVISIT_ARGUMENTS_INVALID");
        FinanceContextV1 context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,
                requestId, traceId, before.principalRef(), FinanceContextV1.PrincipalType.EMPLOYEE,
                "finance-harness", allocation?"WEALTH_PRODUCT_REVIEW":"WEALTH_PREVISIT", customerRef, sessionRef,
                before.authenticationLevel(), Set.of(allocation?"wealth:allocation:read":"wealth:previsit:read"));
        var parameters=new LinkedHashMap<String,Object>(arguments);parameters.put("customerRef",customerRef);
        CapabilityRequestV1 request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                capabilityId, CapabilityRequestV1.Operation.QUERY, context,
                Map.copyOf(parameters), null, null, Map.of(), null);
        CapabilityResultV1 result = invocationProvider.query(request);
        if (!before.equals(authorize(customerRef))) throw new SecurityException("CRM_AUTHORITY_CHANGED");
        return result;
    }

    @Override public void close() { closed.set(true); }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("HOST_REFERENCE_REQUIRED");
        return value.trim();
    }
}
