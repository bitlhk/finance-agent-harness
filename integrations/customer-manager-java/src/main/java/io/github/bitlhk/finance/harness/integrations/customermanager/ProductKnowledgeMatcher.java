package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Host-side deterministic match against the product identity frozen by Builder. */
final class ProductKnowledgeMatcher {
    enum Decision { MATCHED, NO_KNOWLEDGE, AMBIGUOUS }
    private final JsonNode binding;

    ProductKnowledgeMatcher(JsonNode descriptor) {
        binding = descriptor.path("businessBinding").deepCopy();
        if (!"knowledge-product-release.v0.2".equals(descriptor.path("schemaVersion").asText())
                || !"product-knowledge-binding.v0.1".equals(binding.path("schemaVersion").asText())
                || !"PRODUCT_SPECIFIC".equals(binding.path("knowledgeKind").asText())
                || !binding.path("distribution").isArray() || binding.path("distribution").isEmpty())
            throw new SecurityException("KNOWLEDGE_BUSINESS_BINDING_INVALID");
    }

    Decision select(JsonNode products) {
        if (!products.isArray()) throw new SecurityException("PRODUCT_IDENTITY_SOURCE_INVALID");
        if (products.size() > 1) return Decision.AMBIGUOUS;
        int matches = 0;
        boolean conflict = false;
        for (JsonNode product : products) {
            JsonNode actual = product.path("businessIdentity");
            if (!actual.isObject()) continue;
            boolean sameCode = text(actual,"managerProductCode").equals(text(binding.path("product"),"managerProductCode"));
            boolean sameRegistration = text(actual,"registrationCode").equals(text(binding.path("product"),"registrationCode"));
            if (!sameCode && !sameRegistration) continue;
            boolean manager = text(actual,"managerId").equals(text(binding.path("manager"),"id"));
            boolean distribution = false;
            for (JsonNode allowed : binding.path("distribution")) {
                distribution |= text(actual,"distributorId").equals(text(allowed,"distributorId"))
                        && contains(allowed.path("salesCodes"), text(actual,"salesCode"))
                        && contains(allowed.path("shareClasses"), text(actual,"shareClass"));
            }
            if (sameCode && sameRegistration && manager && distribution) matches++;
            else conflict = true;
        }
        if (conflict || matches > 1) throw new SecurityException("KNOWLEDGE_PRODUCT_IDENTITY_MISMATCH");
        return matches == 1 ? Decision.MATCHED : Decision.NO_KNOWLEDGE;
    }

    private static boolean contains(JsonNode values, String expected) {
        if (!values.isArray() || expected.isBlank()) return false;
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }
    private static String text(JsonNode value, String key) {
        return value.path(key).isTextual() ? value.path(key).asText() : "";
    }
}
