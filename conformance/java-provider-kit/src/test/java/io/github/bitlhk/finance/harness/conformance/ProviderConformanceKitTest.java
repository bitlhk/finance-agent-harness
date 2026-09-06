package io.github.bitlhk.finance.harness.conformance;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderConformanceKitTest {
    @Test
    void emptyNullAndWrongCapabilitySuitesCannotCertifyAProvider() {
        for (List<ProviderConformanceKit.Case> cases : java.util.Arrays.<List<ProviderConformanceKit.Case>>asList(
                null, List.of(), List.of(new ProviderConformanceKit.Case("wrong",
                        request("other.balance.query"), Set.of(CapabilityResultV1.Status.COMPLETED))))) {
            var report = ProviderConformanceKit.verify(provider(false), cases);
            assertEquals(ProviderConformanceKit.Status.FAIL, report.status());
            assertEquals(List.of("account.balance.query:QUERY"), report.missingCapabilities());
            assertEquals(List.of("account.balance.query:QUERY"), report.declaredCapabilities());
        }
    }

    @Test
    void partialCoverageCannotCertifyAllDeclaredCapabilities() {
        var report = ProviderConformanceKit.verify(provider(false, true), List.of(new ProviderConformanceKit.Case(
                "balance", request("account.balance.query"), Set.of(CapabilityResultV1.Status.COMPLETED))));
        assertEquals(ProviderConformanceKit.Status.FAIL, report.status());
        assertEquals(List.of("account.balance.query:QUERY"), report.testedCapabilities());
        assertEquals(List.of("account.history.query:QUERY"), report.missingCapabilities());
    }

    @Test
    void acceptsAProviderThatDeclaresAndPreservesItsCanonicalContract() {
        ProviderConformanceKit.Report report = ProviderConformanceKit.verify(
                provider(false),
                List.of(new ProviderConformanceKit.Case(
                        "balance-query",
                        request("account.balance.query"),
                        Set.of(CapabilityResultV1.Status.COMPLETED))));

        assertEquals(ProviderConformanceKit.Status.PASS, report.status());
        assertEquals("reference-bank", report.providerId());
        assertEquals(ProviderConformanceKit.Status.PASS, report.cases().getFirst().status());
    }

    @Test
    void reportsThrownExceptionsAndUndeclaredCapabilities() {
        ProviderConformanceKit.Report thrown = ProviderConformanceKit.verify(
                provider(true),
                List.of(new ProviderConformanceKit.Case(
                        "balance-query",
                        request("account.balance.query"),
                        Set.of(CapabilityResultV1.Status.COMPLETED))));
        ProviderConformanceKit.Report undeclared = ProviderConformanceKit.verify(
                provider(false),
                List.of(new ProviderConformanceKit.Case(
                        "unknown-query",
                        request("customer.profile.query"),
                        Set.of(CapabilityResultV1.Status.COMPLETED))));

        assertEquals(ProviderConformanceKit.Status.FAIL, thrown.status());
        assertTrue(thrown.cases().getFirst().findings().stream()
                .anyMatch(finding -> "PROVIDER_THROWN".equals(finding.code())));
        assertEquals(ProviderConformanceKit.Status.FAIL, undeclared.status());
        assertTrue(undeclared.cases().getFirst().findings().stream()
                .anyMatch(finding -> "CAPABILITY_NOT_DECLARED".equals(finding.code())));
    }

    private static DescribedFinanceProvider provider(boolean throwsFailure) {
        return provider(throwsFailure, false);
    }

    private static DescribedFinanceProvider provider(boolean throwsFailure, boolean extraCapability) {
        return new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return new ProviderDescriptorV1(
                        ProviderDescriptorV1.SCHEMA_VERSION,
                        "reference-bank",
                        "1.0.0",
                        extraCapability ? List.of(
                                new ProviderDescriptorV1.CapabilitySupport("account.balance.query", CapabilityRequestV1.Operation.QUERY),
                                new ProviderDescriptorV1.CapabilitySupport("account.history.query", CapabilityRequestV1.Operation.QUERY))
                                : List.of(new ProviderDescriptorV1.CapabilitySupport(
                                "account.balance.query", CapabilityRequestV1.Operation.QUERY)));
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                if (throwsFailure) throw new IllegalStateException("upstream unavailable");
                return new CapabilityResultV1(
                        CapabilityResultV1.SCHEMA_VERSION,
                        request.context().requestId(),
                        request.context().traceId(),
                        CapabilityResultV1.Status.COMPLETED,
                        "查询完成。",
                        Map.of("availableBalance", 1000),
                        List.of(),
                        List.of(),
                        "reference-bank-core",
                        Instant.parse("2026-09-03T01:00:00Z"),
                        null,
                        null);
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("not used");
            }
        };
    }

    private static CapabilityRequestV1 request(String capabilityId) {
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-provider-001",
                "trace-provider-001",
                "customer-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "provider-conformance",
                "ACCOUNT_QUERY",
                "customer-001",
                "session-provider-001",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("account:read"));
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                capabilityId,
                CapabilityRequestV1.Operation.QUERY,
                context,
                Map.of(),
                null,
                null,
                Map.of(),
                null);
    }
}
