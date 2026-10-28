package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.InvocationAccess;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import io.github.bitlhk.finance.harness.providers.kyp.KypProductFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Composes authorized customer facts and an independently authorized product source.
 * No fallback to the bundled catalog when a configured source fails.
 */
public final class KypPreparationAccess implements PreparationAccess {
    private final PreparationAccess crm;
    private final FinanceProvider products;
    private final String sessionRef;
    private final Clock clock;
    private final java.util.function.Supplier<BoundFinanceProvider.Grant> productGrants;
    private final AtomicBoolean closed = new AtomicBoolean();

    public KypPreparationAccess(PreparationAccess crm, FinanceProvider products, String sessionRef, Clock clock,
            java.util.function.Supplier<BoundFinanceProvider.Grant> productGrants) {
        this.crm = Objects.requireNonNull(crm); this.products = Objects.requireNonNull(products);
        this.clock = Objects.requireNonNull(clock);
        this.productGrants = Objects.requireNonNull(productGrants);
        if (!(products instanceof InvocationAccess)) throw new IllegalArgumentException("KYP_ACCESS_CHECK_REQUIRED");
        if (sessionRef == null || sessionRef.isBlank()) throw new IllegalArgumentException("HOST_REFERENCE_REQUIRED");
        this.sessionRef = sessionRef;
    }

    @Override public Snapshot authorize(String customer) {
        if (closed.get()) throw new SecurityException("CRM_SESSION_CLOSED");
        var snapshot = crm.authorize(customer);
        if (closed.get()) throw new SecurityException("CRM_SESSION_CLOSED");
        return snapshot;
    }
    @Override public String providerId() { return crm.providerId(); }
    @Override public void close() { closed.set(true); crm.close(); }

