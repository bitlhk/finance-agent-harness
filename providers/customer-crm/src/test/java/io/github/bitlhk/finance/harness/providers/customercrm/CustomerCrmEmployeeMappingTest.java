package io.github.bitlhk.finance.harness.providers.customercrm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
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
}
