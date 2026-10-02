package io.github.bitlhk.finance.harness.providers.curatedmcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Predicate;

/** Required structure of the pinned reference contract, not a business qualification. */
final class CuratedOutputContract {
    private interface Shape { void check(JsonNode node); }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("CURATED_RESULT_CONTRACT_MISMATCH");
    }
    private static Shape type(Predicate<JsonNode> test) { return node -> require(node != null && test.test(node)); }
    private static final Shape TEXT = type(n -> n.isTextual() && !n.textValue().isBlank());
    private static final Shape NUMBER = type(JsonNode::isNumber);
    private static final Shape NONNEGATIVE = type(n -> n.isNumber() && n.decimalValue().signum() >= 0);
    private static final Shape INTEGER = type(n -> n.isIntegralNumber() && n.canConvertToInt() && n.intValue() >= 0);
    private static final Shape TRUE = type(n -> n.isBoolean() && n.booleanValue());
    private static final Shape FALSE = type(n -> n.isBoolean() && !n.booleanValue());
    private static final Shape NULL = type(JsonNode::isNull);
    private static final Shape DATE = type(n -> {
        if (!n.isTextual()) return false;
        try { return java.time.LocalDate.parse(n.textValue()).toString().equals(n.textValue()); }
        catch (java.time.DateTimeException e) { return false; }
    });
    private static final Shape TIMESTAMP = type(n -> {
        if (!n.isTextual()) return false;
        try { java.time.OffsetDateTime.parse(n.textValue()); return true; }
        catch (java.time.DateTimeException e) { return false; }
    });
    private static Shape values(String... values) {
        var allowed = Set.of(values);
        return type(n -> n.isTextual() && allowed.contains(n.textValue()));
    }
    private static Shape array(Shape item) {
        return node -> { require(node != null && node.isArray()); node.forEach(item::check); };
    }
    private static final Shape STRINGS = array(TEXT);
    private static final Shape EMPTY = type(n -> n.isArray() && n.isEmpty());
    private static Shape object(Object... fields) {
        var required = new LinkedHashMap<String, Shape>();
        for (int i = 0; i < fields.length; i += 2) required.put((String) fields[i], (Shape) fields[i + 1]);
        return node -> {
            require(node != null && node.isObject());
            required.forEach((name, shape) -> shape.check(node.get(name)));
        };
    }
    private static final Shape PRODUCT = object("productId", values("P009", "P001", "F000343"), "name", TEXT,
            "risk", values("R1", "R2", "R4"), "availability", values("AVAILABLE"), "redemption", TEXT,
            "lockDays", INTEGER, "fee", TEXT, "guaranteed", FALSE);
    private static final Shape POLICY = object("policyVersion", values("demo-wealth-policy-v1"), "rules", STRINGS);
    private static final Shape INSURANCE_PROFILE = object("customerId", values("I001"), "name", TEXT,
            "vehicle", object("energy", TEXT, "registeredUse", TEXT, "actualUse", TEXT, "annualMileage", INTEGER),
            "policy", object("expiryDate", DATE, "driverSeatCoverage", FALSE, "passengerCoverage", TEXT),
            "claims", object("records", EMPTY, "scope", TEXT), "concerns", STRINGS, "missing", STRINGS);
    private static final Shape C005_PROFILE = object("customerId", values("C005"), "name", TEXT, "riskLevel", TEXT,
            "riskScaleLabel", TEXT, "assessmentStatus", values("VALID"), "validUntil", DATE, "objective", TEXT,
            "knownCashNeeds", EMPTY, "holdings", EMPTY, "holdingsScope", TEXT, "liabilities", TEXT,
            "investmentExperience", TEXT, "lossTolerance", TEXT);
    private static final Shape C006_PROFILE = object("customerId", values("C006"), "name", TEXT, "riskLevel", TEXT,
            "assessmentStatus", values("VALID"), "maturityAmount", NONNEGATIVE, "maturityDate", DATE,
            "notes", TEXT, "holdingsScope", TEXT);
    private static final Shape PAIR = node -> { array(NUMBER).check(node); require(node.size() == 2); };
    private static final Map<String, Shape> ROLE_OUTPUTS = Map.ofEntries(
        Map.entry("list_products", object("products", array(object("productId", values("INS01", "INS02"),
                "name", TEXT, "category", TEXT, "formalTermsAvailable", FALSE)))),
        Map.entry("get_product_detail", object("productId", TEXT, "name", TEXT, "summary", TEXT,
                "dataMode", values("training-demo"), "missing", STRINGS)),
        Map.entry("wind_stock_data", object("company", TEXT, "sourceSystem", TEXT, "period", TEXT,
                "currency", values("CNY"), "unit", TEXT, "financials", object("revenue", PAIR, "netProfit", PAIR,
                    "operatingCashFlow", PAIR, "receivables", PAIR, "inventory", PAIR, "contractLiabilities", PAIR, "capex", PAIR),
                "valuation", object("peTTM", NONNEGATIVE, "priceAsOf", DATE, "consensus", NULL, "comparablePeers", NULL),
                "business", TEXT, "missing", STRINGS)),
        Map.entry("wind_financial_docs", object("documents", array(object("id", TEXT, "title", TEXT, "date", DATE,
                "section", TEXT, "text", TEXT)), "consensusAvailable", FALSE)),
        Map.entry("audit_case_context", object("entity", TEXT, "currency", values("CNY"), "unit", TEXT,
                "materials", array(object("id", TEXT, "field", TEXT, "period", TEXT, "amount", NONNEGATIVE)),
                "policy", object("version", values("demo-audit-v2"), "requiredMaterials", STRINGS, "differenceThreshold", NULL), "scope", TEXT)),
        Map.entry("evaluate_audit_human_review", object("reviewRequired", TRUE, "reason", TEXT,
                "policyVersion", values("demo-audit-v2"), "finalDecision", values("NOT_MADE"))),
        Map.entry("account_balance_query", object("accountRef", TEXT, "availableBalance", NONNEGATIVE,
                "currency", values("CNY"), "status", values("ACTIVE"), "owner", TEXT)),
        Map.entry("search_risk_enterprises", object("enterprises", array(object("enterpriseId", values("DEMO-ENTERPRISE-001"), "name", TEXT)))),
        Map.entry("get_post_loan_risk_snapshot", object("status", values("READY"),
                "snapshotFingerprint", type(n -> n.isTextual() && n.textValue().matches("[0-9a-f]{64}")),
                "data", object("enterpriseId", TEXT, "completeness", object("criticalComplete", TRUE),
                    "loans", array(object("balance", NONNEGATIVE, "overdueDays", INTEGER, "classification", TEXT)),
                    "ratingDowngradeNotches", INTEGER,
                    "financials", object("period", TEXT, "operatingCashFlow", NUMBER, "netProfit", NUMBER, "receivablesGrowth", NUMBER),
                    "collateral", object("value", NONNEGATIVE, "valuedAt", DATE, "coverageRatio", NONNEGATIVE),
                    "externalEvents", array(object("type", TEXT, "verified", FALSE, "source", TEXT)),
                    "policy", object("version", values("demo-post-loan-v2"), "warningLevel", TEXT, "basis", TEXT)))),
        Map.entry("evaluate_post_loan_risk_escalation", object("status", values("READY"), "warningLevel", TEXT,
                "policyVersion", values("demo-post-loan-v2"), "triggers", STRINGS,
                "regulatoryClassificationChanged", FALSE, "requiresHumanReview", TRUE))
    );

    static void validate(CuratedTools.Tool tool, Map<String, Object> args, JsonNode data) {
        Set<String> states = switch (tool.name()) {
            case "prepare_wealth_allocation_context" -> Set.of("readiness");
            case "fraud_transfer_risk_assess" -> Set.of("decision", "status");
            case "account_balance_query", "get_post_loan_risk_snapshot", "evaluate_post_loan_risk_escalation" -> Set.of("status");
            default -> Set.of();
        };
        for (String state : List.of("decision", "status", "readiness")) require(!data.has(state) || states.contains(state));
        switch (tool.name()) {
            case "get_customer_profile" -> profile(args).check(data.get("profile"));
            case "get_customer_portfolio" -> object("customerId", TEXT, "holdings", EMPTY, "scope", TEXT).check(data);
            case "get_wealth_policy_basis" -> POLICY.check(data);
            case "wealth_assistant_product_info" -> product(data.get("product"));
            case "prepare_wealth_allocation_context" -> {
                object("readiness", values("READY", "DEGRADED"), "profile", profile(args),
                        "eligibleProducts", array(PRODUCT), "missing", STRINGS).check(data);
                if (data.get("readiness").textValue().equals("READY")) {
                    require("C005".equals(args.get("customer_id")));
                    object("currentPortfolio", EMPTY, "investableAmount", NONNEGATIVE, "liquidityFloor", NONNEGATIVE,
                            "minimumHorizonYears", NONNEGATIVE, "calculationBasis", TEXT,
                            "excludedProducts", array(object("productId", TEXT, "reason", TEXT))).check(data);
                    JsonNode policy = data.get("policy");
                    // The pinned Python source has two FastMCP-version-dependent policy shapes.
                    if (policy != null && policy.has("version")) object("version", values("demo-wealth-policy-v1")).check(policy);
                    else POLICY.check(policy);
                    data.get("eligibleProducts").forEach(CuratedOutputContract::product);
                    require(data.get("liquidityFloor").decimalValue().compareTo(data.get("investableAmount").decimalValue()) <= 0);
                } else {
                    EMPTY.check(data.get("eligibleProducts"));
                    require(!data.get("missing").isEmpty());
                }
            }
            case "fraud_transfer_risk_assess" -> {
                object("executed", FALSE, "signals", array(object("reasonCode", TEXT, "source", TEXT,
                        "matchedField", TEXT, "updatedAt", TIMESTAMP))).check(data);
                switch ((String) args.get("reference")) {
                    case "bene-unknown" -> {
                        object("status", values("UNAVAILABLE"), "decision", values("UNKNOWN"), "reason", TEXT).check(data);
                        EMPTY.check(data.get("signals"));
                    }
                    case "bene-clear" -> {
                        object("status", values("READY"), "decision", values("NO_KNOWN_HIT"), "scope", TEXT).check(data);
                        EMPTY.check(data.get("signals"));
                    }
                    case "bene-risk" -> {
                        object("status", values("READY"), "decision", values("RISK_HIT"), "scope", TEXT).check(data);
                        require(!data.get("signals").isEmpty());
                    }
                    default -> throw new IllegalArgumentException("CURATED_RESULT_CONTRACT_MISMATCH");
                }
            }
            default -> Objects.requireNonNull(ROLE_OUTPUTS.get(tool.name())).check(data);
        }
        switch (tool.name()) {
            case "get_product_detail" -> identity(data.get("productId"), args.get("reference"));
            case "account_balance_query" -> identity(data.get("accountRef"), args.get("reference"));
            case "get_post_loan_risk_snapshot" -> identity(data.at("/data/enterpriseId"), args.get("reference"));
        }
    }
    private static Shape profile(Map<String, Object> args) {
        return switch ((String) args.get("customer_id")) {
            case "C005" -> C005_PROFILE;
            case "C006" -> C006_PROFILE;
            case "I001" -> INSURANCE_PROFILE;
            default -> throw new IllegalArgumentException("CURATED_RESULT_CONTRACT_MISMATCH");
        };
    }
    private static void product(JsonNode node) {
        PRODUCT.check(node);
        if (node.get("productId").textValue().equals("F000343")) TEXT.check(node.get("riskDisclosure"));
    }
    private static void identity(JsonNode node, Object expected) {
        if (node == null || !node.isTextual() || !node.textValue().equals(expected))
            throw new SecurityException("CURATED_RESULT_RESOURCE_MISMATCH");
    }
}
