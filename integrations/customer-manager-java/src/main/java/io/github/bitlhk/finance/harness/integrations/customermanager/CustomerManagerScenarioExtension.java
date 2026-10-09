package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.examples.wealth.*;
import io.github.bitlhk.finance.harness.providers.customercrm.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Declarative managed-Host extension. Browser input cannot provide authority, credentials or asset paths. */
public final class CustomerManagerScenarioExtension implements ManagedScenarioExtensionV01 {
    public static final String ID = "customer-manager-service-preparation.v1";
    private static final Set<String> ACTIONS = Set.of("chat", "select", "prepare", "read", "review", "versions", "read-version", "replay");

    @Override public String id() { return ID; }
    @Override public Set<String> actions() { return ACTIONS; }

    @Override public Session open(Context context) throws Exception {
        Path mappingFile = outsideArtifact(requiredPath("FH_CRM_EMPLOYEE_MAPPING"), context.artifactRoot());
        Path reports = outsideArtifact(context.privateStateRoot().resolve("service-preparation-reports"), context.artifactRoot());
        String owner = required(context.ownerRef(), "OWNER_REQUIRED");
        var mapping = (java.util.function.Supplier<CustomerCrmEmployeeMapping>) () -> CustomerCrmEmployeeMapping.load(mappingFile, owner);
        CustomerCrmClient client = new CustomerCrmClient(URI.create(requiredEnv("FH_CRM_URL")),
                Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("FH_CRM_TIMEOUT_MILLIS", "8000"))),
                () -> requiredEnv("FH_CRM_SERVICE_TOKEN"));
        CustomerCrmProvider provider = new CustomerCrmProvider(owner, client, () -> mapping.get().authority(), Clock.systemUTC());
        String sessionId = UUID.randomUUID().toString();
        var access = new DirectCrmPreparationAccess(owner, sessionId, provider);
        InstalledPreparationMethod method = installedMethod(context.artifactRoot());
        var model = Objects.requireNonNull(context.model(), "MODEL_CONNECTION_REQUIRED");
        ServicePreparation task = new ServicePreparation(owner, access,
                () -> { var value=mapping.get(); return new ServicePreparation.Mapping(value.authority().revision(), value.customers()); },
                new OpenJiuwenReportGenerator(model.clientProvider(), model.apiKey(), model.apiBase(), model.modelName()),
                reports, Map.of("capability", "wealth-manager-previsit@0.1.0", "provider", "customer-crm-readonly@0.1.0-SNAPSHOT",
                        "runtime", "openjiuwen-java@0.1.15"), method);
        return new Managed(owner, sessionId, task, method);
    }

    private static final class Managed implements Session {
        private final String owner, sessionId;
        private final ServicePreparation task;
        private final InstalledPreparationMethod method;
        private String selectedCustomer;
        private volatile boolean closed;
        Managed(String owner, String sessionId, ServicePreparation task, InstalledPreparationMethod method) {
            this.owner=owner; this.sessionId=sessionId; this.task=task; this.method=method;
        }
        @Override public String sessionId() { return sessionId; }
        @Override public Map<String,Object> bootstrap() {
            current(); return Map.of("scenarioId", ID, "sessionId", sessionId, "customers", task.customers(owner),
                    "steps", List.of("CUSTOMER_AND_GOAL", "FACT_COLLECTION", "PREPARATION_REPORT", "MANAGER_REVIEW"),
                    "method", Map.of("id", method.id(), "version", method.version(), "digest", method.digest(), "lockDigest", method.lockDigest()));
        }
        @Override public synchronized Map<String,Object> call(String action, JsonNode body) throws Exception {
            current(); if (!ACTIONS.contains(action) || body == null || !body.isObject()) throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            return switch (action) {
                case "chat" -> {
                    exactChat(body);
                    yield chat(text(body,"query"));
                }
                case "select" -> {
                    exact(body,"customerAlias","goal");
                    selectedCustomer=text(body,"customerAlias");
                    yield Map.of("generation",task.select(owner,selectedCustomer,text(body,"goal")),"status","SELECTED");
                }
                case "prepare" -> { exact(body,"generation"); yield value(task.prepare(owner,integer(body,"generation"))); }
                case "read", "replay" -> { exact(body,"reportId"); yield value(task.read(owner,text(body,"reportId"))); }
                case "review" -> { exact(body,"reportId","revision","opinion"); yield value(task.review(owner,text(body,"reportId"),integer(body,"revision"),text(body,"opinion"))); }
                case "versions" -> { exact(body,"reportId"); yield Map.of("reportId",text(body,"reportId"),"versions",task.versions(owner,text(body,"reportId"))); }
                case "read-version" -> { exact(body,"reportId","revision"); yield value(task.readVersion(owner,text(body,"reportId"),integer(body,"revision"))); }
                default -> throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            };
        }
        private Map<String,Object> chat(String query) throws Exception {
            List<String> customers=task.customers(owner);
            var decision=CustomerManagerDialogue.resolve(query,customers,selectedCustomer);
            if(decision.action()==CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES)
                return Map.of("status","ANSWERED","response","我可以列出你当前获授权服务的客户，并按指定客户和服务目标汇集资料与持仓事实，依据已安装的专业方法生成待复核的回访准备建议。当前不支持产品推荐、CRM 写入、消息发送或交易办理。");
            if(decision.action()==CustomerManagerDialogue.Action.LIST_CUSTOMERS)
                return Map.of("status","ANSWERED","response","你当前可服务的客户有："+displayNames(customers)+"。你可以直接告诉我客户姓名和本次服务目标。","customers",customers);
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_MULTIPLE)
                return Map.of("status","NEED_CLARIFICATION","response","本轮提到了多位客户，请先明确要服务哪一位："+displayNames(decision.mentioned())+"。");
            if(decision.action()==CustomerManagerDialogue.Action.UNSUPPORTED_PRODUCT)
                return Map.of("status","UNSUPPORTED","response","当前场景只安装了客户资料与访前服务准备能力，尚未安装产品查询、适当性筛选或推荐能力。我可以先汇集授权客户事实并准备沟通重点。");
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_CUSTOMER)
                return Map.of("status","NEED_CLARIFICATION","response","请告诉我要查看哪位获授权客户。你也可以先问“我负责哪些客户？”。");
            String customer=decision.customer();
            selectedCustomer=customer;
            long generation=task.select(owner,customer,query);
            JsonNode report=task.prepare(owner,generation);
            String status=report.path("status").asText();
            String response="READY_FOR_REVIEW".equals(status)
                    ? "我已依据当前获授权的客户事实和专业方法，为 "+displayName(customer)+" 整理了回访准备建议。请结合事实来源和待核实事项进行复核。"
                    : "已保留 "+displayName(customer)+" 的有效客户事实，但服务准备建议未完整生成；请查看缺项和失败说明。";
            var result=new LinkedHashMap<String,Object>();
            result.put("status",status);result.put("response",response);result.put("customer",customer);
            result.put("report",value(report));return Map.copyOf(result);
        }
        @Override public Map<String,Object> status() {
            var value=new LinkedHashMap<String,Object>(bootstrap());value.put("status","ACTIVE");return value;
        }
        @Override public void close() { if(!closed){closed=true;task.close();} }
        private void current(){if(closed)throw new SecurityException("SCENARIO_SESSION_CLOSED");}
    }

    static String displayName(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.startsWith("合成客户") && normalized.length() > 4 ? normalized.substring(4) : normalized;
    }
    static String displayNames(Collection<String> values) {
        return String.join("、", values.stream().map(CustomerManagerScenarioExtension::displayName).toList());
    }

    private static InstalledPreparationMethod installedMethod(Path root) throws Exception {
        JsonNode lock = HarnessJson.mapper().readTree(Files.readAllBytes(root.resolve("service-lock.json")));
        String lockDigest = "sha256:" + requiredEnv("FH_EXPECTED_LOCK");
        String id, version, relative;
        if (lock.has("method")) {
            id=lock.path("method").path("assetId").asText(); version=lock.path("method").path("version").asText();
            relative="assets/private-methods/"+id+"/"+version+"/skills/"+id+"/SKILL.md";
        } else {
            id="customer-manager-service-preparation"; version="0.1.0";
            relative="assets/domain-packs/wealth/service-preparation/skills/customer-manager-service-preparation/SKILL.md";
        }
        String digest=lock.path("files").path(relative).asText();
        if (digest.isBlank()) throw new SecurityException("INSTALLED_METHOD_NOT_LOCKED");
        return InstalledPreparationMethod.load(id,version,root.resolve(relative),"sha256:"+digest,lockDigest);
    }
    private static Map<String,Object> value(JsonNode node) { return HarnessJson.mapper().convertValue(node,new TypeReference<>(){}); }
    private static Path requiredPath(String name){return Path.of(requiredEnv(name)).toAbsolutePath().normalize();}
    private static Path outsideArtifact(Path path,Path artifact){if(path.startsWith(artifact.toAbsolutePath().normalize()))throw new SecurityException("PRIVATE_STATE_OUTSIDE_ARTIFACT_REQUIRED");return path;}
    private static String requiredEnv(String name){return required(System.getenv(name),name+"_REQUIRED");}
    private static String required(String value,String code){if(value==null||value.isBlank())throw new IllegalArgumentException(code);return value.trim();}
    private static String text(JsonNode body,String name){return required(body.path(name).asText(null),"SCENARIO_FIELD_REQUIRED");}
    private static int integer(JsonNode body,String name){if(!body.path(name).canConvertToInt())throw new IllegalArgumentException("SCENARIO_FIELD_INVALID");return body.path(name).asInt();}
    private static void exact(JsonNode body,String... names){Set<String> expected=Set.of(names),actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw new IllegalArgumentException("SCENARIO_BODY_INVALID");}
    static void exactChat(JsonNode body) {
        Set<String> actual = new HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of("query")) && !actual.equals(Set.of("query", "runId"))) {
            throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
        }
    }
}
