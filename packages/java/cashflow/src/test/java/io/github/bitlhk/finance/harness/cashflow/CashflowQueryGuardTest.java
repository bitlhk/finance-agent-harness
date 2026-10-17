package io.github.bitlhk.finance.harness.cashflow;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CashflowQueryGuardTest {
    final List<Map<String,Object>> events = new ArrayList<>();
    final AtomicInteger reads = new AtomicInteger();
    final AtomicBoolean revoked = new AtomicBoolean();
    boolean expired, rejectReturnAudit;
    Set<String> grantedScopes = Set.of("cashflow:read");
    final String policy = "sha256:" + "a".repeat(64), binding = "sha256:" + "b".repeat(64);
    CapabilityRequestV1 request(String subject, Map<String,Object> parameters) {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request", "trace", "actor",
                FinanceContextV1.PrincipalType.CUSTOMER, "host", "purpose", subject, "session",
                FinanceContextV1.AuthenticationLevel.BASIC, Set.of("cashflow:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CashflowQueryGuard.CAPABILITY,
                CapabilityRequestV1.Operation.QUERY, context, parameters, null, null, Map.of(), null);
    }
    CashflowQueryGuard.Authorization authority() {
        var grant = revoked.get() ? null : new BoundFinanceProvider.Grant("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("subject"), Set.of("purpose"), grantedScopes, "session", "policy", Instant.now().plusSeconds(expired ? -1 : 60));
        return new CashflowQueryGuard.Authorization(grant, policy);
    }
    Map<String,Object> facts() {
        // Null totals are contract-valid and must remain missing, never zero-filled.
        return Map.of("schemaVersion", "finance-cashflow-facts.v0.1", "section", "SUMMARY", "period", "2026-09",
                "currency", "CNY", "coverage", "OWN_BANK_ACCOUNT_STATEMENT", "totals", Map.of(),
                "categories", List.of(), "trend", List.of(), "gaps", List.of("TOTALS_NOT_RETURNED", "SOURCE_TIME_NOT_RETURNED"),
                "source", Map.of("namespace", "fixture", "subjectRef", "subject", "system", "fixture", "recordVersion", "v1",
                        "observedAt", "2026-10-01T00:00:00Z", "policyRef", "fixture", "policyAsOf", "2026-09-01T00:00:00Z"));
    }
    CashflowQueryGuard guard(boolean revokeAfter, boolean extraNested, boolean failAudit) {
        var provider = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() {
                return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, "fixture", "1.0.0", List.of(
                        new ProviderDescriptorV1.CapabilitySupport(CashflowQueryGuard.CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
            }
            public CapabilityResultV1 query(CapabilityRequestV1 r) {
                reads.incrementAndGet(); if (revokeAfter) revoked.set(true);
                var data = new HashMap<>(facts()); if (extraNested) data.put("secret", "MUST_NOT_LEAK");
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, "request", "trace", CapabilityResultV1.Status.COMPLETED,
                        "UNTRUSTED_PROSE", Map.of("facts", data, "secret", "MUST_NOT_LEAK"), List.of(), List.of(), "fixture", Instant.now(), null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 r) { throw new AssertionError(); }
        };
        return new CashflowQueryGuard(provider, this::authority, event -> { events.add(event);
            return !failAudit && !(rejectReturnAudit && event.get("stage").equals("RETURN")); }, binding);
    }
    @Test void validResultIsProjectedAndPoliciesAreAssociated() {
        var result = guard(false, false, false).query(request("subject", Map.of("period", "2026-09")));
        assertEquals(Set.of("facts", "description"), result.data().keySet());
        assertFalse(result.toString().contains("MUST_NOT_LEAK")); assertFalse(result.content().contains("UNTRUSTED"));
        assertEquals(1, reads.get()); assertEquals(List.of("ENTRY", "RETURN", "PROJECTION"), events.stream().map(e -> e.get("stage")).toList());
        assertEquals(policy, events.get(0).get("policyDigest")); assertEquals(binding, events.get(0).get("bindingDigest"));
        assertFalse(events.toString().contains("session="));
    }
    @Test void wrongSubjectAndInvalidPeriodNeverQuerySource() {
        var guard = guard(false, false, false);
        assertThrows(SecurityException.class, () -> guard.query(request("other", Map.of("period", "2026-09"))));
        assertThrows(SecurityException.class, () -> guard.query(request("subject", Map.of("period", "2026-99"))));
        assertThrows(SecurityException.class, () -> guard.query(request("subject", Map.of("period", "2026-09", "customerRef", "other"))));
        assertEquals(0, reads.get()); assertTrue(events.stream().allMatch(e -> e.get("decision").equals("DENY")));
    }
    @Test void revocationBeforeCallRejectsWithoutQuery() {
        revoked.set(true);
        assertThrows(SecurityException.class, () -> guard(false,false,false).query(request("subject", Map.of("period", "2026-09"))));
        assertEquals(0, reads.get()); assertEquals(policy, events.get(0).get("policyDigest"));
    }
    @Test void revocationDuringQuerySuppressesLateResult() {
        assertThrows(SecurityException.class, () -> guard(true,false,false).query(request("subject", Map.of("period", "2026-09"))));
        assertEquals(1, reads.get()); assertEquals("RETURN", events.getLast().get("stage")); assertEquals("DENY", events.getLast().get("decision"));
    }
    @Test void nestedUnknownFieldsAreRejectedNotReturned() {
        assertThrows(SecurityException.class, () -> guard(false,true,false).query(request("subject", Map.of("period", "2026-09"))));
        assertEquals(1, reads.get()); assertEquals("RESULT_CONTRACT_REJECTED", events.getLast().get("reason"));
        assertFalse(events.toString().contains("MUST_NOT_LEAK"));
    }
    @Test void auditFailurePreventsSourceCall() {
        var ex = assertThrows(SecurityException.class, () -> guard(false,false,true).query(request("subject", Map.of("period", "2026-09"))));
        assertEquals("AUTHORIZATION_AUDIT_UNAVAILABLE", ex.getMessage()); assertEquals(0, reads.get());
    }
    @Test void expiredOrInsufficientGrantNeverQueries() {
        expired=true;
        assertThrows(SecurityException.class, () -> guard(false,false,false).query(request("subject",Map.of("period","2026-09"))));
        expired=false; grantedScopes=Set.of();
        assertThrows(SecurityException.class, () -> guard(false,false,false).query(request("subject",Map.of("period","2026-09"))));
        assertEquals(0,reads.get());
    }
    @Test void returnAuditFailureSuppressesResultWithoutAnotherQuery() {
        rejectReturnAudit=true;
        var ex=assertThrows(SecurityException.class, () -> guard(false,false,false).query(request("subject",Map.of("period","2026-09"))));
        assertEquals("AUTHORIZATION_AUDIT_UNAVAILABLE",ex.getMessage()); assertEquals(1,reads.get());
    }
}
