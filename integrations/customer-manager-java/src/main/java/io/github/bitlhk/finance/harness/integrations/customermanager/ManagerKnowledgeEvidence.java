package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationKnowledge;
import io.github.bitlhk.finance.harness.examples.wealth.PreparationAccess;
import io.github.bitlhk.finance.harness.integrations.knowledge.ManagedKnowledgeQueries;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenCapabilityInstaller.ProviderInvocation;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge.InvocationDefaults;
import io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmProvider;
import io.github.bitlhk.finance.harness.providers.kyp.KypProductFactory;
import io.github.bitlhk.finance.harness.providers.knowledge.KnowledgeProvider;
import java.util.*;

final class ManagerKnowledgeEvidence implements PreparationKnowledge {
    static final String PREVISIT = "客户经理开展服务准备时，制度对事实核实、适当性和人工复核有哪些要求？请提供原文条款。";
    static final String PRODUCTS = "产品服务准备中，制度对风险匹配、销售资格、信息核实和人工复核有哪些要求？请提供原文条款。";
    private final ManagedKnowledgeQueries knowledge;
    private final ManagedScenarioExtensionV01.Context context;
    private final PreparationAccess business;
    private final String session;

    ManagerKnowledgeEvidence(ManagedScenarioExtensionV01.Context context, FinanceProvider crm,
            PreparationAccess business, String session) throws Exception {
        this.context = context; this.business = business; this.session = session;
        var peers = new HashMap<String, FinanceProvider>();
        peers.put(CustomerCrmProvider.PROVIDER_ID, crm);
        if (business instanceof KypPreparationAccess kyp) peers.put(KypProductFactory.ID, kyp.productProvider());
        knowledge = new ManagedKnowledgeQueries(context, peers, Set.of(PREVISIT, PRODUCTS));
    }

    PreparationAccess installedAccess() {
        return new PreparationAccess() {
            public Snapshot authorize(String customer) { return business.authorize(customer); }
            public String providerId() { return business.providerId(); }
            public CapabilityResultV1 query(String requestId, String traceId, String customer,
                    String capability, Map<String, Object> arguments) {
                var snapshot = authorize(customer);
                boolean allocation = CustomerCrmProvider.ALLOCATION_CAPABILITY.equals(capability);
                var scopes = Set.of(allocation ? "wealth:allocation:read" : "wealth:previsit:read");
                var contexts = new HashMap<String, ProviderInvocation>();
                contexts.put(CustomerCrmProvider.PROVIDER_ID, new ProviderInvocation(new InvocationDefaults(
                        snapshot.principalRef(), customer, FinanceContextV1.PrincipalType.EMPLOYEE, "finance-harness",
                        allocation ? "WEALTH_PRODUCT_REVIEW" : "WEALTH_PREVISIT", snapshot.authenticationLevel(), scopes,
                        () -> { if (!snapshot.equals(authorize(customer))) throw new SecurityException("CRM_AUTHORITY_CHANGED");
                            return scopes; }, true), session));
                if (business instanceof KypPreparationAccess kyp) {
                    var grant = kyp.productGrant();
                    contexts.put(KypProductFactory.ID, new ProviderInvocation(new InvocationDefaults(
                            grant.principalId(), customer, grant.principalType(), "finance-harness", "WEALTH_PRODUCT_REVIEW",
                            snapshot.authenticationLevel(), grant.scopes(), () -> kyp.productGrant().scopes(), true),
                            kyp.productSession()));
                }
                return knowledge.withInstalledProviders(contexts,
                        () -> business.query(requestId, traceId, customer, capability, arguments));
            }
            public Runnable resultReleaseGuard(String customer, CapabilityResultV1 result) {
                return business.resultReleaseGuard(customer, result);
            }
            public void close() { business.close(); }
        };
    }

    ManagedScenarioExtensionV01.Session session(ManagedScenarioExtensionV01.Session delegate) {
        return new ManagedScenarioExtensionV01.Session() {
            public String sessionId() { return delegate.sessionId(); }
            public Map<String,Object> bootstrap() throws Exception { return delegate.bootstrap(); }
            public Map<String,Object> status() throws Exception { return delegate.status(); }
            public Map<String,Object> call(String action, JsonNode body) throws Exception {
                return result(delegate.call(action, body));
            }
            public Map<String,Object> callStreaming(String action, JsonNode body,
                    ManagedScenarioExtensionV01.Session.EventSink sink) throws Exception {
                return result(delegate.callStreaming(action, body, sink));
            }
            public void close() { delegate.close(); }
        };
    }

    private Map<String,Object> result(Map<String,Object> value) {
        if (!value.containsKey("report")) return value;
        JsonNode report = HarnessJson.mapper().valueToTree(value.get("report"));
        if (!report.has("knowledgeExecution")) return value;
        current();
        var result = new LinkedHashMap<String,Object>(value);
        var policy = report.path("knowledgeExecution");
        result.put("response", answer(report));
        result.put("toolResults", List.of(Map.of("data", Map.of("capabilityId", KnowledgeProvider.SEARCH,
                "resultStatus", policy.path("status").asText(),
                "requestId", policy.path("requestId").asText(), "traceId", policy.path("traceId").asText(),
                "result", policy.path("data")))));
        result.put("runtimeLock", knowledge.installationLock());
        result.put("manifest", knowledge.manifest()); result.put("productId", knowledge.productId());
        result.put("buildId", context.buildId()); result.put("instanceId", context.instanceId());
        result.put("runId", report.path("runId").asText());
        result.put("status", "READY".equals(report.path("explanationStatus").asText())
                && "READY_FOR_REVIEW".equals(report.path("status").asText()) ? "COMPLETED" : "PARTIAL");
        return Map.copyOf(result);
    }

    static String answer(JsonNode report) {
        var out = new StringBuilder("## ").append(report.path("customerAlias").asText()).append(" · 服务准备\n\n");
        if (!"READY".equals(report.path("explanationStatus").asText()))
            return out.append("本轮解释未通过校验，不能作为已完成的服务建议。已取得事实与失败记录保留供核对。").toString();
        var sections = new LinkedHashMap<String,String>();
        sections.put("communicationFocus", "沟通重点"); sections.put("preparationAdvice", "服务建议");
        sections.put("pendingItems", "待核实事项"); sections.put("reviewChecklist", "复核事项");
        sections.forEach((key, title) -> {
            out.append("### ").append(title).append("\n\n");
            for (var item : report.path("modelSuggestion").path(key))
                out.append("- ").append(item.isTextual() ? item.asText() : item.path("text").asText()).append("\n");
            out.append("\n");
        });
        out.append("待人工复核，未办理购买或写入客户系统。");
        return out.toString();
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
