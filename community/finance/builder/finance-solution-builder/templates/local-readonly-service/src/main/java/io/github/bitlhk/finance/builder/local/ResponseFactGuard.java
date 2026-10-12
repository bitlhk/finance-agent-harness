package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Rejects monetary claims that are absent from the current turn's tool evidence. */
final class ResponseFactGuard {
    private static final Pattern MONEY = Pattern.compile(
            "(?:(?:人民币|CNY|[¥￥])\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:元|人民币|CNY)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("^-?[0-9]+(?:\\.[0-9]+)?$");
    private ResponseFactGuard() { }

    static boolean grounded(String response, List<Object> tools) {
        var claims = amounts(response);
        if (claims.isEmpty()) return true;
        var evidence = new HashSet<BigDecimal>();
        collect(HarnessJson.mapper().valueToTree(tools), evidence);
        return evidence.containsAll(claims);
    }

    /**
     * Replaces an unsafe model narrative with a summary derived solely from completed tool
     * receipts. It deliberately contains no customer-provided amounts or credit decision.
     */
    static String conflictFallback(List<Object> tools) {
        Map<String, String> labels = Map.of(
                "smartaudit.personal.borrower-qualification.query", "借款人主体核验",
                "smartaudit.personal.income-verification.query", "收入核验",
                "smartaudit.personal.asset-liability.query", "资产负债核验",
                "smartaudit.personal.repayment-capacity.query", "偿债能力评估");
        List<String> completed = new ArrayList<>();
        for (Object tool : tools == null ? List.of() : tools) {
            var node = HarnessJson.mapper().valueToTree(tool).path("data");
            String label = labels.get(node.path("capabilityId").asText());
            if (label != null && "COMPLETED".equals(node.path("resultStatus").asText())) completed.add(label);
        }
        if (completed.isEmpty()) {
            return "已完成本轮查询，但模型说明包含当前工具证据之外的金额，已停止展示该说明。请以结构化结果和人工复核为准。";
        }
        return "本轮受控智能审核已完成：" + String.join("、", completed)
                + "。材料已进入审核工作底稿；真实性、准确性和完整性仍须由具备权限的人员人工复核。"
                + "本页面仅提供审核辅助，不形成审批、授信或放款决定。请展开“审核协作”和“结果与来源”查看结构化回执。";
    }

    private static Set<BigDecimal> amounts(String text) {
        var values = new HashSet<BigDecimal>();
        var matcher = MONEY.matcher(text == null ? "" : text);
        while (matcher.find()) values.add(decimal(matcher.group(1)));
        return values;
    }

    private static void collect(com.fasterxml.jackson.databind.JsonNode node, Set<BigDecimal> values) {
        if (node.isNumber()) values.add(node.decimalValue().stripTrailingZeros());
        else if (node.isTextual() && NUMBER.matcher(node.asText()).matches()) values.add(decimal(node.asText()));
        else if (node.isContainerNode()) node.elements().forEachRemaining(child -> collect(child, values));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value.replace(",", "")).stripTrailingZeros();
    }
}
