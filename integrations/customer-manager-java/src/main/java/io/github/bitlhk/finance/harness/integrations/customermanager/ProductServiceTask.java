package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.util.*;
import java.util.regex.Pattern;

/** Product exploration is independent from customer suitability and product-knowledge matching. */
final class ProductServiceTask {
    interface Access { CapabilityResultV1 query(String requestId, String traceId, String keyword); }
    interface Knowledge { Map<String,Object> answer(String question, String runId, JsonNode product); }
    private static final Pattern PRODUCT_CODE = Pattern.compile("(?<![A-Za-z0-9-])([0-9]{6,}[A-Z0-9-]*|[A-Z][A-Z0-9-]{2,63})(?![A-Za-z0-9-])");
    private static final Pattern SHARE_CLASS = Pattern.compile("(?i)(?<![A-Za-z0-9-])([A-Z][A-Z0-9-]{0,15})\\s*份额");
    private final Access access;
    private final Knowledge knowledge;

    ProductServiceTask(Access access, Knowledge knowledge) {
        this.access = Objects.requireNonNull(access);
        this.knowledge = knowledge;
    }

    static boolean handles(String query) {
        if (query == null) return false;
        boolean explore = query.contains("有哪些产品") || query.contains("有什么产品")
                || query.contains("产品列表") || query.contains("能看到") && query.contains("产品");
        boolean compare = query.contains("对比") || query.contains("比较") || query.contains("区别");
        boolean detail = (query.contains("产品") || query.contains("理财计划")
                || query.contains("份额") || PRODUCT_CODE.matcher(query).find())
                && (query.contains("详情") || query.contains("风险等级") || query.contains("费用")
                || query.contains("赎回") || query.contains("申购") || query.contains("起购")
                || query.contains("是什么") || query.contains("怎么样"));
        return explore || compare || detail;
    }

    Map<String,Object> run(String query, String runId) {
        String requestId = "fh-product-" + UUID.randomUUID();
        String traceId = "fh-product-trace-" + UUID.randomUUID();
        CapabilityResultV1 result = access.query(requestId, traceId, "");
        if (result.status() != CapabilityResultV1.Status.COMPLETED)
            throw new IllegalStateException("PRODUCT_FACTS_QUERY_FAILED");
        JsonNode data = HarnessJson.mapper().valueToTree(result.data());
        List<JsonNode> products = new ArrayList<>();
        data.path("products").forEach(products::add);
        List<JsonNode> selected = select(query, products);
        boolean compare = query.contains("对比") || query.contains("比较") || query.contains("区别");
        boolean list = query.contains("有哪些产品") || query.contains("有什么产品")
                || query.contains("产品列表") || query.contains("能看到") && query.contains("产品");
        var reply = new LinkedHashMap<String,Object>();
        reply.put("status", "ANSWERED");
        reply.put("responseScope", "PRODUCT_FACTS");
        reply.put("toolResults", List.of(tool(result)));
        reply.put("runId", runId);
        if (list && selected.isEmpty()) {
            reply.put("response", list(products));
            reply.put("products", products.stream().map(ProductServiceTask::publicProduct).toList());
            return Map.copyOf(reply);
        }
        if (compare && selected.size() < 2) {
            reply.put("status", "NEED_CLARIFICATION");
            reply.put("response", "请明确两款要比较的产品名称或产品代码。当前没有执行客户适当性判断。");
            reply.put("missingInputs", List.of("twoProducts"));
            return Map.copyOf(reply);
        }
        if (!compare && selected.size() != 1) {
            reply.put("status", "NEED_CLARIFICATION");
            reply.put("response", selected.isEmpty()
                    ? "当前产品目录中没有唯一匹配项，请提供产品名称或产品代码。"
                    : "匹配到多款产品，请提供唯一的产品名称或产品代码。");
            reply.put("missingInputs", List.of("product"));
            return Map.copyOf(reply);
        }
        if (compare) {
            reply.put("response", comparison(selected));
            reply.put("products", selected.stream().map(ProductServiceTask::publicProduct).toList());
            return Map.copyOf(reply);
        }
        JsonNode product = selected.getFirst();
        reply.put("response", detail(product));
        reply.put("product", publicProduct(product));
        if (knowledge != null) {
            Map<String,Object> evidence = knowledge.answer(query, runId, product);
            Object knowledgeResponse = evidence.get("response");
            if (knowledgeResponse instanceof String text && !text.isBlank())
                reply.put("response", reply.get("response") + "\n\n### 产品手册依据\n\n" + text);
            if (evidence.get("toolResults") instanceof List<?> values) {
                var tools = new ArrayList<Object>(); tools.addAll((List<?>) reply.get("toolResults")); tools.addAll(values);
                reply.put("toolResults", List.copyOf(tools));
            }
            for (String key : List.of("manifest", "productId", "buildId", "instanceId"))
                if (evidence.containsKey(key)) reply.put(key, evidence.get(key));
        }
        return Map.copyOf(reply);
    }

