package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.util.*;

/** Deterministic safety gate around the conversational surface; it never grants customer access. */
final class CustomerManagerDialogue {
    enum Action { DESCRIBE_CAPABILITIES, LIST_CUSTOMERS, PREPARE, CLARIFY_CUSTOMER, CLARIFY_MULTIPLE, UNSUPPORTED_PRODUCT }
    record Decision(Action action, String customer, List<String> mentioned) {}

    static Decision resolve(String query, List<String> customers, String selected) {
        Objects.requireNonNull(query); Objects.requireNonNull(customers);
        List<String> mentioned=customers.stream().filter(customer -> mentions(query, customer)).toList();
        if (query.contains("能做什么") || query.contains("可以做什么") || query.contains("有什么能力")
                || query.contains("怎么用") || query.contains("帮助"))
            return new Decision(Action.DESCRIBE_CAPABILITIES,null,mentioned);
        if (query.contains("客户") && (query.contains("我负责") || query.contains("哪些")
                || query.contains("列表") || query.contains("名单") || query.contains("名下")))
            return new Decision(Action.LIST_CUSTOMERS,null,mentioned);
        if (mentioned.size()>1) return new Decision(Action.CLARIFY_MULTIPLE,null,mentioned);
        if (query.contains("推荐") || query.contains("产品匹配") || query.contains("买什么")
                || query.contains("理财产品") || query.contains("适合的产品"))
            return new Decision(Action.UNSUPPORTED_PRODUCT,null,mentioned);
        String customer=mentioned.isEmpty()?selected:mentioned.getFirst();
        return customer==null || customer.isBlank()
                ? new Decision(Action.CLARIFY_CUSTOMER,null,mentioned)
                : new Decision(Action.PREPARE,customer,mentioned);
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
