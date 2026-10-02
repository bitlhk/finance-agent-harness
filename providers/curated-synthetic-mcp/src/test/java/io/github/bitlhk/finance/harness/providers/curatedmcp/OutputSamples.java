package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;

/** Small independent examples, deliberately not generated from the validator. */
final class OutputSamples {
    static Map<String, Object> value(String tool, String ref) {
        String json = switch (tool) {
            case "get_customer_profile" -> "{}";
            case "get_customer_portfolio" -> """
                {"holdings":[],"scope":"bank only"}
                """;
            case "get_wealth_policy_basis" -> """
                {"policyVersion":"demo-wealth-policy-v1","rules":["not an approval"]}
                """;
            case "prepare_wealth_allocation_context" -> """
                {"readiness":"READY","eligibleProducts":[],"missing":["external assets"],"currentPortfolio":[],
                 "investableAmount":750000,"liquidityFloor":150000,"minimumHorizonYears":3,
                 "calculationBasis":"budget only","excludedProducts":[{"productId":"P004","reason":"unavailable"}],
                 "policy":{"version":"demo-wealth-policy-v1"}}
                """;
            case "wealth_assistant_product_info" -> """
                {"product":{"productId":"P009","name":"demo","risk":"R1","availability":"AVAILABLE",
                 "redemption":"T+1","lockDays":0,"fee":"0.15%","guaranteed":false,"riskDisclosure":"may lose value"}}
                """;
            case "list_products" -> """
                {"products":[{"productId":"INS01","name":"demo","category":"auto","formalTermsAvailable":false}]}
                """;
            case "get_product_detail" -> """
                {"productId":"INS01","name":"demo","summary":"training","dataMode":"training-demo","missing":["terms"]}
                """;
            case "wind_stock_data" -> """
                {"company":"demo","sourceSystem":"synthetic","period":"2026H1","currency":"CNY","unit":"million",
                 "financials":{"revenue":[1000,1200],"netProfit":[100,140],"operatingCashFlow":[130,70],
                 "receivables":[280,450],"inventory":[190,270],"contractLiabilities":[120,100],"capex":[80,160]},
                 "valuation":{"peTTM":28,"priceAsOf":"2026-09-04","consensus":null,"comparablePeers":null},
                 "business":"equipment","missing":["consensus"]}
                """;
            case "wind_financial_docs" -> """
                {"documents":[{"id":"DOC001","title":"demo","date":"2026-08-28","section":"3","text":"synthetic"}],"consensusAvailable":false}
                """;
            case "audit_case_context" -> """
                {"entity":"demo","currency":"CNY","unit":"ten thousand","materials":[{"id":"p2","field":"revenue","period":"2026H1","amount":600}],
                 "policy":{"version":"demo-audit-v2","requiredMaterials":["application"],"differenceThreshold":null},"scope":"income only"}
                """;
            case "evaluate_audit_human_review" -> """
                {"reviewRequired":true,"reason":"reconcile","policyVersion":"demo-audit-v2","finalDecision":"NOT_MADE"}
                """;
            case "account_balance_query" -> """
                {"accountRef":"acct-demo-001","availableBalance":80000,"currency":"CNY","status":"ACTIVE","owner":"demo"}
                """;
            case "fraud_transfer_risk_assess" -> ref.equals("bene-risk") ? """
                {"status":"READY","decision":"RISK_HIT","signals":[{"reasonCode":"KNOWN_FRAUD_ACCOUNT","source":"synthetic",
                 "matchedField":"beneficiary","updatedAt":"2026-09-05T08:00:00+08:00"}],"scope":"list only","executed":false}
                """ : ref.equals("bene-unknown") ? """
                {"status":"UNAVAILABLE","decision":"UNKNOWN","signals":[],"reason":"unavailable","executed":false}
                """ : """
                {"status":"READY","decision":"NO_KNOWN_HIT","signals":[],"scope":"list only","executed":false}
                """;
            case "search_risk_enterprises" -> """
                {"enterprises":[{"enterpriseId":"DEMO-ENTERPRISE-001","name":"demo"}]}
                """;
            case "get_post_loan_risk_snapshot" -> """
                {"status":"READY","snapshotFingerprint":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                 "data":{"enterpriseId":"DEMO-ENTERPRISE-001","completeness":{"criticalComplete":true},
                 "loans":[{"balance":6000000,"overdueDays":12,"classification":"watch"}],"ratingDowngradeNotches":1,
                 "financials":{"period":"2026H1","operatingCashFlow":-1500000,"netProfit":1000000,"receivablesGrowth":0.4},
                 "collateral":{"value":7000000,"valuedAt":"2026-08-31","coverageRatio":0.7},
                 "externalEvents":[{"type":"litigation","verified":false,"source":"synthetic"}],
                 "policy":{"version":"demo-post-loan-v2","warningLevel":"high","basis":"internal"}}}
                """;
            case "evaluate_post_loan_risk_escalation" -> """
                {"status":"READY","warningLevel":"high","policyVersion":"demo-post-loan-v2","triggers":["overdue"],
                 "regulatoryClassificationChanged":false,"requiresHumanReview":true}
                """;
            default -> throw new IllegalArgumentException(tool);
        };
        try {
            Map<String, Object> result = HarnessJson.mapper().readValue(json, Map.class);
            if (tool.equals("get_customer_profile") || tool.equals("prepare_wealth_allocation_context"))
                result.put("profile", HarnessJson.mapper().readValue(ref.equals("I001") ? """
                    {"customerId":"I001","name":"demo","vehicle":{"energy":"electric","registeredUse":"family",
                     "actualUse":"unverified","annualMileage":28000},"policy":{"expiryDate":"2026-09-20",
                     "driverSeatCoverage":false,"passengerCoverage":"unknown"},"claims":{"records":[],"scope":"bank only"},
                     "concerns":["price"],"missing":["actual use"]}
                    """ : ref.equals("C006") ? """
                    {"customerId":"C006","name":"demo","riskLevel":"C3","assessmentStatus":"VALID","maturityAmount":3000000,
                     "maturityDate":"2026-09-10","notes":"equipment payment","holdingsScope":"bank only"}
                    """ : """
                    {"customerId":"C005","name":"demo","riskLevel":"C4","riskScaleLabel":"internal","assessmentStatus":"VALID",
                     "validUntil":"2027-03-11","objective":"growth","knownCashNeeds":[],"holdings":[],"holdingsScope":"bank only",
                     "liabilities":"none confirmed","investmentExperience":"5 years","lossTolerance":"75000"}
                    """, Map.class));
            return result;
        } catch (Exception error) { throw new AssertionError(error); }
    }
}
