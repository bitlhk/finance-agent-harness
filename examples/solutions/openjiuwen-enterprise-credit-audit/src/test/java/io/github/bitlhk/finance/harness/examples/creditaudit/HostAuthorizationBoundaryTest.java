package io.github.bitlhk.finance.harness.examples.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.providers.creditaudit.EnterpriseCreditMaterialAuditProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class HostAuthorizationBoundaryTest {
    @Test
    void deterministicAuditCannotBypassMissingHostScope() {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-1", "trace-1",
                "employee-1", FinanceContextV1.PrincipalType.EMPLOYEE, "credit-audit-isolated",
                "enterprise-credit-material-review", "customer-1", "session-1",
                FinanceContextV1.AuthenticationLevel.STRONG, Set.of());
        var provider = EnterpriseCreditAuditApplication.bindHost(new RejectIfInvokedProvider(), context);
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                EnterpriseCreditMaterialAuditProvider.FINANCIAL, CapabilityRequestV1.Operation.QUERY,
                context, Map.of("materialSetRef", "fixture"), null, null, Map.of(), null);

        SecurityException failure = assertThrows(SecurityException.class, () -> provider.query(request));

        assertEquals("HOST_GRANT_REJECTED", failure.getMessage());
    }

    private static final class RejectIfInvokedProvider implements DescribedFinanceProvider {
        private final ProviderDescriptorV1 descriptor = new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION, "credit-audit-test-provider", "0.1.0",
                List.of(new ProviderDescriptorV1.CapabilitySupport(
                        EnterpriseCreditMaterialAuditProvider.FINANCIAL, CapabilityRequestV1.Operation.QUERY)));

        @Override public ProviderDescriptorV1 descriptor() { return descriptor; }
        @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
            throw new AssertionError("delegate must not run before the host authorization check");
        }
        @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            throw new UnsupportedOperationException();
        }
    }
}
