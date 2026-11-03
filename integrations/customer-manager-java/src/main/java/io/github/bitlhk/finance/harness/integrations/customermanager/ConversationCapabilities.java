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
        if (ids.contains("knowledge.evidence.search")) actions.add(ConversationIntent.Kind.KNOWLEDGE_QUERY);
        // These two registered CRM actions are QUERY-only, single-customer draft production.
        // Do not infer exemption for future actions from their names or model/Skill claims.
        var directReads = EnumSet.copyOf(actions);
        directReads.remove(ConversationIntent.Kind.KNOWLEDGE_QUERY);
        return new ConversationCapabilities(actions, new ArrayList<>(ids), directReads);
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
            result += "；查看指定客户的资产概况、持仓、风险测评和近期事项，并生成回访/访前简报、沟通要点或话术";
        if (actions.contains(ConversationIntent.Kind.PRODUCT_REVIEW))
            result += capabilityIds.contains("wealth.products.query")
                    ? "；查看产品列表、详情和对比；在金额、期限和渠道明确后，结合授权客户事实核对候选条件。销售状态、额度和适当性仍以当前业务系统核验为准"
                    : "；在金额、期限和渠道明确后，从合成产品目录筛选待复核候选";
        if (capabilityIds.contains("knowledge.evidence.search"))
            result += "；查询当前构建已固定版本的制度文档，返回相关原文条款和来源位置。知识查询不读取或发送客户资料，也不替代具体业务审批";
        return result + "。以上为本制品已装配能力，实际执行仍需核验当前授权。我不会执行购买、CRM写入、消息发送或交易办理。";
    }
}
