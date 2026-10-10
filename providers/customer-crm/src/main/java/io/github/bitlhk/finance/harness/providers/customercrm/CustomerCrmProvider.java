package io.github.bitlhk.finance.harness.providers.customercrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Direct read-only CRM Provider. Access is the intersection of host mapping and active CRM assignment. */
public final class CustomerCrmProvider implements DescribedFinanceProvider {
    public static final String PROVIDER_ID = "customer-crm-readonly";
    public static final String CAPABILITY = "wealth.previsit-context.query";
    public static final String ALLOCATION_CAPABILITY = "wealth.allocation-context.query";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String ownerRef;
    private final CustomerCrmClient client;
    private final Supplier<CustomerCrmAuthority> authority;
    private final java.time.Clock clock;

    public CustomerCrmProvider(String ownerRef, CustomerCrmClient client,
            Supplier<CustomerCrmAuthority> authority, java.time.Clock clock) {
        this.ownerRef = required(ownerRef); this.client = Objects.requireNonNull(client);
        this.authority = Objects.requireNonNull(authority); this.clock = Objects.requireNonNull(clock);
    }

    public CustomerCrmAuthority authorize(String customerRef) {
        CustomerCrmAuthority current = authority.get();
        if (current == null) throw new SecurityException("CRM_EMPLOYEE_MAPPING_MISSING");
        current.requireCurrent(ownerRef, customerRef, clock.instant());
        List<JsonNode> assignments = client.find("customerAssignments",
                "principalRef[eq]:" + quote(current.principalRef()), 2000);
        boolean assigned = assignments.stream().anyMatch(row -> customerRef.equals(text(row, "globalCustomerId"))
                && "active".equals(text(row, "assignmentStatus")) && assignmentCurrent(row, clock.instant()));
        if (!assigned) throw new SecurityException("CRM_RELATIONSHIP_SCOPE_REJECTED");
        return current;
    }

