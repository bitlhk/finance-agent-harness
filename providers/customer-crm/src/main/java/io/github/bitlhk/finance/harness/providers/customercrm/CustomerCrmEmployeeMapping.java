package io.github.bitlhk.finance.harness.providers.customercrm;

import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Reloadable deployment-side owner mapping. The file contains no CRM service credential. */
public record CustomerCrmEmployeeMapping(CustomerCrmAuthority authority, Map<String,String> customers) {
    private static final ObjectMapper JSON = new ObjectMapper();
    public CustomerCrmEmployeeMapping {
        Objects.requireNonNull(authority); customers = Map.copyOf(Objects.requireNonNull(customers));
        if (customers.isEmpty() || !authority.allowedCustomerRefs().containsAll(customers.values()))
            throw new IllegalArgumentException("CRM_EMPLOYEE_MAPPING_INVALID");
    }
    public static CustomerCrmEmployeeMapping load(Path file, String ownerRef) {
        try {
            Path resolved=file.toAbsolutePath();
            if(!Files.isRegularFile(resolved)||Files.isSymbolicLink(resolved)||Files.size(resolved)>128*1024)
                throw new SecurityException("CRM_EMPLOYEE_MAPPING_FILE_REJECTED");
            JsonNode root=JSON.readTree(Files.readAllBytes(resolved));
            if(!"customer-crm-employee-mapping.v1".equals(root.path("schemaVersion").asText()))
                throw new IllegalArgumentException("CRM_EMPLOYEE_MAPPING_SCHEMA_INVALID");
            JsonNode match=null;
            for(JsonNode value:root.path("employees"))if(ownerRef.equals(value.path("ownerRef").asText())) {
                if(match!=null)throw new SecurityException("CRM_EMPLOYEE_MAPPING_AMBIGUOUS");match=value;
            }
            if(match==null)throw new SecurityException("CRM_EMPLOYEE_MAPPING_MISSING");
            Map<String,String> customers=new LinkedHashMap<>();
            for(JsonNode value:match.path("customers")) {
                String alias=value.path("displayName").asText().trim(), ref=value.path("customerRef").asText().trim();
                if(alias.isBlank()||ref.isBlank()||customers.putIfAbsent(alias,ref)!=null)
                    throw new IllegalArgumentException("CRM_EMPLOYEE_CUSTOMER_MAPPING_INVALID");
            }
            var authority=new CustomerCrmAuthority(ownerRef,match.path("principalRef").asText(),
                root.path("revision").asText(),new HashSet<>(customers.values()),
                Instant.parse(match.path("expiresAt").asText()),
                FinanceContextV1.AuthenticationLevel.valueOf(match.path("authenticationLevel").asText()));
            return new CustomerCrmEmployeeMapping(authority,customers);
        } catch(RuntimeException failure) { throw failure; }
        catch(Exception failure) { throw new IllegalStateException("CRM_EMPLOYEE_MAPPING_LOAD_FAILED",failure); }
    }
}
