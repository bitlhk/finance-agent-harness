package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation.ConversationResponder;
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
    private static final Set<String> ACTIONS = Set.of("chat", "cancel", "select", "prepare", "read", "latest", "review", "versions", "read-version", "replay", "note");

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
                        "runtime", "openjiuwen-java@0.1.15"), method,
                io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.host(context));
        var capabilities = ConversationCapabilities.fromLock(HarnessJson.mapper().readTree(
                Files.readAllBytes(context.artifactRoot().resolve("service-lock.json"))));
        return new Managed(owner, sessionId, task, method, capabilities, context.instanceId(),
                new OpenJiuwenIntentProposer(model), () -> conversationScopeDigest(mapping.get()),
                new ScenarioEvidence(reports, ScenarioEvidence.host(context)));
    }

    static final class Managed implements Session {
        private final String owner, sessionId;
        private final ServicePreparation task;
        private final InstalledPreparationMethod method;
        private final ConversationCapabilities capabilities;
        private final String instanceId;
        private final OpenJiuwenIntentProposer intentProposer;
        private final java.util.function.Supplier<String> scopeDigest;
        private final ScenarioEvidence conversationEvidence;
        private String confirmedScope;
        private final ConversationConfirmation confirmation = new ConversationConfirmation(Clock.systemUTC());
        private long conversationEpoch;
        private PendingClarification clarification;
        private final ConversationDialogue dialogue = new ConversationDialogue();
        private List<Map<String,String>> previousDialogue = List.of();
        private String conversationContext;
        private record PendingClarification(ConversationConfirmation.Binding binding,
                ConversationIntent.Kind kind,String query,Instant expiresAt) {}
        private String selectedCustomer, selectedGoal;
        private String pendingProductCustomer;
        private final Map<String,Object> pendingProductArguments=new LinkedHashMap<>();
        private final Map<String,ObjectNode> taskContexts=new HashMap<>();
        private volatile boolean closed;
        private final Object operationLock = new Object();
        private final Set<String> operationIds = new HashSet<>();
        private Operation operation;
        private static final class Operation {
            final String id;
            long generation = -1;
            boolean cancelled, finished;
            Operation(String id) { this.id = id; }
        }
        Managed(String owner, String sessionId, ServicePreparation task, InstalledPreparationMethod method,
                ConversationCapabilities capabilities, String instanceId, OpenJiuwenIntentProposer intentProposer,
                java.util.function.Supplier<String> scopeDigest, ScenarioEvidence conversationEvidence) throws Exception {
            this.owner=owner; this.sessionId=sessionId; this.task=task; this.method=method;
            this.capabilities=capabilities;
            this.instanceId=required(instanceId,"INSTANCE_REQUIRED"); this.intentProposer=intentProposer;
            this.scopeDigest=Objects.requireNonNull(scopeDigest);
            this.conversationEvidence=Objects.requireNonNull(conversationEvidence);
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
                    "conversationContract", "manager-conversation.v1",
                    "conversationContracts", List.of("manager-conversation.v1", "manager-conversation.v2"),
                    "installedConversationActions", capabilities.actions().stream().map(Enum::name).sorted().toList(),
                    "steps", List.of("CUSTOMER_AND_GOAL", "FACT_COLLECTION", "PREPARATION_REPORT", "MANAGER_REVIEW"),
                    "method", Map.of("id", method.id(), "version", method.version(), "digest", method.digest(), "lockDigest", method.lockDigest()));
        }
        @Override public Map<String,Object> call(String action, JsonNode body) throws Exception {
            current(); if (!ACTIONS.contains(action) || body == null || !body.isObject()) throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            // Cancellation must not queue behind the streaming session monitor.
            if ("cancel".equals(action)) {
                exact(body,"runId"); String id = text(body,"runId");
                synchronized (operationLock) {
                    current();
                    if (operation == null || !operation.id.equals(id)) return Map.of("status","NOT_ACTIVE");
                    if (operation.cancelled) return Map.of("status","CANCELLED");
                    if (operation.finished) return Map.of("status","NOT_ACTIVE");
                    String status = operation.generation < 0 ? "CANCELLED" : task.cancel(owner,operation.generation);
                    operation.cancelled = "CANCELLED".equals(status);
                    if (operation.cancelled) { confirmation.invalidate();clarification=null;dialogue.clear(); }
                    return Map.of("status",status);
                }
            }
            return callSerial(action,body);
        }
        private synchronized Map<String,Object> callSerial(String action, JsonNode body) throws Exception {
            current();
            return switch (action) {
                case "chat" -> {
                    exactChat(body);
                    yield runChat(body,event -> {});
                }
                case "select" -> {
                    exact(body,"customerAlias","goal");
                    invalidateConfirmation();
                    clarification=null;
                    dialogue.clear();
                    clearPendingProduct();
                    selectedCustomer=text(body,"customerAlias");selectedGoal=text(body,"goal");
                    yield Map.of("generation",task.select(owner,selectedCustomer,selectedGoal,context(selectedCustomer)),"status","SELECTED");
                }
                case "prepare" -> { exact(body,"generation"); capabilities.require(ConversationIntent.Kind.PREPARE);
                    yield value(task.prepare(owner,integer(body,"generation"))); }
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
            return runChat(body, sink);
        }
        private Map<String,Object> runChat(JsonNode body, EventSink sink) throws Exception {
            Operation current = new Operation(body.has("runId") ? text(body,"runId") : UUID.randomUUID().toString());
            synchronized (operationLock) {
                if (operationIds.contains(current.id)) throw new IllegalArgumentException("RUN_ID_ALREADY_USED");
                if (operationIds.size() >= 128) throw new IllegalStateException("SESSION_OPERATION_LIMIT");
                operationIds.add(current.id); operation = current;
            }
            try {
                String requested = body.has("customerAlias") ? body.path("customerAlias").asText() : null;
                String context=body.path("conversationContext").textValue();
                if(!Objects.equals(context,conversationContext)) {
                    invalidateConfirmation();clarification=null;dialogue.clear();conversationContext=context;
                }
                if(!body.has("conversationContract")) { invalidateConfirmation();clarification=null;dialogue.clear(); }
                var dialogueBinding=new ConversationDialogue.Binding(context,requested,
                        body.path("reportId").asText()+":"+body.path("revision").asText(),scopeDigest.get());
                previousDialogue=dialogue.read(dialogueBinding,Instant.now());
                // Remembering a directory is not a grant to resend it to the model.
                // If authority is unavailable, plain dialogue remains possible without old data.
                if(!dialogue.disclosedObjects().isEmpty()) {
                    try {
                        if(!liveCustomers().containsAll(dialogue.disclosedObjects()))
                            throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
                    } catch(RuntimeException unavailable) {
                        dialogue.clear();clarification=null;
                        previousDialogue=dialogue.read(dialogueBinding,Instant.now());
                    }
                }
                var result = body.has("conversationContract") ? converse(body, requested, sink)
                        : chat(text(body,"query"),requested,sink);
                synchronized (operationLock) {
                    if (current.cancelled) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                    if (!dialogueBinding.scope().equals(scopeDigest.get())) dialogue.clear();
                    else if(result.containsKey("report")) {
                        dialogue.returnedReport();clarification=null;
                        dialogue.append(dialogueBinding,text(body,"query"),
                                "本轮报告已返回，执行、解释校验和复核状态以报告为准；不代表交易成功。",Instant.now());
                    } else if(result.containsKey("savedExplanation")) {
                        dialogue.append(dialogueBinding,text(body,"query"),"已解释指定保存版本，未重新查询业务。",Instant.now());
                    } else if (result.get("response") instanceof String answer)
                        dialogue.append(dialogueBinding,text(body,"query"),answer,Instant.now());
                    current.finished = true;
                }
                return result;
            }
            finally { synchronized (operationLock) { if(!current.finished) dialogue.clear(); current.finished = true; } }
        }
        private long selectOperation(String customer, String query, JsonNode context) {
            synchronized (operationLock) {
                if (operation.cancelled) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                if(confirmedScope!=null&&!confirmedScope.equals(scopeDigest.get())) throw new SecurityException("CRM_AUTHORITY_CHANGED");
                operation.generation = task.select(owner,customer,query,context);
                if(confirmedScope!=null&&!confirmedScope.equals(scopeDigest.get())) throw new SecurityException("CRM_AUTHORITY_CHANGED");
                return operation.generation;
            }
        }
        private Map<String,Object> chat(String query, String requested, EventSink sink) throws Exception {
            return chat(query, requested, sink, null);
        }
        private Map<String,Object> chat(String query, String requested, EventSink sink,
                CustomerManagerDialogue.Decision confirmedDecision) throws Exception {
            List<String> customers=task.customers(owner);
            if(requested!=null&&!requested.isBlank()&&!customers.contains(requested))
                throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
            String conversationalCustomer=requested!=null?(requested.isBlank()?null:requested):selectedCustomer!=null?selectedCustomer:pendingProductCustomer;
            var decision=confirmedDecision == null ? CustomerManagerDialogue.resolve(query,customers,conversationalCustomer) : confirmedDecision;
            if(decision.action()==CustomerManagerDialogue.Action.GREETING)
                return Map.of("status","ANSWERED","response","你好。我可以协助处理当前已接入的客户服务任务。你可以先问我能做什么，或查看当前获授权服务的客户。此次没有查询客户事实或生成报告。");
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_INTENT
                    && !(pendingProductCustomer!=null&&!productArguments(query).isEmpty()))
                return Map.of("status","NEED_CLARIFICATION","response","请说明你希望完成的具体工作。当前不会把这句话自动当作回访请求；若要查看已有结果，请从历史选择相应报告。","missingInputs",List.of("action"));
            if(CustomerManagerDialogue.rejectsProductSelection(query)) clearPendingProduct();
            if(decision.action()==CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES)
                return Map.of("status","ANSWERED","response",capabilities.description());
            if(decision.action()==CustomerManagerDialogue.Action.LIST_CUSTOMERS)
                return Map.of("status","ANSWERED","response","你当前可服务的客户有："+displayNames(customers)+"。你可以直接告诉我客户姓名和本次服务目标。","customers",customers);
            if(decision.action()==CustomerManagerDialogue.Action.CLARIFY_MULTIPLE)
                return Map.of("status","NEED_CLARIFICATION","response","本轮提到了多位客户，请先明确要服务哪一位："+displayNames(decision.mentioned())+"。");
            if(decision.action()==CustomerManagerDialogue.Action.CANCEL_PRODUCT) {
                clearPendingProduct();
                return Map.of("status","ANSWERED","response","已取消尚未完成的产品候选筛选。你可以继续准备客户回访或提出新的服务目标。");
            }
            boolean product=shouldUseProductFlow(decision.action(),pendingProductCustomer!=null,query);
            if(!product && pendingProductCustomer!=null) clearPendingProduct();
            if(product) {
                capabilities.require(ConversationIntent.Kind.PRODUCT_REVIEW);
                String customer=decision.customer()!=null?decision.customer():pendingProductCustomer;
                if(customer==null||customer.isBlank())return Map.of("status","NEED_CLARIFICATION","response","请先告诉我要为哪位获授权客户筛选产品候选。");
                pendingProductCustomer=customer;pendingProductArguments.putAll(productArguments(query));
                var missing=new ArrayList<String>();if(!pendingProductArguments.containsKey("amount"))missing.add("拟配置金额");
                if(!pendingProductArguments.containsKey("horizonMonths"))missing.add("投资期限");
                if(!pendingProductArguments.containsKey("channel"))missing.add("办理渠道（手机银行或网点）");
                if(!missing.isEmpty())return Map.of("status","NEED_CLARIFICATION","response","为 "+displayName(customer)+" 筛选产品候选前，请补充："+String.join("、",missing)+"。候选只供经理复核，不会执行购买。");
                ObjectNode taskContext=updateContext(customer,query);
                selectedCustomer=customer;selectedGoal=query;long generation=selectOperation(customer,query,taskContext);
                sink.emit(Map.of("type","CUSTOM","name","finance.progress","value",Map.of("stage","PRODUCT_POLICY_CHECK","label","正在核对产品目录与适当性条件")));
                JsonNode report=task.prepareProduct(owner,generation,Map.copyOf(pendingProductArguments),listener(sink));
                clearPendingProduct();
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
            capabilities.require(ConversationIntent.Kind.PREPARE);
            ObjectNode taskContext=updateContext(customer,query);
            selectedCustomer=customer;selectedGoal=query;
            sink.emit(Map.of("type","CUSTOM","name","finance.progress","value",Map.of(
                    "stage","AUTHORIZATION_CONFIRMED","label","已核对客户服务范围")));
            long generation=selectOperation(customer,query,taskContext);
            JsonNode report=task.prepare(owner,generation,listener(sink));
            String status=report.path("status").asText();
            String response="READY_FOR_REVIEW".equals(status)
                    ? "我已依据当前获授权的客户事实和专业方法，为 "+displayName(customer)+" 整理了回访准备建议。请结合事实来源和待核实事项进行复核。"
                    : "已保留 "+displayName(customer)+" 的有效客户事实，但服务准备建议未完整生成；请查看缺项和失败说明。";
            var result=new LinkedHashMap<String,Object>();
            result.put("status",status);result.put("response",response);result.put("customer",customer);
            result.put("report",value(report));return Map.copyOf(result);
        }
        private ConversationConfirmation.Binding conversationBinding() {
            return new ConversationConfirmation.Binding(owner, instanceId, method.lockDigest(), conversationEpoch, scopeDigest.get());
        }
        private void invalidateConfirmation() {
            synchronized(operationLock) { confirmation.invalidate(); conversationEpoch++; }
        }
        private boolean operationCancelled() {
            synchronized(operationLock) { return closed || operation == null || operation.cancelled; }
        }
        private Map<String,Object> converse(JsonNode body, String requested, EventSink sink) throws Exception {
            PendingClarification pending;
            synchronized(operationLock) {
                pending=clarification;
                if(pending!=null&&(!pending.binding().equals(conversationBinding())
                        ||!Instant.now().isBefore(pending.expiresAt()))) {pending=null;clarification=null;}
            }
            // A new message invalidates an old executable approval, not the conversation epoch.
            // Otherwise an intervening greeting makes the still-pending goal look stale.
            if(!body.has("confirmationId")) synchronized(operationLock) { confirmation.invalidate(); }
            Set<String> customers;
            try {
                customers=body.has("confirmationId")?liveCustomers():task.conversationCandidates(owner);
                if(requested!=null&&!requested.isBlank()&&!customers.contains(requested))
                    throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
            } catch(RuntimeException rejected) {
                confirmation.invalidate();
                throw rejected;
            }
            if(body.has("confirmationId")) {
                clarification=null;
                ConversationConfirmation.Plan plan;
                synchronized(operationLock) {
                    if(operationCancelled()) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                    plan=confirmation.claim(text(body,"confirmationId"),conversationBinding(),customers,capabilities.actions());
                }
                // The submitted text and object cannot rewrite a previously confirmed request.
                if(!plan.originalQuery().equals(text(body,"query")) || !plan.intent().objectRef().equals(requested))
                    throw new SecurityException("CONVERSATION_CONFIRMATION_REJECTED");
                if("CANCEL".equals(body.path("confirmationDecision").asText())) {
                    dialogue.forgetObject();
                    return Map.of("status","ANSWERED","response","已取消这项尚未执行的提案，未查询客户事实，也未生成新报告。");
                }
                clearPendingProduct();
                var action=plan.intent().kind()==ConversationIntent.Kind.PREPARE
                        ? CustomerManagerDialogue.Action.PREPARE : CustomerManagerDialogue.Action.PRODUCT_REVIEW;
                confirmedScope=plan.binding().scopeDigest();
                try { return chat(plan.originalQuery(),plan.intent().objectRef(),sink,
                        new CustomerManagerDialogue.Decision(action,plan.intent().objectRef(),List.of(plan.intent().objectRef()))); }
                finally { confirmedScope=null; }
            }
            clearPendingProduct();
            String query=text(body,"query");
            var proposedBinding=conversationBinding();
            String reportId=body.has("reportId")?text(body,"reportId"):null;
            Integer reportRevision=body.has("revision")?integer(body,"revision"):null;
            if(reportId!=null) {
                var saved=task.readVersion(owner,reportId,reportRevision);
                if(requested!=null&&!requested.isBlank()&&!requested.equals(saved.path("customerAlias").asText()))
                    throw new SecurityException("REPORT_OBJECT_MISMATCH");
            }
            Runnable checkIntent=()->{
                current();
                if(operationCancelled())throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                if(!proposedBinding.equals(conversationBinding()))throw new SecurityException("CRM_AUTHORITY_CHANGED");
                if(!dialogue.disclosedObjects().isEmpty()&&!liveCustomers().containsAll(dialogue.disclosedObjects()))
                    throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
            };
            var intentProof=conversationEvidence.begin(operation.id,owner,"CONVERSATION","INTENT",
                    "conversation.intent",Map.of("questionDigest",ScenarioEvidence.digest(query),
                            "dialogueDigest",ScenarioEvidence.digest(previousDialogue),
                            "continuityDigest",ScenarioEvidence.digest(dialogue.state())),
                    proposedBinding,"PLATFORM_SESSION_NOT_BUSINESS_GRANT");
            intentProof.check("INTENT_ENTRY",checkIntent,null);
            intentProof.invoked();
            ConversationIntent intent=intentProposer.propose(query,customers,capabilities.actions(),
                    dialogue.object()!=null?dialogue.object():requested==null||requested.isBlank()?null:requested,reportId,reportRevision,
                    pending==null?null:Map.of("kind",pending.kind().name(),"query",pending.query()),previousDialogue,
                    dialogue.state(),this::operationCancelled);
            intentProof.returned();
            intentProof.check("INTENT_RELEASE",checkIntent,intent);
            if(!proposedBinding.equals(conversationBinding())) throw new SecurityException("CRM_AUTHORITY_CHANGED");
            if(operationCancelled()) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
            boolean resolving=intent.kind()==ConversationIntent.Kind.RESOLVE_OBJECT;
            if(resolving) {
                if(pending==null) return naturalReply(query,ConversationResponder.Purpose.CLARIFY,List.of("intent"),
                        "没有可执行的待补充任务；结合previousDialogue解释当前支持的目标，不得称客户授权已失效，尚未执行业务。",proposedBinding,sink);
                capabilities.require(pending.kind());
                intent=new ConversationIntent(pending.kind(),intent.objectRef(),null,null);
            }
            // Model classification cannot grant access. Only non-data replies bypass CRM I/O;
            // every data/list/history/confirmation path still obtains current business authority.
            if(!Set.of(ConversationIntent.Kind.GREETING,ConversationIntent.Kind.CAPABILITIES,
                    ConversationIntent.Kind.CLARIFY,ConversationIntent.Kind.CANCEL_TASK,ConversationIntent.Kind.UNSUPPORTED).contains(intent.kind())) {
                customers=liveCustomers();
                if((requested!=null&&!requested.isBlank()&&!customers.contains(requested))
                        ||(intent.objectRef()!=null&&!customers.contains(intent.objectRef()))
                        ||!proposedBinding.equals(conversationBinding()))
                    throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
            }
            if(!Set.of(ConversationIntent.Kind.GREETING,ConversationIntent.Kind.CAPABILITIES,
                    ConversationIntent.Kind.LIST_OBJECTS).contains(intent.kind())) clarification=null;
            return switch(intent.kind()) {
                case RESOLVE_OBJECT -> throw new IllegalStateException("CONVERSATION_INTENT_INVALID");
                case GREETING -> naturalReply(query,ConversationResponder.Purpose.GREETING,List.of(),
                        "本轮仅问候，尚未查询客户事实或生成报告。",proposedBinding,sink);
                case CAPABILITIES -> naturalReply(query,ConversationResponder.Purpose.CAPABILITIES,List.of(),
                        "仅介绍当前装配能力，不表示已获得业务授权；尚未执行。",proposedBinding,sink);
                case LIST_OBJECTS -> {
                    dialogue.listed(customers);
                    yield Map.of("status","ANSWERED","response","你当前可服务的客户有："+displayNames(customers)+"。请告诉我本次客户和目标。","customers",customers);
                }
                case SELECT_OBJECT -> {
                    dialogue.select(intent.objectRef());
                    yield naturalReply(query,ConversationResponder.Purpose.CLARIFY,List.of("intent"),
                            "已记录本次客户；仅缺目标。请询问需要回访准备还是已装配的其他工作，不要求再次列名单；尚未查询客户事实。",proposedBinding,sink);
                }
                case CANCEL_TASK -> {
                    dialogue.forgetObject();clearPendingProduct();
                    yield Map.of("status","ANSWERED","response","已取消尚未执行的待办；未查询新事实，已保存报告不变。");
                }
                case UNSUPPORTED -> {
                    dialogue.forgetObject();
                    yield naturalReply(query,ConversationResponder.Purpose.UNSUPPORTED,List.of(),
                        "请求不在当前已装配能力内，未执行；只能说明已装配能力内的替代。",proposedBinding,sink);
                }
                case CLARIFY -> naturalReply(query,ConversationResponder.Purpose.CLARIFY,List.of("intent"),
                        "用户意图尚不能唯一确定；需澄清，不查询客户事实或生成报告。",proposedBinding,sink);
                case EXPLAIN_REPORT -> {
                    if(reportId==null||intent.reportRef()==null)yield Map.of("status","NEED_CLARIFICATION","response","请先选择要解释的已保存报告及版本；不会为了解释旧结果重新查询。","missingInputs",List.of("reportRef","revision"));
                    var answer=task.explainSaved(owner,reportId,reportRevision,query,
                            saved->intentProposer.explainSaved(query,saved,this::operationCancelled),this::operationCancelled);
                    yield Map.of("status","ANSWERED","response",answer.path("interpretation").path("answer").asText(),
                            "savedExplanation",value(answer));
                }
                case PREPARE, PRODUCT_REVIEW -> {
                    if(reportId!=null)yield Map.of("status","NEED_CLARIFICATION","response","当前正在解释已保存版本。若需取得新事实，请返回新对话确认执行；本轮没有业务查询。","missingInputs",List.of("newConversation"));
                    String executionQuery=resolving
                            ? pending.query()+"\n本轮补充："+query : query;
                    if(executionQuery.length()>500) yield Map.of("status","NEED_CLARIFICATION",
                            "response","任务与补充内容过长，请合并为500字以内的明确请求；尚未执行。","missingInputs",List.of("query"));
                    if(intent.objectRef()==null) {
                        synchronized(operationLock) {
                            if(operationCancelled()) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                            clarification=new PendingClarification(proposedBinding,intent.kind(),executionQuery,Instant.now().plusSeconds(300));
                        }
                        yield Map.of("status","NEED_CLARIFICATION","response","请告诉我本次服务的授权客户，我会保留这次目标供你确认；尚未查询客户事实。","missingInputs",List.of("customerAlias"));
                    }
                    ConversationConfirmation.Plan plan;
                    synchronized(operationLock) {
                        if(operationCancelled()) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                        plan=confirmation.propose(proposedBinding,intent,executionQuery);
                    }
                    yield Map.of("status","NEED_CONFIRMATION","response","请确认：为"+displayName(intent.objectRef())
                            +(intent.kind()==ConversationIntent.Kind.PREPARE?"取得新事实并准备回访报告。":"取得新事实并筛选待复核产品候选。")
                            +"尚未执行业务查询，已有报告不会被改写。",
                            "confirmation",Map.of("id",plan.id(),"customerAlias",intent.objectRef(),"action",intent.kind().name(),
                                    "query",plan.originalQuery(),"expiresAt",plan.expiresAt().toString()));
                }
            };
        }
        private Set<String> liveCustomers() {
            try { return Set.copyOf(task.customers(owner)); }
            catch(SecurityException denied) { throw denied; }
            catch(RuntimeException failure) {
                throw new io.github.bitlhk.finance.harness.core.ConversationFailure(
                        "CONVERSATION_AUTHORITY_UNAVAILABLE","BUSINESS_AUTHORITY",failure);
            }
        }
        private Map<String,Object> naturalReply(String query,ConversationResponder.Purpose purpose,List<String> missing,
                String decision,ConversationConfirmation.Binding binding,EventSink sink) throws Exception {
            String runId=operation.id;
            Runnable authorize=()->{
                current();
                if(operationCancelled())throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
                if(!binding.equals(conversationBinding()))
                    throw new SecurityException("CRM_AUTHORITY_CHANGED");
                if(!dialogue.disclosedObjects().isEmpty() && !liveCustomers().containsAll(dialogue.disclosedObjects()))
                    throw new SecurityException("CRM_EMPLOYEE_SCOPE_REJECTED");
            };
            var proof=conversationEvidence.begin(runId,owner,"CONVERSATION",purpose.name(),
                    "conversation.response",Map.of("questionDigest",ScenarioEvidence.digest(query),
                            "dialogueDigest",ScenarioEvidence.digest(previousDialogue),
                            "continuityDigest",ScenarioEvidence.digest(dialogue.state()),
                            "bindingDigest",ScenarioEvidence.digest(binding),"purpose",purpose.name()),
                    binding.scopeDigest(),"EXISTING_CRM_MAPPING_AND_INSTALLED_LOCK");
            proof.check("RESPONSE_ENTRY",authorize,null);
            int[] sequence={0};
            boolean[] invoked={false};
            String wordingInput=HarnessJson.mapper().writeValueAsString(Map.of("userText",query,
                    "previousDialogue",previousDialogue,"continuity",dialogue.state(),
                    "contextRule","此前文字仅用于理解序号与代词，不是事实、授权或执行确认。continuity只记录此前步骤；已列名单不能称从未列过，已返回报告不能称从未执行。无需让用户反复确认名单，当前访问权限仍由服务端核验。"));
            String answer=intentProposer.respond(wordingInput,new ConversationResponder.Context(purpose,
                            capabilities.description(),missing,decision),this::operationCancelled,
                    ()->{authorize.run();if(!invoked[0]){proof.invoked();invoked[0]=true;}},
                    text->{
                        proof.check("RESPONSE_TEXT_RELEASE",authorize,text);
                        sink.emit(Map.of("type","CUSTOM","name","finance.conversation.progress","value",Map.of(
                                "schemaVersion","finance-conversation-progress.v1","runId",runId,
                                "sequence",++sequence[0],"text",text,"purpose",purpose.name(),
                                "status","GENERATING","businessExecuted",false,
                                "bindingDigest",ScenarioEvidence.digest(binding))));
                    });
            proof.returned();
            proof.check("RESPONSE_COMPLETE_RELEASE",authorize,answer);
            var reply=new LinkedHashMap<String,Object>();
            reply.put("status",purpose==ConversationResponder.Purpose.CLARIFY?"NEED_CLARIFICATION":"ANSWERED");
            reply.put("response",answer);
            reply.put("responseScope","NON_BUSINESS");
            reply.put("conversationPurpose",purpose.name());
            if(!missing.isEmpty())reply.put("missingInputs",missing);
            return reply;
        }
        private void clearPendingProduct() {
            pendingProductCustomer=null;
            pendingProductArguments.clear();
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
        @Override public void close() { if(!closed){closed=true;confirmation.invalidate();clarification=null;dialogue.clear();task.close();} }
        private void current(){if(closed)throw new SecurityException("SCENARIO_SESSION_CLOSED");}
    }

    static String displayName(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.startsWith("合成客户") && normalized.length() > 4 ? normalized.substring(4) : normalized;
    }
    static String conversationScopeDigest(CustomerCrmEmployeeMapping mapping) {
        var authority=mapping.authority();
        try {
            byte[] encoded=HarnessJson.mapper().writeValueAsBytes(new TreeMap<>(Map.of(
                    "owner",authority.ownerRef(),"principal",authority.principalRef(),"revision",authority.revision(),
                    "expiresAt",authority.expiresAt().toString(),"authenticationLevel",authority.authenticationLevel().name(),
                    "allowedCustomers",new TreeSet<>(authority.allowedCustomerRefs()),"aliases",new TreeMap<>(mapping.customers()))));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch(Exception failure) { throw new IllegalStateException("CONVERSATION_SCOPE_UNAVAILABLE"); }
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

    static boolean shouldUseProductFlow(CustomerManagerDialogue.Action action, boolean pendingProduct, String query) {
        if(action==CustomerManagerDialogue.Action.PRODUCT_REVIEW) return true;
        if(!pendingProduct || action==CustomerManagerDialogue.Action.CANCEL_PRODUCT) return false;
        return action!=CustomerManagerDialogue.Action.PREPARE || !CustomerManagerDialogue.requestsPreparation(query);
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
        if(body.has("conversationContract")) {
            if(body.has("conversationContext")) {
                String context=body.path("conversationContext").textValue();
                if(context==null||!context.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
                actual.remove("conversationContext");
            }
            Set<String> required=Set.of("query","runId","customerAlias","conversationContract");
            var withConfirmation=new HashSet<>(required);withConfirmation.addAll(Set.of("confirmationId","confirmationDecision"));
            var withReport=new HashSet<>(required);withReport.addAll(Set.of("reportId","revision"));
            if((!actual.equals(required)&&!actual.equals(withConfirmation)&&!actual.equals(withReport))
                    ||!"manager-conversation.v2".equals(body.path("conversationContract").textValue())
                    ||!body.path("query").isTextual()||body.path("query").asText().isBlank()||body.path("query").asText().length()>500
                    ||!body.path("runId").isTextual()||body.path("runId").asText().isBlank()
                    ||!body.path("customerAlias").isTextual()||body.path("customerAlias").asText().length()>200
                    ||body.has("reportId")&&(!body.path("reportId").isTextual()||!body.path("reportId").asText().matches("[0-9a-f-]{36}")
                        ||!body.path("revision").isIntegralNumber()||!body.path("revision").canConvertToInt()||body.path("revision").asInt()<1)
                    ||body.has("confirmationId")&&(!body.path("confirmationId").isTextual()||body.path("confirmationId").asText().isBlank()
                        ||!Set.of("EXECUTE","CANCEL").contains(body.path("confirmationDecision").asText())))
                throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
            return;
        }
        if (!actual.equals(Set.of("query")) && !actual.equals(Set.of("query", "runId"))
                && !actual.equals(Set.of("query", "runId", "customerAlias"))) {
            throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
        }
        if(body.has("customerAlias")&&(!body.path("customerAlias").isTextual()||body.path("customerAlias").asText().length()>200))
            throw new IllegalArgumentException("SCENARIO_BODY_INVALID");
    }
}
