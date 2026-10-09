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
                List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
    }

    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (!CAPABILITY.equals(request.capabilityId()) || request.operation() != CapabilityRequestV1.Operation.QUERY)
            throw new IllegalArgumentException("CRM_QUERY_NOT_INSTALLED");
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
    private static JsonNode parseObject(JsonNode value) {
        if (!value.isTextual()) return value;
        try { return JSON.readTree(value.asText()); }
        catch (Exception invalid) { throw new IllegalStateException("CRM_JSON_FIELD_INVALID", invalid); }
    }
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
