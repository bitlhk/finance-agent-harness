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
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;

final class ManagerKnowledgeEvidence implements PreparationKnowledge {
    static final String PREVISIT = "客户经理开展服务准备时，制度对事实核实、适当性和人工复核有哪些要求？请提供原文条款。";
    static final String PRODUCTS = "产品服务准备中，制度对风险匹配、销售资格、信息核实和人工复核有哪些要求？请提供原文条款。";
    private final ManagedKnowledgeQueries knowledge;
    private final ManagedScenarioExtensionV01.Context context;
    private final PreparationAccess business;
    private final String session;
    private final String productName;
    private final int productVersion;
    private final ProductKnowledgeMatcher matcher;
    private final String productQuestion;

    ManagerKnowledgeEvidence(ManagedScenarioExtensionV01.Context context, FinanceProvider crm,
            PreparationAccess business, String session) throws Exception {
        this.context = context; this.business = business; this.session = session;
        JsonNode descriptor = installedDescriptor(context.artifactRoot());
        productName = descriptor.path("name").asText();
        productVersion = descriptor.path("version").asInt();
        matcher = "knowledge-product-release.v0.2".equals(descriptor.path("schemaVersion").asText())
                ? new ProductKnowledgeMatcher(descriptor) : null;
        productQuestion = matcher == null ? PRODUCTS : productQuestion(descriptor);
        var peers = new HashMap<String, FinanceProvider>();
        peers.put(CustomerCrmProvider.PROVIDER_ID, crm);
        if (business instanceof KypPreparationAccess kyp) peers.put(KypProductFactory.ID, kyp.productProvider());
        knowledge = new ManagedKnowledgeQueries(context, peers, Set.of(PREVISIT, PRODUCTS, productQuestion));
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
        if (productReview && matcher != null && business instanceof KypPreparationAccess kyp) {
            JsonNode products = kyp.productsFor(traceId);
            var decision = matcher.select(products, kyp.productChannelFor(traceId));
            if (decision == ProductKnowledgeMatcher.Decision.NO_KNOWLEDGE)
                return unavailable(requestId, traceId, "NO_BOUND_PRODUCT_KNOWLEDGE", false);
            if (decision == ProductKnowledgeMatcher.Decision.AMBIGUOUS)
                return unavailable(requestId, traceId, "KNOWLEDGE_PRODUCT_SELECTION_AMBIGUOUS", true);
            return knowledge.query(requestId, traceId, productQuestion);
        }
        return knowledge.query(requestId, traceId, productReview ? PRODUCTS : PREVISIT);
    }

    static String productQuestion(JsonNode descriptor) {
        JsonNode binding = descriptor.path("businessBinding");
        String name = descriptor.path("name").asText("").strip();
        String code = binding.path("product").path("managerProductCode").asText("").strip();
        var shares = new TreeSet<String>();
        for (JsonNode distribution : binding.path("distribution"))
            for (JsonNode share : distribution.path("shareClasses")) shares.add(share.asText());
        String share = String.join("、", shares);
        if (name.isBlank() || name.length() > 200 || !code.matches("[A-Za-z0-9-]{1,64}")
                || shares.isEmpty() || shares.size() > 16
                || shares.stream().anyMatch(value -> !value.matches("[A-Za-z0-9-]{1,32}")))
            throw new SecurityException("PRODUCT_IDENTITY_SOURCE_INVALID");
        return "请提供产品“" + name + "”（管理人产品代码 " + code + "，" + share
                + " 份额）的原文依据：产品类型与风险等级、适用投资者、申购起点、费用、申购赎回安排、"
                + "暂停或大额赎回、管理人提前终止，以及条款调整和费率公告的适用期间。"
                + "只返回该产品及该份额适用的原文；实时在售、额度、交易日历和客户适当性不由文档判断。";
    }

