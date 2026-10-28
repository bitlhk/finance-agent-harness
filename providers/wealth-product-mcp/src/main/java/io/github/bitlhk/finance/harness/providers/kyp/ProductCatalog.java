package io.github.bitlhk.finance.harness.providers.kyp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Product facts are distinct from bank sales eligibility; source assertions are not grants. */
public record ProductCatalog(List<Product> products, Instant retrievedAt, String contentDigest) {
    public ProductCatalog { products = List.copyOf(products); }

    public enum SalesTrust { DEMO, UNVERIFIED, VERIFIED }

    /** Provisioned by the Host, never read from tool output or model arguments. */
    public record SourcePolicy(String sourceId, SalesTrust salesTrust, Duration maximumAge) {
        public SourcePolicy {
            if (sourceId == null || !sourceId.matches("[a-z][a-z0-9-]{0,63}") || salesTrust == null
                    || maximumAge == null || maximumAge.isNegative() || maximumAge.isZero())
                throw new IllegalArgumentException("KYP_SOURCE_POLICY_INVALID");
        }
    }

    public record Product(String id, String name, String sourceId, String factsType,
            Instant factsAsOf, Instant salesAsOf, SalesTrust salesTrust, String salesStatus,
            int riskLevel, BigDecimal minimumAmount, Integer termMonths, Set<String> channels,
            List<String> issues) {
        public Product { channels = Set.copyOf(channels); issues = List.copyOf(issues); }
        public boolean eligibleForScreening() { return issues.isEmpty(); }
    }

    /** Nested details and reviewed search summaries retain distinct completeness levels. */
    public static ProductCatalog parse(byte[] bytes, SourcePolicy policy, Instant now) {
        Objects.requireNonNull(policy); Objects.requireNonNull(now);
        if (bytes == null || bytes.length == 0 || bytes.length > 262144)
            throw new IllegalArgumentException("KYP_RESPONSE_SIZE_INVALID");
        try {
            var mapper = HarnessJson.mapper().copy()
                    .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode payload = mapper.readTree(bytes);
            JsonNode data = payload.path("data");
            if (!data.isObject() || (data.has("products") == data.has("items")))
                throw new IllegalArgumentException("KYP_ENVELOPE_UNSUPPORTED");
            JsonNode rows = data.has("products") ? data.get("products") : data.get("items");
            if (!rows.isArray() || rows.size() > 50 || data.path("hasMore").asBoolean(false)
                    || data.hasNonNull("nextCursor"))
                throw new IllegalArgumentException("KYP_RESULT_INCOMPLETE_OR_OVERSIZED");
            if (payload.has("ok") && !payload.path("ok").asBoolean(false))
                throw new IllegalArgumentException("KYP_SOURCE_REJECTED");
            if (data.has("pagination")) {
                JsonNode page = data.path("pagination");
                if (!page.isObject() || !page.path("page").isIntegralNumber() || page.path("page").asInt() != 1
                        || !page.path("totalPages").isIntegralNumber() || page.path("totalPages").asInt() != 1
                        || !page.path("total").isIntegralNumber() || !page.path("total").canConvertToInt()
                        || page.path("total").asInt() != rows.size())
                    throw new IllegalArgumentException("KYP_RESULT_INCOMPLETE_OR_OVERSIZED");
            }
            var products = new ArrayList<Product>();
            var ids = new HashSet<String>();
            for (JsonNode row : rows) {
                String id = text(row, "productId");
                if (id.isBlank() || id.length() > 128 || !ids.add(id))
                    throw new IllegalArgumentException("KYP_PRODUCT_ID_INVALID");
                var issues = new ArrayList<String>();
                JsonNode facts = row.path("productFacts"), sales = row.path("salesEligibility");
                boolean summary = !row.has("productFacts") && !row.has("salesEligibility") && data.has("items");
                if (summary) {
                    // Search fields are useful facts, never proof of bank sales eligibility.
                    facts = row;
                    issues.add("PRODUCT_SEARCH_SUMMARY_ONLY");
                }
                if (!facts.isObject()) issues.add("PRODUCT_FACTS_MISSING");
                if (!sales.isObject()) issues.add("BANK_SALES_ELIGIBILITY_MISSING");
                String type = text(row, "dataSourceType"), name = text(facts, "name");
                if (name.isBlank() || name.length() > 240) issues.add("PRODUCT_NAME_INVALID");
                if (!Set.of("demo", "public", "bank").contains(type)) issues.add("PRODUCT_FACTS_TYPE_UNKNOWN");
                if (type.equals("demo")) issues.add("DEMO_PRODUCT_FACTS");
                Instant factsTime = timestamp(facts, "asOf", now, policy.maximumAge(), issues, "FACTS");
                Instant salesTime = timestamp(sales, "asOf", now, policy.maximumAge(), issues, "SALES");
                if (policy.salesTrust() != SalesTrust.VERIFIED) issues.add("BANK_SALES_NOT_VERIFIED");
                String status = text(sales, "salesStatus");
                if (!status.equals("on_sale")) issues.add("PRODUCT_NOT_SELLABLE");
                int risk = risk(text(facts, "riskLevel"));
                if (risk == 0) issues.add("PRODUCT_RISK_MISSING");
                BigDecimal minimum = facts.path("minPurchaseAmount").isNumber()
                        ? facts.path("minPurchaseAmount").decimalValue() : null;
                if (minimum == null || minimum.signum() < 0) issues.add("MINIMUM_AMOUNT_INVALID");
                Integer months = facts.path("termMonths").isIntegralNumber() && facts.path("termMonths").canConvertToInt()
                        ? facts.path("termMonths").intValue() : null;
                // Days are not silently rounded to months; that requires an explicit business convention.
                if (months == null || months < 0) issues.add("PRODUCT_TERM_MONTHS_UNAVAILABLE");
                var channels = new LinkedHashSet<String>();
                if (sales.path("channels").isArray()) for (JsonNode channel : sales.path("channels")) {
                    if (!channel.isTextual() || channel.asText().isBlank()) issues.add("SALES_CHANNEL_INVALID");
                    else channels.add(channel.asText());
                }
                if (channels.isEmpty()) issues.add("SALES_CHANNEL_MISSING");
                products.add(new Product(id, name, policy.sourceId(), type, factsTime, salesTime,
                        policy.salesTrust(), status, risk, minimum, months, channels, issues));
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ProductCatalog(products, now, digest);
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("KYP_RESPONSE_INVALID"); }
    }

    private static int risk(String value) {
        return value.matches("R[1-5]") ? value.charAt(1) - '0' : 0;
    }
    private static String text(JsonNode node, String key) {
        return node.path(key).isTextual() ? node.path(key).asText().trim() : "";
    }
    private static Instant timestamp(JsonNode node, String key, Instant now, Duration age,
            List<String> issues, String category) {
        try {
            Instant value = Instant.parse(text(node, key));
            if (value.isAfter(now) || value.isBefore(now.minus(age))) issues.add(category + "_DATE_OUTSIDE_POLICY");
            return value;
        } catch (Exception failure) { issues.add(category + "_BUSINESS_DATE_MISSING_OR_INVALID"); return null; }
    }
}
