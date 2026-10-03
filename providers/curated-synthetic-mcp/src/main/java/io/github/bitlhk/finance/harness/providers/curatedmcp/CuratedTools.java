package io.github.bitlhk.finance.harness.providers.curatedmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.util.*;

/** Explicit synthetic contracts; names do not bind to existing banking capabilities. */
public final class CuratedTools {
    public record Tool(String name, String selector, String defaultRef, Set<String> resources,
                       String source, String snapshot, String identityPointer, String requiredField) {
        public String capabilityId() { return "eval.synthetic." + name.replace('_', '-') + ".query"; }
        public String scope() { return "eval:synthetic:" + name + ":read"; }
        public String toolName() { return "curated_" + name; }
    }
    private static Tool customer(String name, String field, String identity, String... ids) {
        return new Tool(name, "customer_id", null, Set.of(ids), "finance-eval-synthetic-fixture", "wealth-demo-v1", identity, field);
    }
    private static Tool reference(String name, String defaultRef, String field, String... refs) {
        return new Tool(name, "reference", defaultRef, Set.of(refs), "finance-eval-role-fixtures", "roles-demo-v1", "/reference", field);
    }
    public static final List<Tool> ALL = List.of(
        customer("get_customer_profile", "profile", "/profile/customerId", "C005", "C006", "I001"),
        customer("get_customer_portfolio", "holdings", "/customerId", "C005", "C006"),
        new Tool("get_wealth_policy_basis", "", null, Set.of("policy"), "finance-eval-synthetic-fixture", "wealth-demo-v1", "", "rules"),
        customer("prepare_wealth_allocation_context", "readiness", "/profile/customerId", "C005", "C006"),
        new Tool("wealth_assistant_product_info", "product_id", null, Set.of("P009", "P001", "F000343"), "finance-eval-synthetic-fixture", "wealth-demo-v1", "/product/productId", "product"),
        reference("list_products", "insurance", "products", "insurance"),
        reference("get_product_detail", "INS01", "productId", "INS01", "INS02"),
        reference("wind_stock_data", "DEMO001", "financials", "DEMO001"),
        reference("wind_financial_docs", "DEMO001", "documents", "DEMO001"),
        reference("audit_case_context", "AU001", "materials", "AU001", "AU002"),
        reference("evaluate_audit_human_review", "AU001", "finalDecision", "AU001", "AU002"),
        reference("account_balance_query", "acct-demo-001", "accountRef", "acct-demo-001"),
        reference("fraud_transfer_risk_assess", "bene-risk", "decision", "bene-risk", "bene-clear", "bene-unknown"),
        reference("search_risk_enterprises", "海岳制造", "enterprises", "海岳制造", "DEMO-ENTERPRISE-001"),
        reference("get_post_loan_risk_snapshot", "DEMO-ENTERPRISE-001", "data", "DEMO-ENTERPRISE-001"),
        reference("evaluate_post_loan_risk_escalation", "DEMO-ENTERPRISE-001", "warningLevel", "DEMO-ENTERPRISE-001")
    );
    public static Tool named(String name) {
        return ALL.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("CURATED_TOOL_UNKNOWN"));
    }
    static Tool capability(String id) {
        return ALL.stream().filter(t -> t.capabilityId().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("CURATED_CAPABILITY_UNKNOWN"));
    }
    static Map<String, Object> arguments(Tool tool, Map<String, Object> parameters, String subject, CuratedSourceConfig.Subject policy) {
        var args = new LinkedHashMap<String, Object>(parameters);
        Set<String> allowed = tool.selector().isEmpty() ? Set.of() : tool.selector().equals("customer_id")
                ? Set.of("customer_id", "synthetic_customer_id") : Set.of(tool.selector());
        if (tool.name().equals("prepare_wealth_allocation_context")) {
            allowed = Set.of("customer_id", "synthetic_customer_id", "investable_amount", "investment_horizon_years", "liquidity_ratio");
            for (String key : List.of("investable_amount", "investment_horizon_years", "liquidity_ratio")) {
                Object value = args.get(key);
                if (args.containsKey(key)) {
                    if (!(value instanceof Number)) throw new IllegalArgumentException("CURATED_PARAMETER_INVALID");
                    BigDecimal number;
                    try { number = new BigDecimal(value.toString()); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("CURATED_PARAMETER_INVALID"); }
                    if (number.signum() < 0 || (key.equals("liquidity_ratio") && number.compareTo(BigDecimal.ONE) > 0))
                        throw new IllegalArgumentException("CURATED_PARAMETER_INVALID");
                }
            }
        }
        if (!allowed.containsAll(args.keySet())) throw new IllegalArgumentException("CURATED_PARAMETER_UNKNOWN");
        String ref = "policy";
        if (!tool.selector().isEmpty()) {
            Object value = args.get(tool.selector());
            if (value == null && args.containsKey(tool.selector())) throw new IllegalArgumentException("CURATED_SELECTOR_INVALID");
            if (value != null && !(value instanceof String)) throw new IllegalArgumentException("CURATED_SELECTOR_INVALID");
            ref = (String) value;
            if ((ref == null || ref.isEmpty()) && tool.defaultRef() != null) ref = tool.defaultRef();
            if (ref == null || ref.isBlank()) throw new IllegalArgumentException("CURATED_SELECTOR_REQUIRED");
            if (tool.selector().equals("customer_id")) {
                if (!subject.equals(ref)) throw new SecurityException("CURATED_SUBJECT_MISMATCH");
                if ("BOUND_SUBJECT".equals(policy.customerSelection())) {
                    if (args.containsKey("synthetic_customer_id")) throw new IllegalArgumentException("CURATED_SELECTION_NOT_ENABLED");
                    ref = policy.customerId();
                } else {
                    Object selected = args.remove("synthetic_customer_id");
                    if (!(selected instanceof String id) || id.isBlank()) throw new IllegalArgumentException("CURATED_SYNTHETIC_SELECTOR_REQUIRED");
                    ref = id;
                }
            }
            args.put(tool.selector(), ref);
        }
        if (!policy.resources().getOrDefault(tool.name(), Set.of()).contains(ref)) throw new SecurityException("CURATED_RESOURCE_DENIED");
        return Map.copyOf(args);
    }
    static void validateResult(Tool tool, Map<String, Object> args, JsonNode data) {
        String snapshot = tool.snapshot();
        if (tool.name().equals("get_customer_profile") && "I001".equals(args.get("customer_id"))) snapshot = "roles-demo-v1";
        if (data == null || !data.isObject() || !data.path("synthetic").isBoolean() || !data.path("synthetic").booleanValue() || !data.path("source").asText().equals(tool.source())
                || !data.path("snapshotVersion").asText().equals(snapshot) || !data.path("asOf").asText().equals("2026-09-05")
                || !data.hasNonNull(tool.requiredField())) throw new IllegalArgumentException("CURATED_RESULT_CONTRACT_MISMATCH");
        if (!tool.identityPointer().isEmpty() && !data.at(tool.identityPointer()).asText().equals(args.get(tool.selector())))
            throw new SecurityException("CURATED_RESULT_RESOURCE_MISMATCH");
        CuratedOutputContract.validate(tool, args, data);
    }
    static String schemaPin(Object schema) {
        try {
            var mapper = HarnessJson.mapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            Object ordered = mapper.convertValue(schema, Object.class);
            return CuratedSourceConfig.sha(mapper.writeValueAsBytes(ordered));
        } catch (Exception e) { throw new IllegalArgumentException("CURATED_SCHEMA_INVALID"); }
    }
    static com.fasterxml.jackson.databind.ObjectMapper responseMapper() {
        var mapper = HarnessJson.mapper()
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(BoundedHttpClient.MAX_BODY_BYTES).build());
        return mapper;
    }
}
