package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import io.github.bitlhk.finance.harness.providers.kyp.KypProductFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KypPreparationAccessTest {
    final Instant now = Instant.now();
    final AtomicReference<BoundFinanceProvider.Grant> grant = new AtomicReference<>(new BoundFinanceProvider.Grant(
            "employee-fixture", FinanceContextV1.PrincipalType.EMPLOYEE, Set.of("customer-fixture"),
            Set.of("WEALTH_PRODUCT_REVIEW"), Set.of(KypProductFactory.SCOPE), "session-fixture", "kyp-grant-1", now.plusSeconds(600)));
    final Crm crm = new Crm();
    final Products products = new Products();
    KypPreparationAccess access() {
        return new KypPreparationAccess(crm, products, "session-fixture", Clock.fixed(now, ZoneOffset.UTC), grant::get);
    }
    CapabilityResultV1 query() {
        return access().query("request-fixture", "trace-fixture", "customer-fixture",
                CustomerCrmProvider.ALLOCATION_CAPABILITY, Map.of("amount", 100000, "horizonMonths", 12, "channel", "branch"));
    }
    CapabilityResultV1 result(String request, String trace, Map<String,Object> data, List<String> missing) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request, trace,
                CapabilityResultV1.Status.COMPLETED, "fixture", data, missing, List.of(), "fixture-source", now, null, null);
    }
    class Crm implements PreparationAccess {
        boolean receipt = true;
        public Snapshot authorize(String customer) {
            if (!"customer-fixture".equals(customer)) throw new SecurityException("CRM_SCOPE_REJECTED");
            return new Snapshot("crm-grant-1", "employee-fixture", FinanceContextV1.AuthenticationLevel.STRONG);
        }
        public String providerId() { return "customer-crm-readonly"; }
        public void close() {}
        public CapabilityResultV1 query(String request, String trace, String customer, String capability, Map<String,Object> args) {
            var data = new LinkedHashMap<String,Object>();
            data.put("previsitContext", Map.of("customer", Map.of("customerId", customer), "profiles", List.of(
                    Map.of("profileType", "wealth", "detail", Map.of("riskAssessment",
                            Map.of("riskLevel", "C3", "validUntil", now.plusSeconds(3600).toString()))))));
            if(receipt) data.put("hostEvidence", Map.of("receiptId", "receipt-fixture"));
            return result(request, trace, data, List.of());
        }
    }
    class Products implements FinanceProvider, InvocationAccess {
        int calls;
        boolean revoke, mismatch, brokenChannels;
        public void checkAccess(CapabilityRequestV1 request) { BoundFinanceProvider.checkContext(grant.get(), request.context()); }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new SecurityException("READ_ONLY"); }
        public CapabilityResultV1 query(CapabilityRequestV1 request) {
            calls++;
            assertTrue(request.parameters().isEmpty(), "CRM facts must not be sent as product query parameters");
            var row = new LinkedHashMap<String,Object>();
            row.put("productId", "P001"); row.put("name", "Demo fixture"); row.put("sourceId", "demo-fixture");
            row.put("salesTrust", "DEMO"); row.put("factsType", "demo"); row.put("riskLevel", 2);
            row.put("minimumAmount", 5000); row.put("termMonths", 6); row.put("salesStatus", "on_sale");
            row.put("issues", List.of("BANK_SALES_NOT_VERIFIED")); row.put("readyForScreening", false);
            if(!brokenChannels) row.put("channels", List.of("branch"));
            if(revoke) grant.set(null);
            return result(mismatch ? "another-request" : request.context().requestId(), request.context().traceId(),
                    Map.of("schema", "kyp-product-context.v1", "sourceId", "demo-fixture",
                            "normalizedContentDigest", "a".repeat(64), "coverage", "RETURNED_SEARCH_RESULTS_ONLY",
                            "products", List.of(row)), List.of("BANK_SALES_NOT_VERIFIED"));
        }
    }
    @Test void demoProductsRemainPartialAndNeverEligible() {
        var result=query();
        assertEquals("partial", result.data().get("status"));
        assertEquals(List.of(), result.data().get("eligibleProducts"));
        assertEquals(1, ((List<?>)result.data().get("excludedProducts")).size());
        assertNotNull(result.data().get("sourceEvidence"));
    }
    @Test void crmGrantDoesNotGrantProductAccess() {
        grant.set(null);
        assertThrows(SecurityException.class, this::query);
        assertEquals(0, products.calls);
    }
    @Test void revokedProductGrantCannotReleaseReturnedFacts() {
        products.revoke=true;
        assertThrows(SecurityException.class, this::query);
        assertEquals(1, products.calls);
    }
    @Test void responseMustBelongToCurrentRequest() {
        products.mismatch=true;
        assertEquals("KYP_RESULT_BINDING_MISMATCH", assertThrows(SecurityException.class, this::query).getMessage());
    }
    @Test void malformedContractFailsWithSpecificCodeNotNullPointer() {
        products.brokenChannels=true;
        assertEquals("ALLOCATION_PRODUCT_CONTRACT_INVALID", assertThrows(IllegalStateException.class, this::query).getMessage());
    }
    @Test void missingCrmReceiptCannotBeSilentlyDropped() {
        crm.receipt=false;
        assertEquals("CRM_SOURCE_RECEIPT_MISSING", assertThrows(IllegalStateException.class, this::query).getMessage());
    }
    @Test void otherCustomerIsRejectedBeforeProductCall() {
        assertThrows(SecurityException.class, () -> access().query("request", "trace", "another-customer",
                CustomerCrmProvider.ALLOCATION_CAPABILITY, Map.of("amount", 100, "horizonMonths", 12, "channel", "branch")));
        assertEquals(0, products.calls);
    }
}
