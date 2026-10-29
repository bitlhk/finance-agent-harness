package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import java.util.*;

/** Actions backed by the admitted artifact's Lock, not the UI selection or model claims. */
record ConversationCapabilities(Set<ConversationIntent.Kind> actions, List<String> capabilityIds,
        Set<ConversationIntent.Kind> directReadActions) {
    // Legacy/internal callers without a trusted action policy retain confirmation.
    ConversationCapabilities(Set<ConversationIntent.Kind> actions, List<String> capabilityIds) {
        this(actions, capabilityIds, Set.of());
    }
    ConversationCapabilities {
        actions = Set.copyOf(actions);
        capabilityIds = List.copyOf(capabilityIds);
        directReadActions = Set.copyOf(directReadActions);
        if (!actions.containsAll(directReadActions)
                || !Set.of(ConversationIntent.Kind.PREPARE, ConversationIntent.Kind.PRODUCT_REVIEW).containsAll(directReadActions))
            throw new SecurityException("CONVERSATION_ACTION_POLICY_INVALID");
    }
    static ConversationCapabilities fromLock(JsonNode lock) {
        if (!"local-agent-service-lock.v0.1".equals(lock.path("schemaVersion").asText())
                || !lock.path("capabilities").isArray())
            throw new SecurityException("CONVERSATION_INSTALLED_CAPABILITIES_INVALID");
        Set<String> ids = new TreeSet<>();
        for (JsonNode item : lock.path("capabilities")) {
            if (!item.isTextual() || item.asText().isBlank() || !ids.add(item.asText()))
                throw new SecurityException("CONVERSATION_INSTALLED_CAPABILITIES_INVALID");
        }
        var actions = EnumSet.noneOf(ConversationIntent.Kind.class);
        if (ids.contains(CustomerCrmProvider.CAPABILITY)) {
            actions.add(ConversationIntent.Kind.PREPARE);
            if (ids.contains(CustomerCrmProvider.ALLOCATION_CAPABILITY))
                actions.add(ConversationIntent.Kind.PRODUCT_REVIEW);
        }
        // These two registered CRM actions are QUERY-only, single-customer draft production.
        // Do not infer exemption for future actions from their names or model/Skill claims.
        return new ConversationCapabilities(actions, new ArrayList<>(ids), actions);
    }
    void require(ConversationIntent.Kind kind) {
        if (!actions.contains(kind)) throw new SecurityException("CONVERSATION_CAPABILITY_NOT_INSTALLED");
    }
    boolean requiresConfirmation(ConversationIntent.Kind kind) {
        require(kind);
        return !directReadActions.contains(kind);
    }
    String description() {
        String result = "可以查看你当前获授权的客户名单";
        if (actions.contains(ConversationIntent.Kind.PREPARE))
            result += "；在用户明确提出回访准备目标和客户后汇集客户资料与持仓事实，生成待复核的回访准备建议。没有独立的只查资料或持仓动作，不能将它列成可直接执行的选项；用户只想查看时应说明此边界，询问是否改为回访准备，不能自动替用户执行";
        if (actions.contains(ConversationIntent.Kind.PRODUCT_REVIEW))
            result += capabilityIds.contains("wealth.products.query")
                    ? "；在金额、期限和渠道明确后，结合授权客户事实读取独立产品来源并核对条件。当前为Demo/Shadow来源，未核实银行在售资格的产品只展示为待核实事实，不是可推荐或可购买候选"
                    : "；在金额、期限和渠道明确后，从合成产品目录筛选待复核候选";
        return result + "。以上为本制品已装配能力，实际执行仍需核验当前授权。我不会执行购买、CRM写入、消息发送或交易办理。";
    }
}
