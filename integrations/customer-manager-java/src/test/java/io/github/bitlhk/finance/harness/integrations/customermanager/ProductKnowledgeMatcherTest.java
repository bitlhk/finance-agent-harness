package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ProductKnowledgeMatcherTest {
    private static ProductKnowledgeMatcher matcher() throws Exception {
        JsonNode descriptor = HarnessJson.mapper().readTree("""
            {"schemaVersion":"knowledge-product-release.v0.2","businessBinding":{
              "schemaVersion":"product-knowledge-binding.v0.1","knowledgeKind":"PRODUCT_SPECIFIC",
              "manager":{"id":"cmb-wealth-management","name":"招银理财有限责任公司"},
              "product":{"managerProductCode":"132112","registrationCode":"Z7001626000062"},
              "distribution":[{"distributorId":"bank-of-nanjing","distributorName":"南京银行",
                "salesCodes":["132112N"],"shareClasses":["N"],"channels":["BRANCH"]}],
              "documents":[],"clauseApplicability":{}}}
            """);
        return new ProductKnowledgeMatcher(descriptor);
    }
    private static JsonNode products(String manager, String code, String registration,
            String distributor, String salesCode, String share) throws Exception {
        return HarnessJson.mapper().readTree("""
            [{"productId":"provider-product","businessIdentity":{
              "managerId":"%s","managerProductCode":"%s","registrationCode":"%s",
              "distributorId":"%s","salesCode":"%s","shareClass":"%s"}}]
            """.formatted(manager, code, registration, distributor, salesCode, share));
    }

    @Test void exact_provider_identity_matches_locked_knowledge() throws Exception {
        assertEquals(ProductKnowledgeMatcher.Decision.MATCHED, matcher().select(products(
                "cmb-wealth-management","132112","Z7001626000062","bank-of-nanjing","132112N","N"), "BRANCH"));
    }

    @Test void unrelated_mock_product_has_no_knowledge() throws Exception {
        assertEquals(ProductKnowledgeMatcher.Decision.NO_KNOWLEDGE,
                matcher().select(HarnessJson.mapper().readTree("[{\"productId\":\"SYN-FI-002\"}]"), "BRANCH"));
    }

    @Test void same_code_wrong_manager_or_share_is_rejected() throws Exception {
        assertThrows(SecurityException.class, () -> matcher().select(products(
                "other-manager","132112","Z7001626000062","bank-of-nanjing","132112N","N"), "BRANCH"));
        assertThrows(SecurityException.class, () -> matcher().select(products(
                "cmb-wealth-management","132112","Z7001626000062","bank-of-nanjing","132112D","D"), "BRANCH"));
        assertThrows(SecurityException.class, () -> matcher().select(products(
                "cmb-wealth-management","132112","Z7001626000062","bank-of-nanjing","132112N","N"), "MOBILE_BANKING"));
    }

    @Test void multiple_products_are_ambiguous_even_when_one_matches() throws Exception {
        JsonNode exact = products("cmb-wealth-management","132112","Z7001626000062",
                "bank-of-nanjing","132112N","N").get(0);
        var rows = HarnessJson.mapper().createArrayNode();
        rows.add(exact); rows.addObject().put("productId","SYN-FI-002");
        assertEquals(ProductKnowledgeMatcher.Decision.AMBIGUOUS, matcher().select(rows, "BRANCH"));
    }
}
