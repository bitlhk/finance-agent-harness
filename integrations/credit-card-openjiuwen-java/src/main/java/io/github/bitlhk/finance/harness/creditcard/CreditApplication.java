package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Private reference host. An authenticated upstream host supplies business identity bindings. */
public final class CreditApplication {
    private CreditApplication() {}
    private static String artifactDigest(Class<?> type) {
        try {
            Path path=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(!Files.isRegularFile(path))return "NOT_PACKAGED";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        }catch(Exception failure){return "NOT_OBSERVED";}
    }
    private static String secret(JsonNode config,String field){
        String name=CreditContracts.required(config,field);
        if(!name.matches("[A-Z][A-Z0-9_]*"))throw new IllegalArgumentException("INVALID_SECRET_REFERENCE");
        String value=System.getenv(name);
        if(value==null||value.isBlank()||value.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("SECRET_NOT_BOUND");
        return value;
    }
    /** The reference UI is a transport projection. All conversation state lives in this JVM/store. */
    private static final class WebHost {
        private final CreditService service;private final CreditStateStore store;private final CreditModelTools tools;
        private final Map<String,CreditContracts.Binding> references=new LinkedHashMap<>();
        private final Map<String,WebSession> sessions=new HashMap<>();
        private final Set<String> busy=new HashSet<>();
        private final String owner,scene,instance=UUID.randomUUID().toString();
        private final List<String> engines;
        private static final class WebSession {String conversation;CreditContracts.Turn pending;boolean closed;}
        WebHost(CreditService service,CreditStateStore store,CreditModelTools tools,
                Set<CreditContracts.Binding> bindings,String scene,List<String> engines){
            this.service=service;this.store=store;this.tools=tools;this.scene=scene;this.engines=List.copyOf(engines);
            owner=bindings.iterator().next().owner();
            for(var binding:bindings){
                if(!owner.equals(binding.owner()))throw new IllegalArgumentException("REFERENCE_SINGLE_OWNER_REQUIRED");
                references.put(CreditContracts.hash(binding.value()),binding);
            }
        }
        private ObjectNode snapshot(CreditContracts.Binding binding) {
            var turn=new CreditContracts.Turn("ui-"+UUID.randomUUID().toString().replace("-",""),
                    "session-ui-authority",scene,"核验当前材料",binding,Instant.now().plusSeconds(5));
            return CreditContracts.object(tools.call("snapshot",turn,turn.operationKey(),null).path("result"));
        }
        private CreditContracts.Binding binding(ObjectNode conversation){
            var value=references.get(conversation.path("customerRef").asText());
            if(value==null)throw new SecurityException("CUSTOMER_UNAVAILABLE");return value;
        }
        private ObjectNode workspace(WebSession session) throws Exception {
            var current=store.conversation(owner,scene,session.conversation);snapshot(binding(current));
            var output=CreditContracts.JSON.createObjectNode();var customers=output.putArray("customers");var visible=new HashSet<String>();
            for(var entry:references.entrySet())try{
                var view=snapshot(entry.getValue());visible.add(entry.getKey());
                customers.addObject().put("ref",entry.getKey()).put("name",view.path("customer").path("name").asText())
                    .put("tier",view.path("customer").path("tier").asInt()).put("customerId",entry.getValue().customer())
                    .put("caseId",view.path("case").path("case_id").asText());
            }catch(RuntimeException unavailable){/* Only currently readable customers are selectable. */}
            output.set("conversations",store.conversations(owner,scene));
            output.put("activeConversationId",current.path("id").asText()).put("customerRef",current.path("customerRef").asText());
            var messages=output.putArray("messages");
            for(var message:current.path("messages")){
                if(visible.contains(message.path("sourceRef").asText()))messages.add(message.deepCopy());
                else messages.addObject().put("role",message.path("role").asText()).put("text","该轮来源已变化，内容需重新核验。");
            }return output;
        }
        ObjectNode handle(String path,ObjectNode body) throws Exception {
            if(path.equals("/sessions"))synchronized(this){
                if(!body.isEmpty()||sessions.size()>=64)throw new IllegalArgumentException("SESSION_LIMIT_OR_INPUT");
                var session=new WebSession();var previous=store.conversations(owner,scene);
                if(!previous.isEmpty())session.conversation=previous.get(0).path("id").asText();
                if(session.conversation==null){
                    var first=references.entrySet().iterator().next();snapshot(first.getValue());
                    session.conversation=store.createConversation(owner,scene,first.getKey()).path("id").asText();
                }
                String id="session-"+UUID.randomUUID().toString().replace("-","");sessions.put(id,session);
                var response=CreditContracts.JSON.createObjectNode().put("sessionId",id).put("instanceId",instance)
                        .put("modelRef","deepseek-flash").put("authorization","CONFIGURED_REFERENCE_OWNER");
                response.set("workspace",workspace(session));return response;
            }
            var match=java.util.regex.Pattern.compile("/sessions/(session-[a-f0-9]{32})/([a-z-]+)").matcher(path);
            if(!match.matches())throw new IllegalArgumentException("UNKNOWN_ENDPOINT");
            String id=match.group(1),action=match.group(2);WebSession session;ObjectNode conversation;
            synchronized(this){
                session=sessions.get(id);if(session==null||session.closed)throw new SecurityException("SESSION_UNAVAILABLE");
                conversation=store.conversation(owner,scene,session.conversation);snapshot(binding(conversation));
                if(Set.of("end","revoke","cancel").contains(action)){
                    CreditContracts.fields(body,action.equals("cancel")?Set.of("runId"):Set.of());
                    session.closed=true;if(session.pending!=null)service.cancel(session.pending);
                    return CreditContracts.JSON.createObjectNode().put("status","ENDED").put("clearConversation",true);
                }
                if(Set.of("status","snapshot","activity").contains(action)){
                    if(!body.isEmpty())throw new IllegalArgumentException("INVALID_INPUT");
                    var result=CreditContracts.JSON.createObjectNode().put("sessionId",id)
                            .put("status",busy.contains(session.conversation)?"RUNNING":"IDLE").put("clearConversation",false);
                    result.set("messages",workspace(session).path("messages"));return result;
                }
                if(busy.contains(session.conversation))throw new IllegalArgumentException("SESSION_BUSY");
                if(Set.of("workbench","new-conversation","select-conversation","select-customer").contains(action)){
                    if(action.equals("workbench")||action.equals("new-conversation")){
                        if(!body.isEmpty())throw new IllegalArgumentException("INVALID_INPUT");
                        if(action.equals("new-conversation"))session.conversation=store.createConversation(owner,scene,
                                conversation.path("customerRef").asText()).path("id").asText();
                    }else if(action.equals("select-conversation")){
                        CreditContracts.fields(body,Set.of("conversationId"));
                        var selected=store.conversation(owner,scene,CreditContracts.required(body,"conversationId"));snapshot(binding(selected));
                        if(busy.contains(selected.path("id").asText()))throw new IllegalArgumentException("SESSION_BUSY");
                        session.conversation=selected.path("id").asText();
                    }else{
                        CreditContracts.fields(body,Set.of("customerRef"));String ref=CreditContracts.required(body,"customerRef");
                        var selected=references.get(ref);if(selected==null)throw new SecurityException("CUSTOMER_UNAVAILABLE");snapshot(selected);
                        conversation.put("customerRef",ref).put("thread","session-"+UUID.randomUUID().toString().replace("-",""));
                        store.saveConversation(owner,scene,conversation);
                    }return workspace(session);
                }
                if(!action.equals("chat"))throw new IllegalArgumentException("UNKNOWN_ENDPOINT");
                CreditContracts.fields(body,Set.of("query","runId"));
                String question=CreditContracts.required(body,"query"),run=CreditContracts.required(body,"runId");
                if(!run.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("INVALID_RUN");
                for(var message:conversation.path("messages"))if(run.equals(message.path("runId").asText())
                        &&"user".equals(message.path("role").asText())){
                    if(!question.equals(message.path("text").asText())||!conversation.path("customerRef").equals(message.path("sourceRef")))
                        throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
                    var replay=CreditContracts.JSON.createObjectNode().put("sessionId",id).put("instanceId",instance)
                            .put("runId",run).put("status","REPLAYED");replay.set("workspace",workspace(session));return replay;
                }
                session.pending=new CreditContracts.Turn("operation-"+CreditContracts.hash(CreditContracts.JSON.getNodeFactory().textNode(run)).substring(0,40),
                        conversation.path("thread").asText(),scene,question,binding(conversation),Instant.now().plusSeconds(120),3,engines);
                busy.add(session.conversation);
            }
            try{
                var raw=service.query(session.pending);
                synchronized(this){
                    if(session.closed)throw new SecurityException("SESSION_CLOSED");
                    var view=snapshot(binding(conversation));
                    var detail=CreditContracts.object(raw.path("result"));
                    var business=CreditContracts.JSON.createObjectNode().put("status",raw.path("status").asText());
                    business.set("execution",raw.path("execution"));
                    business.set("account",view.path("account"));business.set("receipt",detail.path("forecast"));
                    if(scene.equals("benefits"))business.set("result",detail);
                    String answer=scene.equals("benefits")?"已按当前资格与本轮偏好核验权益；匹配说明供客户经理参考。":"已核对当前额度与未来1、3、7天累计消费预测，区间和独立受阻概率分别展示。";
                    if(!"complete".equals(raw.path("status").asText()))answer="本轮状态："+raw.path("status").asText()+"；请查看下方核查记录。";
                    for(var q:detail.path("questions"))answer+="\n"+q.asText();
                    for(var check:detail.path("manager_preparation").path("checks"))answer+="\n建议核查："+check.path("description").asText();
                    var display=CreditContracts.JSON.createObjectNode().put("schemaVersion","finance-credit-card-display.v1");display.set("result",business);
                    display.putObject("context").put("customerId",binding(conversation).customer())
                        .put("customerName",view.path("customer").path("name").asText()).put("tier",view.path("customer").path("tier").asInt())
                        .put("caseId",view.path("case").path("case_id").asText()).put("observedAt",view.path("case").path("observed_at").asText())
                        .put("groupId",view.path("account").path("group_id").asText())
                        .put("snapshotDigest",binding(conversation).value().path("snapshot_digest").asText())
                        .put("snapshotRevision",view.path("revision").asInt());
                    var messages=conversation.withArray("messages");String ref=conversation.path("customerRef").asText();
                    String label=view.path("customer").path("name").asText()+" · M"+view.path("customer").path("tier").asInt();
                    messages.addObject().put("role","user").put("text",session.pending.question()).put("sourceRef",ref).put("customerLabel",label).put("runId",body.path("runId").asText());
                    messages.addObject().put("role","assistant").put("text",answer).put("sourceRef",ref).put("customerLabel",label).set("display",display);
                    if("新对话".equals(conversation.path("title").asText()))conversation.put("title",session.pending.question().substring(0,Math.min(48,session.pending.question().length())));
                    store.saveConversation(owner,scene,conversation);
                    var response=CreditContracts.JSON.createObjectNode().put("sessionId",id).put("instanceId",instance)
                            .put("runId",body.path("runId").asText()).put("status",raw.path("status").asText()).put("answer",answer)
                            .put("text",answer).put("response",answer);
                    response.set("creditCard",business);response.set("display",display);response.set("workspace",workspace(session));return response;
                }
            }finally{synchronized(this){busy.remove(session.conversation);session.pending=null;}}
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=2||!"--config".equals(args[0]))throw new IllegalArgumentException("USE_CONFIG_FILE");
        Path configFile=Path.of(args[1]).toRealPath();
        ObjectNode config=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(configFile)));
        CreditContracts.fields(config,Set.of("schema_version","port","state_root","transport_token_env",
                "model_tools_url","model_tools_token_env","model","model_digest","bindings","benefits","scene","engines","scenes"));
        if(!"credit-java-host.v1".equals(config.path("schema_version").asText()))
            throw new IllegalArgumentException("HOST_SCHEMA_MISMATCH");
        int port=config.path("port").asInt(-1);
        if(port<0||port>65535)throw new IllegalArgumentException("INVALID_PORT");
        var bindings=new LinkedHashSet<CreditContracts.Binding>();
        if(!config.path("bindings").isArray()||config.path("bindings").isEmpty())
            throw new IllegalArgumentException("HOST_AUTHORITY_REQUIRED");
        for(JsonNode value:config.path("bindings"))bindings.add(new CreditContracts.Binding(CreditContracts.object(value)));
        var model=CreditContracts.object(config.path("model"));
        CreditContracts.fields(model,Set.of("api_base","api_key_env","model"));
        String token=secret(config,"transport_token_env");
        var tools=new CreditModelTools(URI.create(CreditContracts.required(config,"model_tools_url")),
                secret(config,"model_tools_token_env"));
        var agent=new LimitRiskAgent(tools,CreditContracts.required(model,"api_base"),secret(model,"api_key_env"),
                CreditContracts.required(model,"model"),CreditContracts.required(config,"model_digest"));
        Path state=configFile.getParent().resolve(CreditContracts.required(config,"state_root")).normalize();
        var store=new CreditStateStore(state);
        URI worker=null;String workerToken=null;
        if(config.has("benefits")){
            var benefitsConfig=CreditContracts.object(config.path("benefits"));
            CreditContracts.fields(benefitsConfig,Set.of("worker_url","worker_token_env"));
            worker=URI.create(CreditContracts.required(benefitsConfig,"worker_url"));
            workerToken=secret(benefitsConfig,"worker_token_env");
        }
        var benefitsAgent=new BenefitsAgent(tools,CreditContracts.required(model,"api_base"),secret(model,"api_key_env"),
                CreditContracts.required(model,"model"),worker,workerToken);
        var service=new CreditService(store,tools,agent,benefitsAgent,turn->bindings.contains(turn.binding()));
        String scene=config.path("scene").asText("limit-risk");
        if(!Set.of("limit-risk","benefits").contains(scene))throw new IllegalArgumentException("INVALID_SCENE");
        List<String> workbenchEngines=List.of("llm");
        if(config.has("engines")){
            if(!config.path("engines").isArray())throw new IllegalArgumentException("INVALID_ENGINES");
            var names=new ArrayList<String>();for(var engine:config.path("engines")){
                if(!engine.isTextual())throw new IllegalArgumentException("INVALID_ENGINES");names.add(engine.asText());
            }
            if(names.isEmpty()||names.size()>2||new HashSet<>(names).size()!=names.size()||!Set.of("llm","onerec").containsAll(names))
                throw new IllegalArgumentException("INVALID_ENGINES");workbenchEngines=names;
        }
        Set<String> allowedScenes=new HashSet<>(Set.of("limit-risk","benefits"));
        if(config.has("scenes")){
            if(!config.path("scenes").isArray()||config.path("scenes").isEmpty())throw new IllegalArgumentException("INVALID_SCENES");
            allowedScenes.clear();for(var value:config.path("scenes")){
                if(!value.isTextual()||!Set.of("limit-risk","benefits").contains(value.asText())||!allowedScenes.add(value.asText()))
                    throw new IllegalArgumentException("INVALID_SCENES");
            }
        }
        if(!allowedScenes.contains(scene))throw new IllegalArgumentException("SCENE_NOT_INSTALLED");
        var web=new WebHost(service,store,tools,bindings,scene,workbenchEngines);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/",exchange->{
            int status=200;ObjectNode output;
            try {
                var auth=exchange.getRequestHeaders().get("Authorization");
                if(auth==null||auth.size()!=1||!MessageDigest.isEqual(auth.getFirst().getBytes(StandardCharsets.UTF_8),
                        ("Bearer "+token).getBytes(StandardCharsets.UTF_8)))throw new SecurityException("ACCESS_DENIED");
                String path=exchange.getRequestURI().toString();
                if(path.equals("/health")&&Set.of("GET","POST").contains(exchange.getRequestMethod())){
                    output=CreditContracts.JSON.createObjectNode().put("status","ready")
                            .put("runtime","openjiuwen-java").put("sdk_version","0.1.15")
                            .put("artifact_sha256",artifactDigest(CreditApplication.class))
                            .put("sdk_sha256",artifactDigest(com.openjiuwen.core.singleagent.agents.ReActAgent.class))
                            .put("readiness","PROCESS_ONLY").put("business_validation","NOT_RUN")
                            .put("pid",ProcessHandle.current().pid()).put("instanceId",web.instance)
                            .put("artifactLockSha256",System.getenv().getOrDefault("FH_EXPECTED_LOCK","NOT_BOUND"))
                            .put("launchNonce",System.getenv().getOrDefault("FH_INSTANCE_NONCE","NOT_BOUND"));
                    output.set("scenes",CreditContracts.JSON.valueToTree(new TreeSet<>(allowedScenes)));
                }else{
                    if(!exchange.getRequestMethod().equals("POST")||!(Set.of("/v1/query","/v1/cancel").contains(path)||path.equals("/sessions")||path.startsWith("/sessions/")))
                        throw new IllegalArgumentException("UNKNOWN_ENDPOINT");
                    var sizes=exchange.getRequestHeaders().get("Content-Length");
                    if(sizes==null||sizes.size()!=1||!sizes.getFirst().matches("[0-9]{1,7}")
                            ||exchange.getRequestHeaders().containsKey("Transfer-Encoding"))throw new IllegalArgumentException("INVALID_BODY");
                    int size=Integer.parseInt(sizes.getFirst());
                    if(size<=0||size>2*1024*1024)throw new IllegalArgumentException("INVALID_BODY");
                    byte[] bytes=exchange.getRequestBody().readNBytes(size+1);
                    if(bytes.length!=size)throw new IllegalArgumentException("INVALID_BODY");
                    ObjectNode input=CreditContracts.object(CreditContracts.JSON.readTree(bytes));
                    if(path.equals("/sessions")||path.startsWith("/sessions/"))output=web.handle(path,input);
                    else {
                    CreditContracts.fields(input,Set.of("schema_version","operation_key","session_ref","scene","question",
                            "binding","deadline_at","remaining_ms","recommendation"));
                    if(!"credit-java-request.v1".equals(input.path("schema_version").asText())
                            ||!input.path("remaining_ms").isIntegralNumber()||!input.path("remaining_ms").canConvertToLong())
                        throw new IllegalArgumentException("INVALID_REQUEST");
                    long remaining=input.path("remaining_ms").longValue();
                    if(remaining<=0||remaining>180000)throw new IllegalArgumentException("INVALID_BUDGET");
                    Instant deadline=Instant.parse(CreditContracts.required(input,"deadline_at"));
                    Instant limit=Instant.now().plusMillis(remaining);if(deadline.isAfter(limit))deadline=limit;
                    int topK=3;List<String> engines=List.of("llm");
                    if(input.has("recommendation")){
                        var options=CreditContracts.object(input.path("recommendation"));
                        CreditContracts.fields(options,Set.of("top_k","engines"));
                        if(!options.path("top_k").isIntegralNumber()||!options.path("top_k").canConvertToInt()
                                ||!options.path("engines").isArray())throw new IllegalArgumentException("INVALID_RECOMMENDATION_OPTIONS");
                        topK=options.path("top_k").intValue();var names=new ArrayList<String>();
                        for(var engine:options.path("engines")){
                            if(!engine.isTextual())throw new IllegalArgumentException("INVALID_RECOMMENDATION_OPTIONS");names.add(engine.asText());
                        }engines=names;
                    }
                    var turn=new CreditContracts.Turn(CreditContracts.required(input,"operation_key"),
                            CreditContracts.required(input,"session_ref"),CreditContracts.required(input,"scene"),
                            CreditContracts.required(input,"question"),new CreditContracts.Binding(CreditContracts.object(input.path("binding"))),deadline,topK,engines);
                    if(!allowedScenes.contains(turn.scene()))throw new SecurityException("SCENE_NOT_INSTALLED");
                    output=path.equals("/v1/query")?service.query(turn):service.cancel(turn);
                    output.put("customer_id",turn.binding().customer());
                    }
                }
            }catch(SecurityException failure){status=403;output=CreditContracts.JSON.createObjectNode().put("code","ACCESS_DENIED");}
            catch(IllegalArgumentException failure){
                String code=Set.of("IDEMPOTENCY_CONFLICT","SESSION_BUSY","CONVERSATION_CHANGED").contains(failure.getMessage())?failure.getMessage():"INVALID_REQUEST";
                status=code.equals("INVALID_REQUEST")?400:409;output=CreditContracts.JSON.createObjectNode().put("code",code);
            }
            catch(Exception failure){status=503;output=CreditContracts.JSON.createObjectNode().put("code","CREDIT_SERVICE_UNAVAILABLE");}
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(output);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);
            try{exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);service.close();try{store.close();}catch(Exception ignored){}}));
        server.start();
        System.out.println("Credit reference Java host ready on IPv4 loopback port "+server.getAddress().getPort());
    }
}
