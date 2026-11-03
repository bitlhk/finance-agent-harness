package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.examples.wealth.ServicePreparation;
import java.util.*;

/** Bounded CRM read for customer overview, holdings and upcoming items. */
final class CustomerServiceTask {
    interface CustomerRef { String resolve(String alias); }
    private final PreparationAccess access;
    private final CustomerRef customers;

    CustomerServiceTask(PreparationAccess access, CustomerRef customers) {
        this.access = Objects.requireNonNull(access);
        this.customers = Objects.requireNonNull(customers);
    }

    static boolean handles(String query) {
        return query != null && !CustomerManagerDialogue.requestsPreparation(query)
                && (query.contains("持仓") || query.contains("到期")
                || query.contains("资产概况") || query.contains("风险测评")
                || query.contains("客户概览") || query.contains("客户情况"));
    }

    Map<String,Object> run(String query, String alias, String runId) {
        if (alias == null || alias.isBlank()) return Map.of("status", "NEED_CLARIFICATION",
                "response", "请先明确要查看哪位获授权客户。", "missingInputs", List.of("customer"));
        String customerRef = customers.resolve(alias);
        if (customerRef == null || customerRef.isBlank()) throw new SecurityException("CUSTOMER_MAPPING_MISSING");
        String requestId = "fh-customer-" + UUID.randomUUID();
        String traceId = "fh-customer-trace-" + UUID.randomUUID();
        CapabilityResultV1 result = access.query(requestId, traceId, customerRef,
                ServicePreparation.CAPABILITY, Map.of());
        if (result.status() != CapabilityResultV1.Status.COMPLETED)
            throw new IllegalStateException("CUSTOMER_FACTS_QUERY_FAILED");
        JsonNode context = HarnessJson.mapper().valueToTree(result.data()).path("previsitContext");
        if (!context.isObject()) throw new IllegalStateException("CUSTOMER_FACTS_CONTRACT_INVALID");
        Runnable releaseGuard = access.resultReleaseGuard(customerRef, result);
        String response = render(alias, context, query);
        releaseGuard.run();
        return Map.of("status", "ANSWERED", "responseScope", "CUSTOMER_FACTS", "response", response,
                "customerRef", alias, "evidenceRefs", List.of(result.requestId(), result.traceId()),
                "runId", runId, "toolResults", List.of(Map.of("data", Map.of(
                        "capabilityId", ServicePreparation.CAPABILITY,
                        "resultStatus", result.status().name(), "requestId", result.requestId(),
                        "traceId", result.traceId(), "result", result.data()))));
    }

    private static String render(String alias, JsonNode context, String query) {
        JsonNode detail = wealthDetail(context.path("profiles"));
        boolean wantsHoldings = query.contains("持仓");
        boolean wantsMaturities = query.contains("到期") || query.contains("近期") || query.contains("资金动向");
        boolean wantsProfile = query.contains("画像") || query.contains("概览") || query.contains("情况")
                || query.contains("风险测评") || query.contains("资产概况") || (!wantsHoldings && !wantsMaturities);
        String subject = wantsHoldings && wantsMaturities ? "持仓与到期事项"
                : wantsHoldings ? "持仓" : wantsMaturities ? "到期事项" : "客户画像";
        var out = new StringBuilder("## ").append(alias).append(" · ").append(subject).append("\n\n");
        JsonNode risk = detail.path("riskAssessment");
        String tier = text(detail,"customerTier");
        if (wantsProfile && !"待核实".equals(tier)) out.append("- 客户层级：").append(tier).append("\n");
        if (wantsProfile && risk.isObject()) out.append("- 风险测评：").append(text(risk,"riskLevel"))
                .append("，有效期至 ").append(text(risk,"validUntil")).append("\n");
        JsonNode demographics = wealthProfile(context.path("profiles")).path("demographics");
        if (wantsProfile && demographics.isObject()) out.append("- 基本画像：年龄段 ").append(text(demographics,"ageBand"))
                .append("，职业 ").append(text(demographics,"occupationCategory"))
                .append("，城市 ").append(text(demographics,"city")).append("\n");
        JsonNode preferences = wealthProfile(context.path("profiles")).path("preferences");
        if (wantsProfile && preferences.isObject()) out.append("- 服务偏好：投资期限 ").append(text(preferences,"investmentHorizon"))
                .append("，流动性要求 ").append(text(preferences,"liquidityNeeds"))
                .append("，沟通方式 ").append(text(preferences,"communicationPreference")).append("\n");
        JsonNode portfolio = detail.path("portfolioSummary");
        if ((wantsProfile || wantsHoldings) && portfolio.isObject()) out.append("- 资产概况：总资产 ").append(amount(portfolio,"totalAssets"))
                .append("，可用资金 ").append(amount(portfolio,"availableCash"))
                .append("，投资资产 ").append(amount(portfolio,"investedAssets")).append("\n");
        JsonNode holdings = detail.path("holdings");
        if (wantsHoldings && holdings.isArray() && !holdings.isEmpty()) {
            out.append("\n### 持仓\n\n| 产品 | 余额 | 数据时点 |\n|---|---:|---|\n");
            holdings.forEach(item -> out.append("| ").append(text(item,"productName")).append(" | ")
                    .append(amount(item,"balance")).append(" | ").append(text(item,"dataAsOf")).append(" |\n"));
        } else if (query.contains("持仓")) out.append("\n当前来源未返回持仓明细。\n");
        JsonNode events = detail.path("recentEvents");
        if ((!events.isArray() || events.isEmpty()) && detail.path("maturities").isArray())
            events = detail.path("maturities");
        if (wantsMaturities && events.isArray() && !events.isEmpty()) {
            out.append("\n### 近期事项\n\n");
            events.forEach(item -> out.append("- ").append(event(item)).append("\n"));
        } else if (query.contains("到期")) out.append("\n当前来源未返回可核验的到期事项。\n");
        out.append("\n以上为获授权来源快照，不代表当前余额；缺失字段未作推断。");
        return out.toString();
    }

    private static JsonNode wealthDetail(JsonNode profiles) {
        return wealthProfile(profiles).path("detail");
    }
    private static JsonNode wealthProfile(JsonNode profiles) {
        if (profiles.isArray()) for (JsonNode profile : profiles)
            if ("wealth".equals(profile.path("profileType").asText())) return profile;
        return HarnessJson.mapper().createObjectNode();
    }
    private static String event(JsonNode item) {
        String type = text(item,"type"), date = text(item,"date");
        if ("待核实".equals(type)) type = text(item,"productName");
        if ("待核实".equals(date)) date = text(item,"maturityDate");
        String amount = amount(item,"amount");
        return type + ("待核实".equals(date) ? "" : "（" + date + "）")
                + ("待核实".equals(amount) ? "" : "，金额 " + amount);
    }
    private static String amount(JsonNode node, String field) {
        JsonNode value=node.path(field); if(!value.isNumber()) return "待核实";
        String currency=node.path("currency").asText("");
        return value.asText() + (currency.isBlank()?"":" "+currency);
    }
    private static String text(JsonNode node, String field) {
        String value=node.path(field).asText(""); return value.isBlank()?"待核实":value;
    }
}
