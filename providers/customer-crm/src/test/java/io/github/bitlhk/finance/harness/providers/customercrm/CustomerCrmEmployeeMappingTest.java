package io.github.bitlhk.finance.harness.providers.customercrm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class CustomerCrmEmployeeMappingTest {
    @TempDir Path files;
    @Test void loadsOnlyTheExplicitOwnerAndContainsNoCredential() throws Exception {
        Path mapping=files.resolve("mapping.json");
        Files.writeString(mapping,"""
            {"schemaVersion":"customer-crm-employee-mapping.v1","revision":"scope-v1","employees":[
              {"ownerRef":"owner-1","principalRef":"employee-1","expiresAt":"2027-01-01T00:00:00Z",
               "authenticationLevel":"BASIC","customers":[{"customerRef":"CUST-000001","displayName":"合成客户甲"}]}
            ]}
            """);
        var value=CustomerCrmEmployeeMapping.load(mapping,"owner-1");
        assertEquals("employee-1",value.authority().principalRef());
        assertEquals("CUST-000001",value.customers().get("合成客户甲"));
        assertFalse(Files.readString(mapping).contains("apiKey"));
        assertThrows(SecurityException.class,()->CustomerCrmEmployeeMapping.load(mapping,"admin"));
    }

    @Test void expiredAuthorityHasAStableActionableError() {
        var authority = new CustomerCrmAuthority("owner-1", "employee-1", "scope-v1",
                java.util.Set.of("CUST-000001"), Instant.parse("2026-01-01T00:00:00Z"),
                io.github.bitlhk.finance.harness.core.FinanceContextV1.AuthenticationLevel.BASIC);
        var error = assertThrows(SecurityException.class, () -> authority.requireCurrent(
                "owner-1", "CUST-000001", Instant.parse("2026-01-02T00:00:00Z")));
        assertEquals("CRM_EMPLOYEE_SCOPE_EXPIRED", error.getMessage());
    }

    @Test void expiredAuthorityDoesNotRevealItsScopeToAnotherOwner() {
        var authority = new CustomerCrmAuthority("owner-1", "employee-1", "scope-v1",
                java.util.Set.of("CUST-000001"), Instant.parse("2026-01-01T00:00:00Z"),
                io.github.bitlhk.finance.harness.core.FinanceContextV1.AuthenticationLevel.BASIC);
        var error = assertThrows(SecurityException.class, () -> authority.requireCurrent(
                "owner-2", "CUST-000001", Instant.parse("2026-01-02T00:00:00Z")));
        assertEquals("CRM_EMPLOYEE_SCOPE_REJECTED", error.getMessage());
    }
}