    private static List<JsonNode> select(String query, List<JsonNode> products) {
        var ids = new LinkedHashSet<String>();
        var matcher = PRODUCT_CODE.matcher(query);
        while (matcher.find()) ids.add(matcher.group(1));
        return products.stream().filter(product -> (ids.contains(product.path("productId").asText())
                || ids.contains(product.path("businessIdentity").path("managerProductCode").asText())
                || ids.contains(product.path("businessIdentity").path("registrationCode").asText())
                || ids.contains(product.path("businessIdentity").path("salesCode").asText())
                || matchesName(query, product.path("name").asText()))
                && matchesExplicitShare(query, product))
                .toList();
    }

    private static boolean matchesExplicitShare(String query, JsonNode product) {
        String actual = product.path("businessIdentity").path("shareClass").asText("").strip();
        if (actual.isBlank()) return true;
        String name = product.path("name").asText("");
        if (!name.isBlank() && query.contains(name)) return true;
        var requested = new LinkedHashSet<String>();
        var matcher = SHARE_CLASS.matcher(query);
        while (matcher.find()) requested.add(matcher.group(1).toUpperCase(Locale.ROOT));
        return requested.isEmpty() || requested.contains(actual.toUpperCase(Locale.ROOT));
    }

    private static boolean matchesName(String query, String name) {
        if (name == null || name.isBlank()) return false;
        if (query.contains(name)) return true;
        String core = name.replaceFirst("(?i)[A-Z][A-Z0-9-]{0,15}份额$", "")
                .replaceFirst("(?:固定收益类|混合类|权益类|商品及金融衍生品类)?理财计划$", "")
                .replaceFirst("理财产品$", "");
        return core.length() >= 8 && query.contains(core);
    }

    private static String list(List<JsonNode> products) {
        if (products.isEmpty()) return "当前产品来源没有返回可展示的产品。";
        var out = new StringBuilder("当前产品来源返回 ").append(products.size())
                .append(" 款产品。销售状态和额度仍需以当前业务系统为准。\n\n")
                .append("| 产品 | 类别/风险 | 起购金额 | 参考期限 | 渠道 |\n|---|---|---:|---:|---|\n");
        for (JsonNode product : products) out.append("| ").append(product.path("name").asText())
                .append(" (`").append(product.path("productId").asText()).append("`) | R")
                .append(product.path("riskLevel").asInt()).append(" | ")
                .append(product.path("minimumAmount").isNumber() ? product.path("minimumAmount").asText()+" 元" : "待核实")
                .append(" | ").append(product.path("termMonths").isIntegralNumber() ? product.path("termMonths").asText()+" 个月" : "待核实")
                .append(" | ").append(join(product.path("channels"))).append(" |\n");
        return out.toString();
    }

    private static String detail(JsonNode product) {
        return "**" + product.path("name").asText() + "**（`" + product.path("productId").asText() + "`）\n\n"
                + "- 产品事实风险等级：R" + product.path("riskLevel").asInt() + "\n"
                + "- 起购金额：" + (product.path("minimumAmount").isNumber() ? product.path("minimumAmount").asText()+" 元" : "待核实") + "\n"
                + "- 参考期限：" + (product.path("termMonths").isIntegralNumber() ? product.path("termMonths").asText()+" 个月" : "待核实") + "\n"
                + "- 可见渠道：" + join(product.path("channels")) + "\n"
                + "- 销售状态：" + product.path("salesStatus").asText("待核实") + "（来源可信级别："
                + product.path("salesTrust").asText("UNKNOWN") + "）\n\n"
                + "以上是产品来源事实，不代表客户已经通过适当性审核，也不构成购买建议。";
    }

    private static String comparison(List<JsonNode> products) {
        var out = new StringBuilder("以下仅比较产品来源中的事实，不判断客户适当性。\n\n")
                .append("| 产品 | 风险 | 起购金额 | 参考期限 | 渠道 |\n|---|---:|---:|---:|---|\n");
        for (JsonNode product : products) out.append("| ").append(product.path("name").asText()).append(" (`")
                .append(product.path("productId").asText()).append("`) | R").append(product.path("riskLevel").asInt())
                .append(" | ").append(product.path("minimumAmount").asText("待核实")).append(" | ")
                .append(product.path("termMonths").asText("待核实")).append(" 个月 | ")
                .append(join(product.path("channels"))).append(" |\n");
        return out.toString();
    }

    private static Map<String,Object> tool(CapabilityResultV1 result) {
        return Map.of("data", Map.of("capabilityId", "wealth.products.query",
                "resultStatus", result.status().name(), "requestId", result.requestId(),
                "traceId", result.traceId(), "result", result.data()));
    }

    private static Map<String,Object> publicProduct(JsonNode product) {
        return HarnessJson.mapper().convertValue(product, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private static String join(JsonNode values) {
        var result = new ArrayList<String>(); values.forEach(value -> result.add(value.asText()));
        return result.isEmpty() ? "待核实" : String.join("、", result);
    }
}
