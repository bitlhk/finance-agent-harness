package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.util.*;
import java.util.regex.Pattern;

/** Deterministic safety gate around the conversational surface; it never grants customer access. */
final class CustomerManagerDialogue {
    private static final Pattern NEGATED_PRODUCT_INTENT = Pattern.compile(
            "(?:不要|无需|不用|暂不|先不|不再|不做|不需要|取消|不)(?:[^，。；！？,.!?]{0,8})(?:推荐|筛选|候选|购买|买产品|理财产品)");
    private static final Pattern PREPARATION_INTENT = Pattern.compile(
            "(?:回访|访前|服务准备|沟通要点|访问要点|准备要点|客户服务)");

    enum Action { DESCRIBE_CAPABILITIES, LIST_CUSTOMERS, PREPARE, CANCEL_PRODUCT, CLARIFY_CUSTOMER, CLARIFY_MULTIPLE, PRODUCT_REVIEW }
    record Decision(Action action, String customer, List<String> mentioned) {}

    static Decision resolve(String query, List<String> customers, String selected) {
        Objects.requireNonNull(query); Objects.requireNonNull(customers);
        List<String> mentioned=customers.stream().filter(customer -> mentions(query, customer)).toList();
        if (query.matches(".*能(?:帮我|为我)?做什么.*") || query.contains("可以做什么")
                || query.contains("有什么能力") || query.contains("能提供什么")
                || query.contains("怎么用") || query.contains("如何使用") || query.contains("使用帮助"))
            return new Decision(Action.DESCRIBE_CAPABILITIES,null,mentioned);
        if (query.contains("客户") && (query.contains("我负责") || query.contains("哪些")
                || query.contains("列表") || query.contains("名单") || query.contains("名下")))
            return new Decision(Action.LIST_CUSTOMERS,null,mentioned);
        if (mentioned.size()>1) return new Decision(Action.CLARIFY_MULTIPLE,null,mentioned);
        boolean rejectsProduct = rejectsProductSelection(query);
        if (rejectsProduct && !requestsPreparation(query))
            return new Decision(Action.CANCEL_PRODUCT,mentioned.isEmpty()?selected:mentioned.getFirst(),mentioned);
        boolean productIntent = !rejectsProduct && (query.contains("推荐") || query.contains("买什么")
                || query.contains("理财产品") || query.contains("产品")
                && (query.contains("匹配") || query.contains("筛选") || query.contains("候选") || query.contains("适合")));
        if (productIntent)
            return new Decision(Action.PRODUCT_REVIEW,mentioned.isEmpty()?selected:mentioned.getFirst(),mentioned);
        String customer=mentioned.isEmpty()?selected:mentioned.getFirst();
        return customer==null || customer.isBlank()
                ? new Decision(Action.CLARIFY_CUSTOMER,null,mentioned)
                : new Decision(Action.PREPARE,customer,mentioned);
    }

    static boolean requestsPreparation(String query) {
        return query != null && PREPARATION_INTENT.matcher(query).find();
    }

    static boolean rejectsProductSelection(String query) {
        return query != null && NEGATED_PRODUCT_INTENT.matcher(query).find();
    }

    private static boolean mentions(String query, String displayName) {
        if (query.contains(displayName)) return true;
        String syntheticPrefix = "合成客户";
        return displayName.startsWith(syntheticPrefix)
                && displayName.length() > syntheticPrefix.length()
                && query.contains(displayName.substring(syntheticPrefix.length()));
    }

    private CustomerManagerDialogue() {}
}
