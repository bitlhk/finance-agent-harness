package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private static final Set<String> ACTIONS = Set.of("chat", "select", "prepare", "read", "latest", "review", "versions", "read-version", "replay", "note");

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
        private String selectedCustomer, selectedGoal;
        private String pendingProductCustomer;
        private final Map<String,Object> pendingProductArguments=new LinkedHashMap<>();
        private final Map<String,ObjectNode> taskContexts=new HashMap<>();
        private volatile boolean closed;
        Managed(String owner, String sessionId, ServicePreparation task, InstalledPreparationMethod method) throws Exception {
            this.owner=owner; this.sessionId=sessionId; this.task=task; this.method=method;
            var latest=task.latest(owner);
            if(latest.isPresent()) {
                JsonNode report=latest.get();selectedCustomer=report.path("customerAlias").asText(null);
                selectedGoal=report.path("goal").asText(null);
                if(selectedCustomer!=null&&report.path("taskContext").isObject())
                    taskContexts.put(selectedCustomer,(ObjectNode)report.path("taskContext").deepCopy());
            }
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
                    selectedCustomer=text(body,"customerAlias");selectedGoal=text(body,"goal");
                    yield Map.of("generation",task.select(owner,selectedCustomer,selectedGoal,context(selectedCustomer)),"status","SELECTED");
                }
                case "prepare" -> { exact(body,"generation"); yield value(task.prepare(owner,integer(body,"generation"))); }
                case "read" -> { exact(body,"reportId"); yield value(task.read(owner,text(body,"reportId"))); }
                case "latest" -> {
                    exact(body);var latest=task.latest(owner);var result=new LinkedHashMap<String,Object>();
                    result.put("found",latest.isPresent());latest.ifPresent(report->result.put("report",value(report)));yield Map.copyOf(result);
                }
                case "replay" -> {
                    exactOneOf(body,Set.of("reportId"),Set.of("reportId","revision"));
                    Integer revision=body.has("revision")?integer(body,"revision"):null;
                    yield value(task.replay(owner,text(body,"reportId"),revision));
                }
                case "review" -> { exact(body,"reportId","revision","opinion"); yield value(task.review(owner,text(body,"reportId"),integer(body,"revision"),text(body,"opinion"))); }
                case "versions" -> { exact(body,"reportId");String id=text(body,"reportId");yield Map.of(
                        "reportId",id,"versions",task.versions(owner,id),"items",task.versionSummaries(owner,id)); }
                case "read-version" -> { exact(body,"reportId","revision"); yield value(task.readVersion(owner,text(body,"reportId"),integer(body,"revision"))); }
                case "note" -> {
                    exactOneOf(body,Set.of("reportId"),Set.of("reportId","revision"));
                    Integer revision=body.has("revision")?integer(body,"revision"):null;
                    yield value(task.note(owner,text(body,"reportId"),revision));
                }
                default -> throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            };
        }
        @Override public synchronized Map<String,Object> callStreaming(String action, JsonNode body, EventSink sink) throws Exception {
            current();
            if (!"chat".equals(action)) return call(action, body);
            exactChat(body);
            return chat(text(body,"query"), sink);
        }
        private Map<String,Object> chat(String query) throws Exception { return chat(query, event -> {}); }
        private Map<String,Object> chat(String query, EventSink sink) throws Exception {
            List<String> customers=task.customers(owner);
            var decision=CustomerManagerDialogue.resolve(query,customers,selectedCustomer);
            if(decision.action()==CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES)
                return Map.of("status","ANSWERED","response","我可以列出你当前获授权服务的客户，汇集资料与持仓事实并生成待复核的回访准备建议。你也可以提供客户、金额、期限和渠道，让我从合成产品目录中形成待复核候选；我不会执行购买、CRM 写入、消息发送或交易办理。");
            if(decision.action()==CustomerManagerDialogue.Action.LIST_CUSTOMERS)
                return Map.of("status","ANSWERED","response","你当前可服务的客户有："+displayNames(customers)+"。你可以直接告诉我客户姓名和本次服务目标。","customers",customers);
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_MULTIPLE)
                return Map.of("status","NEED_CLARIFICATION","response","本轮提到了多位客户，请先明确要服务哪一位："+displayNames(decision.mentioned())+"。");
            boolean product=decision.action()==CustomerManagerDialogue.Action.PRODUCT_REVIEW||pendingProductCustomer!=null;
            if(product) {
                String customer=decision.customer()!=null?decision.customer():pendingProductCustomer;
                if(customer==null||customer.isBlank())return Map.of("status","NEED_CLARIFICATION","response","请先告诉我要为哪位获授权客户筛选产品候选。");
                pendingProductCustomer=customer;pendingProductArguments.putAll(productArguments(query));
                var missing=new ArrayList<String>();if(!pendingProductArguments.containsKey("amount"))missing.add("拟配置金额");
                if(!pendingProductArguments.containsKey("horizonMonths"))missing.add("投资期限");
                if(!pendingProductArguments.containsKey("channel"))missing.add("办理渠道（手机银行或网点）");
                if(!missing.isEmpty())return Map.of("status","NEED_CLARIFICATION","response","为 "+displayName(customer)+" 筛选产品候选前，请补充："+String.join("、",missing)+"。候选只供经理复核，不会执行购买。");
                ObjectNode taskContext=updateContext(customer,query);
                selectedCustomer=customer;selectedGoal=query;long generation=task.select(owner,customer,query,taskContext);
                sink.emit(Map.of("type","CUSTOM","name","finance.progress","value",Map.of("stage","PRODUCT_POLICY_CHECK","label","正在核对产品目录与适当性条件")));
                JsonNode report=task.prepareProduct(owner,generation,Map.copyOf(pendingProductArguments),listener(sink));
                pendingProductCustomer=null;pendingProductArguments.clear();
                int eligible=report.path("productReview").path("eligibleProducts").size();
                var result=new LinkedHashMap<String,Object>();result.put("status",report.path("status").asText());
                result.put("response",eligible>0
                        ? "我已按明确的金额、期限和渠道完成确定性筛选，并形成 "+displayName(customer)+" 的待复核产品候选。请核对产品目录版本、适当性依据和排除原因。"
                        : "我已按明确的金额、期限和渠道完成确定性筛选，但当前没有通过规则校验的产品候选。请查看排除原因，不会用相邻产品替代。");
                result.put("customer",customer);result.put("report",value(report));return Map.copyOf(result);
            }
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_CUSTOMER)
                return Map.of("status","NEED_CLARIFICATION","response","请告诉我要查看哪位获授权客户。你也可以先问“我负责哪些客户？”。");
            String customer=decision.customer();
            ObjectNode taskContext=updateContext(customer,query);
            selectedCustomer=customer;selectedGoal=query;
            sink.emit(Map.of("type","CUSTOM","name","finance.progress","value",Map.of(
                    "stage","AUTHORIZATION_CONFIRMED","label","已核对客户服务范围")));
            long generation=task.select(owner,customer,query,taskContext);
            JsonNode report=task.prepare(owner,generation,listener(sink));
            String status=report.path("status").asText();
            String response="READY_FOR_REVIEW".equals(status)
                    ? "我已依据当前获授权的客户事实和专业方法，为 "+displayName(customer)+" 整理了回访准备建议。请结合事实来源和待核实事项进行复核。"
                    : "已保留 "+displayName(customer)+" 的有效客户事实，但服务准备建议未完整生成；请查看缺项和失败说明。";
            var result=new LinkedHashMap<String,Object>();
            result.put("status",status);result.put("response",response);result.put("customer",customer);
            result.put("report",value(report));return Map.copyOf(result);
        }
        private ObjectNode context(String customer) {
            return taskContexts.computeIfAbsent(customer,ignored->CustomerManagerTaskContext.empty());
        }
        private ObjectNode updateContext(String customer,String query) {
            ObjectNode updated=CustomerManagerTaskContext.update(context(customer),query);
            taskContexts.put(customer,updated);return updated;
        }
        private ServicePreparation.PreparationListener listener(EventSink sink) {
            return new ServicePreparation.PreparationListener(){
                @Override public void stage(String stage,JsonNode snapshot) throws Exception {
                    String label=switch(stage){case "FACTS_READY"->"客户事实已取得";case "REPORT_GENERATION_STARTED"->"正在生成待复核建议";default->stage;};
                    sink.emit(Map.of("type","CUSTOM","name","finance.progress","value",Map.of("stage",stage,"label",label)));
                    if("FACTS_READY".equals(stage))sink.emit(Map.of("type","CUSTOM","name","finance.facts","value",value(snapshot)));
                }
                @Override public void reportDelta(ServicePreparation.ReportDelta delta) throws Exception {
                    sink.emit(Map.of("type","CUSTOM","name","finance.report.delta","value",Map.of(
                            "section",delta.section(),"text",delta.text(),"factRefs",delta.factRefs())));
                }
            };
        }
        @Override public Map<String,Object> status() {
            var value=new LinkedHashMap<String,Object>(bootstrap());value.put("status","ACTIVE");
            if(selectedCustomer!=null)value.put("selectedCustomer",selectedCustomer);
            if(selectedGoal!=null)value.put("selectedGoal",selectedGoal);
            return value;
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
    static Map<String,Object> productArguments(String query) {
        var result=new LinkedHashMap<String,Object>();
        var amount=java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(万|元)").matcher(query);
        if(amount.find())result.put("amount",Double.parseDouble(amount.group(1))*("万".equals(amount.group(2))?10000:1));
        var months=java.util.regex.Pattern.compile("(\\d+)\\s*(年|个月|月)").matcher(query);
        if(months.find())result.put("horizonMonths",Integer.parseInt(months.group(1))*("年".equals(months.group(2))?12:1));
        if(query.contains("手机银行")||query.contains("手机端"))result.put("channel","手机银行");
        else if(query.contains("网点")||query.contains("柜面"))result.put("channel","网点");
        return Map.copyOf(result);
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
    private static void exactOneOf(JsonNode body,Set<String>... choices){Set<String> actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);for(Set<String> choice:choices)if(actual.equals(choice))return;throw new IllegalArgumentException("SCENARIO_BODY_INVALID");}
    static void exactChat(JsonNode body) {
        Set<String> actual = new HashSet<>();
        body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(Set.of("query")) && !actual.equals(Set.of("query", "runId"))) {
            throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
        }
    }
}
