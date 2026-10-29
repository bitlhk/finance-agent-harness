package io.github.bitlhk.finance.harness.providers.kyp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductCatalogTest {
    final Instant now = Instant.parse("2026-10-28T00:00:00Z");
    final ProductCatalog.SourcePolicy verified = new ProductCatalog.SourcePolicy(
            "controlled-product-fixture", ProductCatalog.SalesTrust.VERIFIED, Duration.ofDays(1));
    Map<String,Object> row() {
        return new LinkedHashMap<>(Map.of("productId", "P001", "dataSourceType", "public",
                "productFacts", new LinkedHashMap<>(Map.of("name", "Fixture fund", "riskLevel", "R2",
                        "minPurchaseAmount", 1000, "termMonths", 6, "asOf", now.toString())),
                "salesEligibility", new LinkedHashMap<>(Map.of("salesStatus", "on_sale", "channels", List.of("branch"),
                        "asOf", now.toString()))));
    }
    byte[] body(Map<String,Object> row) throws Exception {
        return HarnessJson.mapper().writeValueAsBytes(Map.of("updatedAt", now.toString(), "data", Map.of("products", List.of(row))));
    }
    @SuppressWarnings("unchecked") Map<String,Object> part(Map<String,Object> row, String key) { return (Map<String,Object>)row.get(key); }

    @Test void separatesPublicFactsFromBankSalesTrustAndPreservesDates() throws Exception {
        var catalog = ProductCatalog.parse(body(row()), verified, now);
        assertTrue(catalog.products().getFirst().eligibleForScreening());
        assertEquals(now, catalog.products().getFirst().factsAsOf());
        assertEquals(now, catalog.products().getFirst().salesAsOf());
        assertEquals(64, catalog.contentDigest().length());
        var shadow = new ProductCatalog.SourcePolicy("fixture", ProductCatalog.SalesTrust.DEMO, Duration.ofDays(1));
        assertTrue(ProductCatalog.parse(body(row()), shadow, now).products().getFirst().issues().contains("BANK_SALES_NOT_VERIFIED"));
    }
    @Test void updatedAtCannotSubstituteForMissingBusinessTime() throws Exception {
        var row = row(); part(row, "productFacts").remove("asOf");
        assertTrue(ProductCatalog.parse(body(row), verified, now).products().getFirst().issues().contains("FACTS_BUSINESS_DATE_MISSING_OR_INVALID"));
    }
    @Test void sourceClaimCannotPromoteDemoSalesToTrusted() throws Exception {
        var row = row(); row.put("salesTrust", "VERIFIED");
        var policy = new ProductCatalog.SourcePolicy("fixture", ProductCatalog.SalesTrust.UNVERIFIED, Duration.ofDays(1));
        assertFalse(ProductCatalog.parse(body(row), policy, now).products().getFirst().eligibleForScreening());
    }
    @Test void incompleteSalesCannotBecomeASellableCandidate() throws Exception {
        var row = row(); row.remove("salesEligibility"); row.put("salesStatus", "on_sale");
        assertTrue(ProductCatalog.parse(body(row), verified, now).products().getFirst().issues().contains("BANK_SALES_ELIGIBILITY_MISSING"));
    }
    @Test void missingMinimumAndDayOnlyTermAreNotZeroOrRoundedMonths() throws Exception {
        var row = row(); part(row, "productFacts").remove("minPurchaseAmount");
        part(row, "productFacts").remove("termMonths"); part(row, "productFacts").put("termDays", 31);
        var product = ProductCatalog.parse(body(row), verified, now).products().getFirst();
        assertNull(product.minimumAmount()); assertNull(product.termMonths());
        assertFalse(product.eligibleForScreening());
    }
    @Test void staleFutureAndDateOnlyValuesAreNotSilentlyAccepted() throws Exception {
        for (String value : List.of("2026-08-27T00:00:00Z", "2027-01-01T00:00:00Z", "2026-10-28")) {
            var row = row(); part(row, "salesEligibility").put("asOf", value);
            assertFalse(ProductCatalog.parse(body(row), verified, now).products().getFirst().eligibleForScreening());
        }
    }
    @Test void demoFactsStayDemoEvenWithVerifiedSales() throws Exception {
        var row = row(); row.put("dataSourceType", "demo");
        assertTrue(ProductCatalog.parse(body(row), verified, now).products().getFirst().issues().contains("DEMO_PRODUCT_FACTS"));
    }
    @Test void duplicateIdsUnknownEnvelopesAndOversizedPayloadReject() throws Exception {
        var duplicate = HarnessJson.mapper().writeValueAsBytes(Map.of("data", Map.of("products", List.of(row(), row()))));
        assertThrows(IllegalArgumentException.class, () -> ProductCatalog.parse(duplicate, verified, now));
        assertThrows(IllegalArgumentException.class, () -> ProductCatalog.parse("{}".getBytes(), verified, now));
        assertThrows(IllegalArgumentException.class, () -> ProductCatalog.parse(new byte[262145], verified, now));
    }

    @Test void actualSearchShapePreservesFactsWithoutInventingEligibilityOrDates() throws Exception {
        var item = Map.of("productId", "P001", "name", "Demo product", "riskLevel", "R2",
                "minPurchaseAmount", 5000, "dataSourceType", "demo", "salesStatus", "on_sale");
        var bytes = HarnessJson.mapper().writeValueAsBytes(Map.of("ok", true, "asOf", "2026-08-20",
                "updatedAt", now.toString(), "data", Map.of("items", List.of(item),
                        "pagination", Map.of("page", 1, "totalPages", 1, "total", 1))));
        var product = ProductCatalog.parse(bytes, verified, now).products().getFirst();
        assertEquals("Demo product", product.name());
        assertEquals(2, product.riskLevel());
        assertEquals(0, new java.math.BigDecimal("5000").compareTo(product.minimumAmount()));
        assertNull(product.factsAsOf()); assertNull(product.salesAsOf());
        assertTrue(product.issues().contains("PRODUCT_SEARCH_SUMMARY_ONLY"));
        assertTrue(product.issues().contains("BANK_SALES_ELIGIBILITY_MISSING"));
        assertFalse(product.eligibleForScreening());
    }

    @Test void paginationAndSourceFailureCannotLookLikeCompleteCatalog() throws Exception {
        for (var pagination : List.of(Map.of("page", 1, "totalPages", 2, "total", 51),
                Map.of("page", 2, "totalPages", 2, "total", 2),
                Map.of("page", 1, "totalPages", 1, "total", 2))) {
            var bytes = HarnessJson.mapper().writeValueAsBytes(Map.of("data",
                    Map.of("items", List.of(row()), "pagination", pagination)));
            assertThrows(IllegalArgumentException.class, () -> ProductCatalog.parse(bytes, verified, now));
        }
        var failed = HarnessJson.mapper().writeValueAsBytes(Map.of("ok", false, "data", Map.of("items", List.of())));
        assertThrows(IllegalArgumentException.class, () -> ProductCatalog.parse(failed, verified, now));
    }
}