    @Override public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, PROVIDER_ID, PROVIDER_VERSION,
                List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY, CapabilityRequestV1.Operation.QUERY),
                        new ProviderDescriptorV1.CapabilitySupport(ALLOCATION_CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
    }

    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (request.operation() != CapabilityRequestV1.Operation.QUERY
                || !Set.of(CAPABILITY,ALLOCATION_CAPABILITY).contains(request.capabilityId()))
            throw new IllegalArgumentException("CRM_QUERY_NOT_INSTALLED");
        if (ALLOCATION_CAPABILITY.equals(request.capabilityId())) return allocation(request);
        FinanceContextV1 context = request.context();
        if (context.principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !"WEALTH_PREVISIT".equals(context.purpose())
                || !context.scopes().contains("wealth:previsit:read"))
            throw new SecurityException("CRM_QUERY_CONTEXT_REJECTED");
        String customerRef = required(context.subjectRef());
        if (request.parameters().containsKey("customerRef")
                && !customerRef.equals(request.parameters().get("customerRef")))
            throw new SecurityException("CRM_QUERY_SUBJECT_MISMATCH");
        CustomerCrmAuthority before = authorize(customerRef);
        List<JsonNode> customers = client.find("enterpriseCustomers",
                "globalCustomerId[eq]:" + quote(customerRef), 2);
        List<JsonNode> profiles = client.find("customerDomainProfiles",
                "and(globalCustomerId[eq]:" + quote(customerRef) + ",runtimeStatus[eq]:" + quote("active") + ")", 20);
        CustomerCrmAuthority after = authorize(customerRef);
        if (!before.equals(after)) throw new SecurityException("CRM_AUTHORITY_CHANGED");
        if (customers.size() != 1) return failed(request, "CRM_CUSTOMER_NOT_UNIQUE");
        JsonNode customer = customers.getFirst();
        String asOf = text(customer, "businessAsOf");
        var customerFacts = new LinkedHashMap<String,Object>();
        copy(customer, customerFacts, "globalCustomerId", "customerId");
        copy(customer, customerFacts, "name", "displayName");
        copy(customer, customerFacts, "customerStatus", "customerStatus");
        copy(customer, customerFacts, "maskedPhone", "maskedPhone");
        List<Map<String,Object>> profileFacts = profiles.stream().map(this::profile).toList();
        var readinessReasons = new ArrayList<String>();
        if (asOf.isBlank()) readinessReasons.add("CRM_CUSTOMER_BUSINESS_DATE_MISSING");
        if (profiles.isEmpty()) readinessReasons.add("CRM_ACTIVE_PROFILE_MISSING");
        else if (profileFacts.stream().noneMatch(this::hasBusinessDetail))
            readinessReasons.add("CRM_PROFILE_BUSINESS_DETAIL_MISSING");
        var previsit = new LinkedHashMap<String,Object>();
        previsit.put("schema", "crm.wealth-previsit-context.v1");
        previsit.put("status", readinessReasons.isEmpty() ? "ready" : "degraded");
        previsit.put("customer", customerFacts); previsit.put("profiles", profileFacts);
        previsit.put("dataAsOf", asOf); previsit.put("missingInputs", readinessReasons);
        previsit.put("readiness", Map.of("status", readinessReasons.isEmpty() ? "READY" : "PARTIAL",
                "reasons", readinessReasons));
        String fingerprint = digest(previsit);
        var receipt = Map.<String,Object>of("schema", "crm-read-receipt.v1",
                "receiptId", "crm-" + UUID.randomUUID(), "receiptFingerprint", fingerprint,
                "createdAt", clock.instant().toString(), "providerId", PROVIDER_ID,
                "assignmentPrincipalRef", before.principalRef(), "mappingRevision", before.revision());
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
                "授权客户访前事实已从 CRM 返回。", Map.of("previsitContext", previsit, "hostEvidence", receipt),
                readinessReasons, List.of(), "customer-crm", parseAsOf(asOf), null, null);
    }

    private CapabilityResultV1 allocation(CapabilityRequestV1 request) {
        FinanceContextV1 context=request.context();
        if(context.principalType()!=FinanceContextV1.PrincipalType.EMPLOYEE
                ||!"WEALTH_PRODUCT_REVIEW".equals(context.purpose())
                ||!context.scopes().contains("wealth:allocation:read"))throw new SecurityException("CRM_QUERY_CONTEXT_REJECTED");
        String customerRef=required(context.subjectRef()); CustomerCrmAuthority before=authorize(customerRef);
        double amount=positiveNumber(request.parameters().get("amount"),"ALLOCATION_AMOUNT_REQUIRED");
        int horizon=(int)positiveNumber(request.parameters().get("horizonMonths"),"ALLOCATION_HORIZON_REQUIRED");
        String channel=required(String.valueOf(request.parameters().getOrDefault("channel","")));
        List<JsonNode> profiles=client.find("customerDomainProfiles",
                "and(globalCustomerId[eq]:"+quote(customerRef)+",runtimeStatus[eq]:"+quote("active")+")",20);
        JsonNode wealth=profiles.stream().filter(row->"wealth".equals(text(row,"profileType"))).findFirst()
                .orElseThrow(()->new IllegalStateException("WEALTH_PROFILE_MISSING"));
        JsonNode detail=parseObject(wealth.path("detail")); JsonNode assessment=detail.path("riskAssessment");
        int customerRisk=riskOrdinal(assessment.path("riskLevel").asText());
        String validUntil=assessment.path("validUntil").asText("");
        if(customerRisk<1||validUntil.isBlank()||!validAfter(validUntil,clock.instant()))
            return failed(request,"CUSTOMER_SUITABILITY_CONTEXT_INVALID");
        JsonNode catalog=catalog(); var eligible=new ArrayList<Map<String,Object>>();var excluded=new ArrayList<Map<String,Object>>();
        for(JsonNode product:catalog.path("products")) {
            String reason=null;int productRisk=riskOrdinal(product.path("riskLevel").asText());
            if(!"在售".equals(product.path("status").asText()))reason="PRODUCT_NOT_SELLABLE";
            else if(productRisk<1||productRisk>customerRisk)reason="RISK_MISMATCH";
            else if(!contains(product.path("channels"),channel))reason="CHANNEL_NOT_ALLOWED";
            else if(amount<product.path("minAmount").asDouble())reason="AMOUNT_BELOW_MINIMUM";
            else if(horizon<product.path("termMonths").asInt())reason="HORIZON_TOO_SHORT";
            var item=new LinkedHashMap<String,Object>();
            for(String key:List.of("productId","name","category","riskLevel","status","minAmount","termMonths","channels"))
                item.put(key,JSON.convertValue(product.path(key),Object.class));
            item.put("dataAsOf",catalog.path("dataAsOf").asText());
            if(reason==null){item.put("policyCode","WEALTH_SUITABILITY_MATCH:ELIGIBLE");eligible.add(Map.copyOf(item));}
            else {item.put("reasonCode",reason);excluded.add(Map.copyOf(item));}
        }
        CustomerCrmAuthority after=authorize(customerRef);if(!before.equals(after))throw new SecurityException("CRM_AUTHORITY_CHANGED");
        var data=new LinkedHashMap<String,Object>();data.put("schema","harness.wealth-allocation-context.v1");
        data.put("status",eligible.isEmpty()?"no_eligible_products":"ready");data.put("customerRef",customerRef);
        data.put("customerRiskLevel","C"+customerRisk);data.put("assessmentValidUntil",validUntil);
        data.put("request",Map.of("amount",amount,"horizonMonths",horizon,"channel",channel));
        data.put("eligibleProducts",List.copyOf(eligible));data.put("excludedProducts",List.copyOf(excluded));
        data.put("eligibilityPolicy",Map.of(
                "horizonRule","PRODUCT_TERM_NOT_LONGER_THAN_CUSTOMER_HORIZON",
                "description","产品期限不超过客户可接受期限即满足期限条件"));
        data.put("policySource",Map.of("ruleVersion","wealth-suitability-v1","catalogVersion",catalog.path("catalogVersion").asText(),
                "catalogDigest",digest(catalog),"dataAsOf",catalog.path("dataAsOf").asText()));
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,context.requestId(),context.traceId(),
                CapabilityResultV1.Status.COMPLETED,"已完成合成产品目录的确定性适当性筛选。",Map.copyOf(data),
                List.of(),List.of(),"customer-crm",Instant.parse(catalog.path("dataAsOf").asText()),null,null);
    }

    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        throw new SecurityException("CRM_PROVIDER_READ_ONLY");
    }

    private Map<String,Object> profile(JsonNode node) {
        var value = new LinkedHashMap<String,Object>();
        for (String key : List.of("profileType", "detailSchemaVersion"))
            if (!text(node, key).isBlank()) value.put(key, text(node, key));
        for (String key : List.of("demographics", "preferences", "insuranceFacts", "detail", "fieldProvenance"))
            if (node.has(key) && !node.path(key).isNull() && !node.path(key).asText("").isBlank())
                value.put(key, JSON.convertValue(parseObject(node.path(key)), Object.class));
        return Map.copyOf(value);
    }
    private boolean hasBusinessDetail(Map<String,Object> profile) {
        return profile.keySet().stream().anyMatch(key -> !Set.of("profileType","detailSchemaVersion").contains(key));
    }
    private static JsonNode parseObject(JsonNode value) {
        if (!value.isTextual()) return value;
        try { return JSON.readTree(value.asText()); }
        catch (Exception invalid) { throw new IllegalStateException("CRM_JSON_FIELD_INVALID", invalid); }
    }
    private static JsonNode catalog() {
        try(var in=CustomerCrmProvider.class.getResourceAsStream("/customer-manager-products.v1.json")) {
            if(in==null)throw new IllegalStateException("PRODUCT_CATALOG_MISSING");return JSON.readTree(in);
        }catch(java.io.IOException failure){throw new IllegalStateException("PRODUCT_CATALOG_INVALID",failure);}
    }
    private static double positiveNumber(Object value,String code){
        if(!(value instanceof Number number)||!Double.isFinite(number.doubleValue())||number.doubleValue()<=0)throw new IllegalArgumentException(code);
        return number.doubleValue();
    }
    private static int riskOrdinal(String value){var matcher=java.util.regex.Pattern.compile("(?i)^[CR]?([1-5])$").matcher(value.trim());return matcher.matches()?Integer.parseInt(matcher.group(1)):-1;}
    private static boolean validAfter(String value,Instant now){try{return Instant.parse(value).isAfter(now);}catch(Exception ignored){return false;}}
    private static boolean contains(JsonNode values,String expected){for(JsonNode value:values)if(expected.equals(value.asText()))return true;return false;}
    private CapabilityResultV1 failed(CapabilityRequestV1 request, String code) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
                request.context().traceId(), CapabilityResultV1.Status.FAILED, "CRM 客户事实查询失败。",
                Map.of(), List.of(), List.of("CHECK_CRM_DATA"), "customer-crm", clock.instant(), null,
                new CapabilityResultV1.Failure("UPSTREAM_FAILURE", code, false, "CRM customer facts unavailable", List.of()));
    }
    private static boolean assignmentCurrent(JsonNode row, Instant now) {
        String from = text(row, "validFrom"), to = text(row, "validTo");
        try { return (from.isBlank() || !Instant.parse(from).isAfter(now)) && (to.isBlank() || Instant.parse(to).isAfter(now)); }
        catch (Exception invalid) { return false; }
    }
    private static Instant parseAsOf(String value) {
        try { return Instant.parse(value); } catch (Exception ignored) { return Instant.EPOCH; }
    }
    private static String quote(String value) { try { return JSON.writeValueAsString(value); } catch(Exception e){throw new IllegalStateException(e);} }
    private static String text(JsonNode node, String key) { return node.path(key).asText("").trim(); }
    private static void copy(JsonNode source, Map<String,Object> target, String from, String to) {
        if (source.has(from) && !source.path(from).isNull() && !text(source, from).isBlank())
            target.put(to, source.path(from).isValueNode() ? source.path(from).asText() : JSON.convertValue(source.path(from), Object.class));
    }
    private static String digest(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch (Exception failure) { throw new IllegalStateException("CRM_RECEIPT_DIGEST_FAILED", failure); }
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CRM_REFERENCE_REQUIRED"); return value.trim();
    }
}