    @Override public CapabilityResultV1 query(String requestId, String traceId, String customer,
            String capability, Map<String,Object> arguments) {
        if (!CustomerCrmProvider.ALLOCATION_CAPABILITY.equals(capability)) {
            authorize(customer);
            return crm.query(requestId, traceId, customer, capability, arguments);
        }
        if (!arguments.keySet().equals(Set.of("amount", "horizonMonths", "channel")))
            throw new IllegalArgumentException("ALLOCATION_ARGUMENTS_INVALID");
        BigDecimal amount = positive(arguments.get("amount"), new BigDecimal("10000000000"));
        BigDecimal horizon = positive(arguments.get("horizonMonths"), new BigDecimal("1200"));
        if (!(arguments.get("channel") instanceof String channel) || channel.isBlank() || channel.length() > 64)
            throw new IllegalArgumentException("ALLOCATION_CHANNEL_REQUIRED");
        var before = authorize(customer);
        var facts = crm.query(requestId + "-crm", traceId, customer, CustomerCrmProvider.CAPABILITY, Map.of());
        if (facts.status() != CapabilityResultV1.Status.COMPLETED)
            throw new IllegalStateException("ALLOCATION_CRM_FACTS_UNAVAILABLE");
        var context = HarnessJson.mapper().valueToTree(facts.data()).path("previsitContext");
        if (!customer.equals(context.path("customer").path("customerId").asText()))
            throw new SecurityException("RESULT_CUSTOMER_MISMATCH");
        var wealth = new ArrayList<JsonNode>();
        for (var profile : context.path("profiles"))
            if ("wealth".equals(profile.path("profileType").asText())) wealth.add(profile);
        if (wealth.size() != 1) throw new IllegalStateException("CUSTOMER_SUITABILITY_CONTEXT_AMBIGUOUS");
        var assessment = wealth.getFirst().path("detail").path("riskAssessment");
        String level = assessment.path("riskLevel").asText(), until = assessment.path("validUntil").asText();
        if (!level.matches("C[1-5]") || !current(until))
            throw new IllegalStateException("CUSTOMER_SUITABILITY_CONTEXT_INVALID");
        int customerRisk = level.charAt(1) - '0';
        unchanged(customer, before);
        var sourceContext = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, requestId + "-kyp", traceId,
                before.principalRef(), FinanceContextV1.PrincipalType.EMPLOYEE, "finance-harness",
                "WEALTH_PRODUCT_REVIEW", customer, sessionRef, before.authenticationLevel(), Set.of(KypProductFactory.SCOPE));
        var productRequest = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                KypProductFactory.CAPABILITY, CapabilityRequestV1.Operation.QUERY, sourceContext,
                Map.of(), null, null, Map.of(), null);
        var productGrant = productGrants.get();
        productCurrent(productRequest, productGrant);
        var result = products.query(productRequest);
        productCurrent(productRequest, productGrant);
        unchanged(customer, before);
        if (!sourceContext.requestId().equals(result.requestId()) || !traceId.equals(result.traceId()))
            throw new SecurityException("KYP_RESULT_BINDING_MISMATCH");
        if (result.status() != CapabilityResultV1.Status.COMPLETED)
            throw new IllegalStateException("ALLOCATION_PRODUCT_SOURCE_UNAVAILABLE");
        var source = HarnessJson.mapper().valueToTree(result.data());
        if (!"kyp-product-context.v1".equals(source.path("schema").asText()) || !source.path("products").isArray()
                || source.path("sourceId").asText().isBlank()
                || !source.path("normalizedContentDigest").asText().matches("[a-f0-9]{64}"))
            throw new IllegalStateException("ALLOCATION_PRODUCT_CONTRACT_INVALID");
        var eligible = new ArrayList<Map<String,Object>>(); var excluded = new ArrayList<Map<String,Object>>();
        var missing = new TreeSet<String>(result.missingInputs());
        for (var product : source.path("products")) {
            if (!product.path("productId").isTextual() || product.path("productId").asText().isBlank()
                    || !product.path("channels").isArray())
                throw new IllegalStateException("ALLOCATION_PRODUCT_CONTRACT_INVALID");
            var reasons = new ArrayList<String>();
            if (!product.path("issues").isArray()) reasons.add("PRODUCT_SOURCE_ISSUES_UNAVAILABLE");
            else product.path("issues").forEach(issue -> reasons.add(issue.asText()));
            if (!"VERIFIED".equals(product.path("salesTrust").asText())) reasons.add("BANK_SALES_NOT_VERIFIED");
            if (!Set.of("public", "bank").contains(product.path("factsType").asText())) reasons.add("PRODUCT_FACTS_NOT_VERIFIED");
            if (!product.path("readyForScreening").asBoolean(false)) reasons.add("PRODUCT_NOT_READY_FOR_SCREENING");
            if (!"on_sale".equals(product.path("salesStatus").asText())) reasons.add("PRODUCT_NOT_SELLABLE");
            int risk = product.path("riskLevel").isIntegralNumber() && product.path("riskLevel").canConvertToInt()
                    ? product.path("riskLevel").intValue() : 0;
            if (risk < 1 || risk > 5 || risk > customerRisk) reasons.add("RISK_MISMATCH");
            if (!product.path("minimumAmount").isNumber() || product.path("minimumAmount").decimalValue().signum() < 0
                    || amount.compareTo(product.path("minimumAmount").decimalValue()) < 0) reasons.add("AMOUNT_BELOW_MINIMUM_OR_UNKNOWN");
            if (!product.path("termMonths").isIntegralNumber() || product.path("termMonths").asInt(-1) < 0
                    || horizon.compareTo(product.path("termMonths").decimalValue()) < 0) reasons.add("HORIZON_TOO_SHORT_OR_UNKNOWN");
            boolean allowedChannel = false;
            for (var value : product.path("channels")) allowedChannel |= channel.equals(value.asText());
            if (!allowedChannel) reasons.add("CHANNEL_NOT_ALLOWED");
            var item = new LinkedHashMap<String,Object>();
            for (String key : List.of("productId", "name", "sourceId", "factsAsOf", "salesAsOf", "salesTrust", "factsType"))
                if (product.hasNonNull(key)) item.put(key, HarnessJson.mapper().convertValue(product.get(key), Object.class));
            item.put("riskLevel", "R" + risk);
            item.put("termMonths", product.path("termMonths").asInt(-1));
            if (product.path("minimumAmount").isNumber()) item.put("minAmount", product.path("minimumAmount").decimalValue());
            item.put("channels", HarnessJson.mapper().convertValue(product.path("channels"), Object.class));
            if (reasons.isEmpty()) { item.put("policyCode", "WEALTH_SUITABILITY_MATCH:ELIGIBLE"); eligible.add(Map.copyOf(item)); }
            else { item.put("reasonCodes", List.copyOf(new LinkedHashSet<>(reasons))); excluded.add(Map.copyOf(item)); }
        }
        var data = new LinkedHashMap<String,Object>();
        data.put("schema", "harness.wealth-allocation-context.v1");
        data.put("status", !missing.isEmpty() ? "partial" : eligible.isEmpty() ? "no_eligible_products" : "ready");
        data.put("customerRef", customer); data.put("customerRiskLevel", level); data.put("assessmentValidUntil", until);
        data.put("request", Map.of("amount", amount, "horizonMonths", horizon, "channel", channel));
        data.put("eligibleProducts", List.copyOf(eligible)); data.put("excludedProducts", List.copyOf(excluded));
        data.put("sourceProducts", result.data().get("products"));
        data.put("eligibilityPolicy", Map.of("horizonRule", "PRODUCT_TERM_NOT_LONGER_THAN_CUSTOMER_HORIZON"));
        data.put("policySource", Map.of("ruleVersion", "wealth-suitability-v1", "sourceId", source.path("sourceId").asText(),
                "catalogDigest", source.path("normalizedContentDigest").asText(), "coverage", source.path("coverage").asText()));
        if (!(facts.data().get("hostEvidence") instanceof Map<?,?> receipt) || receipt.isEmpty())
            throw new IllegalStateException("CRM_SOURCE_RECEIPT_MISSING");
        data.put("sourceEvidence", Map.of("crm", receipt, "kyp", result.data()));
        data.put("productAuthorityBinding", grantDigest(productGrant));
        productCurrent(productRequest, productGrant);
        unchanged(customer, before);
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, requestId, traceId, CapabilityResultV1.Status.COMPLETED,
                "Product facts screened against authorized customer context; no purchase or CRM write.", Map.copyOf(data),
                List.copyOf(missing), List.of(), source.path("sourceId").asText(), result.asOf(), null, null);
    }
    @Override public Runnable resultReleaseGuard(String customer, CapabilityResultV1 result) {
        Runnable customerGuard = crm.resultReleaseGuard(customer, result);
        if (!result.data().containsKey("productAuthorityBinding"))
            throw new SecurityException("KYP_RESULT_AUTHORITY_BINDING_MISSING");
        var initial = productGrants.get();
        if (initial == null || !grantDigest(initial).equals(result.data().get("productAuthorityBinding")))
            throw new SecurityException("KYP_AUTHORITY_CHANGED");
        var snapshot = authorize(customer);
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, result.requestId(), result.traceId(),
                snapshot.principalRef(), FinanceContextV1.PrincipalType.EMPLOYEE, "finance-harness",
                "WEALTH_PRODUCT_REVIEW", customer, sessionRef, snapshot.authenticationLevel(), Set.of(KypProductFactory.SCOPE));
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, KypProductFactory.CAPABILITY,
                CapabilityRequestV1.Operation.QUERY, context, Map.of(), null, null, Map.of(), null);
        Runnable guard = () -> { customerGuard.run(); productCurrent(request, initial); };
        guard.run();
        return guard;
    }
    private static String grantDigest(BoundFinanceProvider.Grant grant) {
        var fields = new TreeMap<String,Object>();
        fields.put("principal", grant.principalId()); fields.put("type", grant.principalType().name());
        fields.put("subjects", grant.subjects().stream().sorted().toList());
        fields.put("purposes", grant.purposes().stream().sorted().toList());
        fields.put("scopes", grant.scopes().stream().sorted().toList());
        fields.put("session", grant.sessionRef()); fields.put("authority", grant.authorityRef());
        fields.put("expiresAt", grant.expiresAt().toString());
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(HarnessJson.mapper().writeValueAsBytes(fields))); }
        catch (Exception failure) { throw new IllegalStateException("KYP_AUTHORITY_BINDING_FAILED"); }
    }
    private void productCurrent(CapabilityRequestV1 request, BoundFinanceProvider.Grant initial) {
        BoundFinanceProvider.checkContext(initial, request.context());
        if (!initial.equals(productGrants.get())) throw new SecurityException("KYP_AUTHORITY_CHANGED");
        ((InvocationAccess) products).checkAccess(request);
    }
    private void unchanged(String customer, Snapshot before) {
        if (!before.equals(authorize(customer))) throw new SecurityException("CRM_AUTHORITY_CHANGED");
    }
    private boolean current(String value) {
        try { return Instant.parse(value).isAfter(clock.instant()); }
        catch (RuntimeException invalid) { return false; }
    }
    private static BigDecimal positive(Object value, BigDecimal maximum) {
        try {
            if (!(value instanceof Number)) throw new IllegalArgumentException();
            var number = new BigDecimal(value.toString());
            if (number.signum() <= 0 || number.compareTo(maximum) > 0) throw new IllegalArgumentException();
            return number;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("ALLOCATION_NUMBER_INVALID"); }
    }
}
