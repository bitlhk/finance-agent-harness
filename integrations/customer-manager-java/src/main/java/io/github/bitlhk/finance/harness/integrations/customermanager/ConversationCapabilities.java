package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import java.util.*;

/** Actions backed by the admitted artifact's Lock, not the UI selection or model claims. */
record ConversationCapabilities(Set<ConversationIntent.Kind> actions, List<String> capabilityIds) {
    ConversationCapabilities {
        actions = Set.copyOf(actions);
        capabilityIds = List.copyOf(capabilityIds);
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
        return new ConversationCapabilities(actions, new ArrayList<>(ids));
    }
    void require(ConversationIntent.Kind kind) {
        if (!actions.contains(kind)) throw new SecurityException("CONVERSATION_CAPABILITY_NOT_INSTALLED");
    }
    String description() {
        String result = "可以查看你当前获授权的客户名单";
        if (actions.contains(ConversationIntent.Kind.PREPARE))
            result += "；可在用户确认回访准备任务后汇集客户资料与持仓事实，生成待复核的回访准备建议。没有独立的只查资料或持仓动作，不能将它列成可直接执行的选项；用户只想查看时应说明此边界，询问是否改为回访准备，不能自动替用户执行";
        if (actions.contains(ConversationIntent.Kind.PRODUCT_REVIEW))
            result += "；在金额、期限和渠道明确后，从合成产品目录筛选待复核候选";
        return result + "。以上为本制品已装配能力，实际执行仍需核验当前授权。我不会执行购买、CRM写入、消息发送或交易办理。";
    }
}