    static CapabilityResultV1 unavailable(String requestId, String traceId, String reason, boolean rejected) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, requestId, traceId,
                CapabilityResultV1.Status.COMPLETED, rejected
                        ? "本轮未能唯一确定产品，已拒绝引用产品知识。"
                        : "当前产品没有装配对应的固定版本知识。",
                Map.of("decision", rejected ? "KNOWLEDGE_REJECTED" : "KNOWLEDGE_NOT_AVAILABLE",
                        "evidence", List.of(), "reason", reason),
                List.of(reason), List.of(), "knowledge-host", Instant.now(), null, null);
    }

    String description(String installedCapabilities) {
        return "当前已装配知识：**" + productName + " · 第 " + productVersion + " 版**。"
                + "你可以直接询问该文档中的制度、规则、条款、义务或依据；回答会返回可核对的原文位置。\n\n"
                + installedCapabilities;
    }

    private static JsonNode installedDescriptor(Path root) throws Exception {
        JsonNode lock = HarnessJson.mapper().readTree(root.resolve("service-lock.json").toFile());
        String expected = lock.path("files").path("knowledge-product.json").asText();
        Path file = root.resolve("knowledge-product.json");
        if (!expected.matches("[a-f0-9]{64}") || Files.isSymbolicLink(file) || !Files.isRegularFile(file)
                || Files.size(file) > 262144) throw new SecurityException("KNOWLEDGE_PRODUCT_NOT_LOCKED");
        byte[] raw = Files.readAllBytes(file);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        JsonNode product = HarnessJson.mapper().readTree(raw);
        JsonNode descriptor = product.path("descriptor");
        JsonNode capabilities = descriptor.path("capabilities");
        String version = descriptor.path("schemaVersion").asText();
        if (!expected.equals(actual) || !Set.of("knowledge-product-release.v0.1", "knowledge-product-release.v0.2").contains(version)
                || descriptor.path("name").asText().isBlank() || descriptor.path("version").asInt() < 1
                || !capabilities.isArray() || capabilities.size() != 1
                || !"knowledge.evidence.search".equals(capabilities.get(0).asText()))
            throw new SecurityException("KNOWLEDGE_PRODUCT_NOT_LOCKED");
        if ("knowledge-product-release.v0.2".equals(version)) {
            String setExpected = lock.path("files").path("knowledge-product-set.json").asText();
            Path setFile = root.resolve("knowledge-product-set.json");
            String bindingExpected = lock.path("files").path(
                    "provider-configuration/knowledge-service/knowledge-binding.json").asText();
            Path bindingFile = root.resolve("provider-configuration/knowledge-service/knowledge-binding.json");
            if (!setExpected.matches("[a-f0-9]{64}") || !bindingExpected.matches("[a-f0-9]{64}")
                    || Files.isSymbolicLink(setFile) || Files.isSymbolicLink(bindingFile)
                    || !Files.isRegularFile(setFile) || !Files.isRegularFile(bindingFile))
                throw new SecurityException("KNOWLEDGE_PRODUCT_NOT_LOCKED");
            byte[] setRaw = Files.readAllBytes(setFile), bindingRaw = Files.readAllBytes(bindingFile);
            if (!setExpected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(setRaw)))
                    || !bindingExpected.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bindingRaw))))
                throw new SecurityException("KNOWLEDGE_PRODUCT_NOT_LOCKED");
            JsonNode set = HarnessJson.mapper().readTree(setRaw), binding = HarnessJson.mapper().readTree(bindingRaw);
            if (!"knowledge-product-set.v0.1".equals(set.path("schemaVersion").asText())
                    || !set.path("products").isArray() || set.path("products").size() != 1
                    || !set.path("products").get(0).equals(product)
                    || !"knowledge-binding.v0.2".equals(binding.path("schemaVersion").asText())
                    || !descriptor.path("businessBindingDigest").equals(binding.path("businessBindingDigest")))
                throw new SecurityException("KNOWLEDGE_PRODUCT_NOT_LOCKED");
        }
        return descriptor;
    }

    Map<String,Object> answerPolicyQuestion(String userQuestion, String runId, Set<String> customerAliases) {
        String publicQuestion = knowledge.publicQuestion(userQuestion, customerAliases);
        String requestId = "fh-request-" + UUID.randomUUID();
        String traceId = "fh-trace-" + UUID.randomUUID();
        CapabilityResultV1 result = knowledge.queryKnowledgeOnly(requestId, traceId, publicQuestion);
        JsonNode data = HarnessJson.mapper().valueToTree(result.data());
        boolean found = result.status() == CapabilityResultV1.Status.COMPLETED
                && "EVIDENCE_FOUND".equals(data.path("decision").asText())
                && data.path("evidence").isArray() && !data.path("evidence").isEmpty();
        var reply = new LinkedHashMap<String,Object>();
        reply.put("status", found ? "COMPLETED" : result.status() == CapabilityResultV1.Status.COMPLETED ? "NO_EVIDENCE" : "PARTIAL");
        reply.put("response", policyAnswer(data, found));
        reply.put("responseScope", "KNOWLEDGE_EVIDENCE");
        reply.put("toolResults", List.of(Map.of("data", Map.of(
                "capabilityId", KnowledgeProvider.SEARCH,
                "resultStatus", result.status().name(),
                "requestId", result.requestId(), "traceId", result.traceId(),
                "result", result.data()))));
        reply.put("manifest", knowledge.manifest()); reply.put("productId", knowledge.productId());
        reply.put("buildId", context.buildId()); reply.put("instanceId", context.instanceId());
        reply.put("runId", runId);
        return Map.copyOf(reply);
    }


    static String policyAnswer(JsonNode data, boolean found) {
        if (!found) return "当前固定版本的知识产品没有返回可用原文依据，因此不能确认这项制度结论。";
        var out = new StringBuilder("已从当前装配的制度知识中找到以下原文依据：\n\n");
        int displayed = 0;
        for (JsonNode item : data.path("evidence")) {
            String clause = item.path("clause").asText();
            String quote = item.path("quote").asText();
            if (quote.isBlank()) continue;
            if (++displayed > 5) break;
            out.append("- ");
            if (!clause.isBlank()) out.append("**").append(clause).append("**：");
            out.append(quote).append("\n");
        }
        if (displayed == 0) return "当前固定版本的知识产品没有返回可展示的原文依据，因此不能确认这项制度结论。";
        return out.append("\n以上为已发布文档的原文检索结果；具体客户或业务处理仍需结合当前授权和业务事实判断。").toString();
    }
    public void current() { knowledge.current(); }
    public void authorizeSaved(JsonNode report) {
        // Runtime authorization must never substitute for independent, receipt-specific history access.
        throw new SecurityException("JOINT_HISTORY_AUTHORIZATION_REQUIRED");
    }
    public void close() { knowledge.close(); }
}
