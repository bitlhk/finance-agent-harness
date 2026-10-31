package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationKnowledge;
import io.github.bitlhk.finance.harness.integrations.knowledge.ManagedKnowledgeQueries;
import io.github.bitlhk.finance.harness.providers.knowledge.KnowledgeProvider;
import java.util.*;

final class ManagerKnowledgeEvidence implements PreparationKnowledge {
    static final String PREVISIT = "客户经理开展服务准备时，制度对事实核实、适当性和人工复核有哪些要求？请提供原文条款。";
    static final String PRODUCTS = "产品服务准备中，制度对风险匹配、销售资格、信息核实和人工复核有哪些要求？请提供原文条款。";
    private final ManagedKnowledgeQueries knowledge;

    ManagerKnowledgeEvidence(ManagedScenarioExtensionV01.Context context) throws Exception {
        var peers = new HashMap<>(KypPreparationBootstrap.installation(context.artifactRoot()));
        peers.remove(KnowledgeProvider.ID);
        knowledge = new ManagedKnowledgeQueries(context, peers, Set.of(PREVISIT, PRODUCTS));
    }

    public CapabilityResultV1 query(String requestId, String traceId, boolean productReview) {
        return knowledge.query(requestId, traceId, productReview ? PRODUCTS : PREVISIT);
    }
    public void current() { knowledge.current(); }
    public void authorizeSaved(JsonNode report) {
        // Runtime authorization must never substitute for independent, receipt-specific history access.
        throw new SecurityException("JOINT_HISTORY_AUTHORIZATION_REQUIRED");
    }
    public void close() { knowledge.close(); }
}
