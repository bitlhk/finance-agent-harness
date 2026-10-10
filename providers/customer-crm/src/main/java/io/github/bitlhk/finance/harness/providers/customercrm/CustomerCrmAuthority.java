package io.github.bitlhk.finance.harness.providers.customercrm;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Deployment-owned employee mapping. It is never accepted from a model or browser request. */
public record CustomerCrmAuthority(String ownerRef, String principalRef, String revision,
        Set<String> allowedCustomerRefs, Instant expiresAt,
        FinanceContextV1.AuthenticationLevel authenticationLevel) {
    public CustomerCrmAuthority {
        ownerRef = required(ownerRef); principalRef = required(principalRef); revision = required(revision);
        allowedCustomerRefs = Set.copyOf(Objects.requireNonNull(allowedCustomerRefs));
        if (allowedCustomerRefs.isEmpty() || expiresAt == null || authenticationLevel == null
                || authenticationLevel == FinanceContextV1.AuthenticationLevel.UNKNOWN) {
            throw new IllegalArgumentException("CRM_EMPLOYEE_MAPPING_INVALID");
        }
    }
    public void requireCurrent(String expectedOwner, String customerRef, Instant now) {
        if (!ownerRef.equals(expectedOwner) || !allowedCustomerRefs.contains(required(customerRef))
                || !expiresAt.isAfter(now)) throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
    }
    public String authorityRef() { return "crm-scope:" + revision; }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CRM_REFERENCE_REQUIRED");
        return value.trim();
    }
}
