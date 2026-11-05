package io.github.bitlhk.finance.harness.integrations.calculation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.session.stream.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import io.github.bitlhk.finance.harness.providers.calculation.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native Agent execution with generation-guarded disclosure and concurrent cancellation. */
public final class CalculationSession implements ManagedScenarioExtensionV01.Session {
    private static final ObjectMapper JSON=HarnessJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedScenarioExtensionV01.Context context;
    private final Path root;
    private final String id="calculation-session-"+UUID.randomUUID(),lockDigest;
    private final JsonNode lock,plan;
    private final CalculationSourceScope scope;
    private final Object state=new Object();
    private int generation;
    private boolean closed;
    private Thread activeThread;
    private OriginalCalculationProvider activeProvider;
    private Map<String,Object> latest;
    private final Map<String,Map<String,Object>> history=new LinkedHashMap<>();
    private String lastSuccessfulRun;

    public CalculationSession(ManagedScenarioExtensionV01.Context context) throws Exception{
        this.context=Objects.requireNonNull(context);root=context.artifactRoot().toRealPath();
        lock=JSON.readTree(Files.readAllBytes(root.resolve("service-lock.json")));lockDigest=hash(Files.readAllBytes(root.resolve("service-lock.json")));
        plan=JSON.readTree(Files.readAllBytes(root.resolve("component-plan.json")));
        if(!CalculationScenarioExtensionV01.ID.equals(lock.path("scenarioExtension").path("id").asText())
                || !"original-calculation-readonly".equals(plan.path("profileRef").path("id").asText())
                || !"maven://com.openjiuwen:agent-core-java:0.1.15".equals(plan.path("runtime").path("templateRef").asText())
                || plan.path("capabilityPackRefs").size()!=1 || plan.path("domainPackRefs").size()!=1)
            throw new SecurityException("CALCULATION_COMPOSITION_INVALID");
        verifyFiles();scope=new CalculationSourceScope(context);
        try{
            var actual=JSON.valueToTree(Map.of(OriginalCalculationProvider.ID,scope.factory.configurationDigests()));
            if((lock.has("providerConfiguration")||lock.has("scenarioDelivery")) && !actual.equals(lock.path("providerConfiguration")))
                throw new SecurityException("CALCULATION_CONFIGURATION_NOT_LOCKED");
            scope.current();
        }catch(Exception error){scope.close();throw error;}
    }
    @Override public String sessionId(){return id;}
    @Override public Map<String,Object> bootstrap(){
        var b=scope.binding();synchronized(state){ensureOpen();}
        var result=new LinkedHashMap<String,Object>(Map.of("scenarioId",CalculationScenarioExtensionV01.ID,"sessionId",id,"status","READY_NOT_EXECUTED",
            "assetId",b.assetId(),"operationId",b.operationId(),"artifactLockDigest",lockDigest,"runtime","openjiuwen-java@0.1.15","businessWrites",0));
        result.put("parameters",b.request().path("parameters"));result.put("dataAsOf",b.request().get("dataAsOf"));
        return Collections.unmodifiableMap(result);
    }
    @Override public Map<String,Object> status(){
        scope.current();Map<String,Object> snapshot;int selected;
        synchronized(state){ensureOpen();if(latest==null)return bootstrap();snapshot=latest;selected=generation;}
        if("COMPLETED".equals(snapshot.get("status"))){
            String sourceRunId=JSON.valueToTree(snapshot).path("capabilityResult").path("data").path("originalReturn").path("runId").asText();
            scope.requireStoredResult(sourceRunId);
        }
        current(selected);
        synchronized(state){if(latest!=snapshot)throw new SecurityException("CALCULATION_DISCLOSURE_CLOSED");return snapshot;}
    }
    private void ensureOpen(){if(closed)throw new SecurityException("CALCULATION_SESSION_CLOSED");}
    private void current(int expected){
        scope.current();synchronized(state){ensureOpen();if(generation!=expected)throw new SecurityException("CALCULATION_DISCLOSURE_CLOSED");}
    }
    @Override public Map<String,Object> call(String action,JsonNode body) throws Exception{
        if("cancel".equals(action))return cancel(false);
        if("status".equals(action))return status();
        if("history".equals(action)){
            scope.current();List<Map<String,Object>> records=new ArrayList<>();
            List<Map<String,Object>> saved;int selected;
            synchronized(state){ensureOpen();selected=generation;saved=List.copyOf(history.values());}
            for(var item:saved){
                var original=JSON.valueToTree(item).path("capabilityResult").path("data").path("originalReturn");
                scope.requireStoredResult(original.path("runId").asText());
                records.add(Map.of("runId",original.path("runId").asText(),"calculatedAt",original.path("calculatedAt").asText(),
                    "parameterDigest",original.path("parameterDigest").asText()));
            }
            current(selected);return Map.of("items",records);
        }
        if("result".equals(action)){
            if(body!=null && body.has("runId")){
                if(body.size()!=1 || !body.path("runId").asText().matches("[a-f0-9]{32}"))throw new IllegalArgumentException("CALCULATION_INPUT_INVALID");
                scope.current();Map<String,Object> saved;int selected;
                synchronized(state){ensureOpen();selected=generation;saved=history.get(body.path("runId").asText());}
                if(saved==null)throw new SecurityException("CALCULATION_HISTORY_NOT_AVAILABLE");
                scope.requireStoredResult(body.path("runId").asText());current(selected);return saved;
            }
            Map<String,Object> snapshot=status();
            if(!"COMPLETED".equals(snapshot.get("status")))throw new IllegalStateException("CALCULATION_RESULT_NOT_AVAILABLE");return snapshot;
        }
        if(!Set.of("chat","calculate").contains(action) || body==null || !body.isObject())throw new IllegalArgumentException("CALCULATION_INPUT_INVALID");
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!Set.of("query","parameters","runId").containsAll(fields) || body.has("parameters")&&!body.get("parameters").isObject()
                || body.has("query")&&(!body.get("query").isTextual() || body.get("query").textValue().isBlank() || body.get("query").textValue().length()>2000))
            throw new IllegalArgumentException("CALCULATION_INPUT_INVALID");
        scope.current();verifyFiles();int selected;
        synchronized(state){
            ensureOpen();if(activeThread!=null)throw new IllegalStateException("CALCULATION_ALREADY_RUNNING");
            if(history.size()>=64)throw new IllegalStateException("CALCULATION_SESSION_HISTORY_LIMIT");
            selected=++generation;latest=null;activeThread=Thread.currentThread();
        }
        try{
            Map<String,Object> result=run(body,selected);
            current(selected);
            synchronized(state){
                if(generation!=selected)throw new SecurityException("CALCULATION_DISCLOSURE_CLOSED");
                if("COMPLETED".equals(result.get("status"))){
                    var original=JSON.valueToTree(result).path("capabilityResult").path("data").path("originalReturn");
                    if(lastSuccessfulRun!=null)result.put("previousRunId",lastSuccessfulRun);
                    lastSuccessfulRun=original.path("runId").asText();
                    latest=Collections.unmodifiableMap(result);history.put(lastSuccessfulRun,latest);
                }else latest=Collections.unmodifiableMap(result);
                return latest;
            }
        }catch(Exception error){
            synchronized(state){
                if(generation!=selected && latest!=null)return latest;
                latest=Map.of("status","FAILED","errorCode","CALCULATION_EXECUTION_FAILED","disclosureClosed",true,"businessWrites",0);
            }
            // Authorization changes are rechecked on every later status/result read.
            return latest;
        }finally{
            OriginalCalculationProvider provider;
            synchronized(state){provider=activeProvider;activeProvider=null;activeThread=null;}
            if(provider!=null)provider.close();
            Thread.interrupted(); // The request thread belongs to the Host pool.
        }
    }
    private Map<String,Object> run(JsonNode body,int selected) throws Exception{
        current(selected);var lease=scope.current();var binding=scope.binding();
        List<ManagedProviderFactoryV01.HttpAttempt> attempts=new CopyOnWriteArrayList<>();
        var connection=new ManagedProviderFactoryV01.Connection(OriginalCalculationFactoryV01.KIND,URI.create(scope.initial().endpoint()),
            Duration.ofMillis(scope.initial().timeoutMs()),scope::session,context.currentObservation(),attempts::add);
        var raw=(OriginalCalculationProvider)scope.factory.create(connection,Map.of());
        synchronized(state){if(generation!=selected || closed){raw.close();throw new SecurityException("CALCULATION_DISCLOSURE_CLOSED");}activeProvider=raw;}
        var bound=new BoundFinanceProvider(raw,scope::grant,scope.factory.requirements());
        var recording=new Recording(bound,body.get("parameters"));
        var observed=new ObservedFinanceProvider(recording,OriginalCalculationProvider.ID,context.currentObservation());
        var model=Objects.requireNonNull(context.model());
        var agent=new ReActAgent(AgentCard.builder().id("original-calculation-"+UUID.randomUUID()).name("Original financial calculation").description("Finite host-bound original algorithms").build());
        String prompt="仅调用当前授权资产对应的工具 "+binding.capability().replace('.','_').replace('-','_')+"；不得选择其他算法、改输入或用模型计算替代原算法。工具失败必须说明未取得结果。";
        var configuration=ReActAgentConfig.builder().maxIterations(3).promptTemplate(List.of(Map.of("role","system","content",prompt)))
            .build().configureModelClient(model.clientProvider(),model.apiKey(),model.apiBase(),model.modelName(),true);
        configuration.getModelConfigObj().setTemperature(0.0);configuration.getModelConfigObj().setMaxTokens(700);
        configuration.setModelClientConfig(ModelClientConfig.builder().clientProvider(model.clientProvider()).apiKey(model.apiKey()).apiBase(model.apiBase()).timeout(10).maxRetries(0).verifySsl(true).build());
        agent.configure(configuration);
        agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL,event->{try{current(selected);}catch(SecurityException denied){event.requestForceFinish(Map.of("error","AUTHORIZATION_OR_CANCELLATION_REJECTED"));}},0);
        if(!Runner.start())throw new IllegalStateException("RUNTIME_START_FAILED");
        var pack=JSON.readValue(Files.readAllBytes(asset(plan.path("capabilityPackRefs").get(0))),FinanceCapabilityPackV1.class);
        Set<String> capabilities=new HashSet<>();pack.capabilities().forEach(c->{if(c.operation()!=FinanceCapabilityPackV1.Operation.QUERY)throw new SecurityException("QUERY_ONLY");capabilities.add(c.capabilityId());});
        Set<String> expected=new HashSet<>();OriginalCalculationProvider.FAMILIES.forEach(f->expected.add(OriginalCalculationProvider.capability(f)));
        if(!capabilities.equals(expected))throw new SecurityException("CALCULATION_CAPABILITIES_INVALID");
        var method=new FinanceDomainPackLoader().load(asset(plan.path("domainPackRefs").get(0)));
        var methodCapabilities=new HashSet<>(method.pack().capabilities());
        if(!"original-calculation-readonly".equals(method.pack().profile()) || !expected.containsAll(methodCapabilities)
                || !methodCapabilities.contains(binding.capability()))throw new SecurityException("CALCULATION_METHOD_INVALID");
        var defaults=new OpenJiuwenFinanceBridge.InvocationDefaults(lease.principalId(),lease.subjectRef(),FinanceContextV1.PrincipalType.EMPLOYEE,
            "managed-local-host","CALCULATION",FinanceContextV1.AuthenticationLevel.STRONG,Set.of(OriginalCalculationProvider.SCOPE),()->scope.current().scopes(),true);
        Map<String,OpenJiuwenCapabilityInstaller.ProviderBinding> bindings=new LinkedHashMap<>();
        capabilities.forEach(c->bindings.put(c,new OpenJiuwenCapabilityInstaller.ProviderBinding(OriginalCalculationProvider.ID,"0.1.0-SNAPSHOT",observed)));
        String nativeSession=UUID.randomUUID().toString(),query=body.path("query").asText("请按固定输入执行本次原算法计算，并说明结果依据。");
        Map<String,Object> output=new LinkedHashMap<>();output.put("schemaVersion","finance-original-calculation-run.v1");output.put("runId",UUID.randomUUID().toString());
        output.put("sessionId",id);output.put("buildId",context.buildId());output.put("artifactLockDigest",lockDigest);output.put("runtime","openjiuwen-java@0.1.15");
        output.put("modelMode",System.getenv().getOrDefault("FH_MODEL_MODE","UNSPECIFIED"));
        try(var domain=new OpenJiuwenDomainPackInstaller().install(agent,method);
            var tools=new OpenJiuwenCapabilityInstaller().install(agent,bindings,defaults,pack,false,new TaskCheckpointStore.Memory(),lease.sessionRef())){
            output.put("methodInstallation",domain.receipt());output.put("capabilityInstallation",tools.receipt());
            current(selected);StringBuilder response=new StringBuilder();
            var stream=Runner.runAgentStreaming(agent,Map.of("query",query),nativeSession,null,List.of(StreamMode.OUTPUT));
            while(stream.hasNext()){
                current(selected);if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                Object next=stream.next();
                if(next instanceof OutputSchema item && "llm_output".equals(item.getType())){
                    Object payload=item.getPayload();String part=payload instanceof String s?s:payload instanceof Map<?,?> m&&m.get("content") instanceof String s?s:"";
                    if(part.startsWith(response.toString()))response.setLength(0);response.append(part);
                    if(response.length()>20000)throw new IllegalStateException("MODEL_OUTPUT_LIMIT");
                }
            }
            current(selected);boolean completed=!recording.results.isEmpty() && recording.results.stream().allMatch(r->r.status()==CapabilityResultV1.Status.COMPLETED);
            CapabilityResultV1 failed=recording.results.stream().filter(r->r.status()!=CapabilityResultV1.Status.COMPLETED).findFirst().orElse(null);
            String reason=failed!=null && failed.error()!=null?failed.error().sourceCode():"";
            String terminal=Set.of("ORIGINAL_TIMED_OUT","ORIGINAL_CALCULATION_TIMEOUT").contains(reason)?"TIMED_OUT":
                Set.of("ORIGINAL_CANCELLED","ORIGINAL_CALCULATION_CANCELLED").contains(reason)?"CANCELLED":"FAILED";
            output.put("status",completed?"COMPLETED":terminal);output.put("response",completed?response.toString():"本次原算法结果未取得，不能以模型答复代替计算。");
            if(!recording.results.isEmpty())output.put("capabilityResult",completed?recording.results.getLast():failed);
            if(!completed)output.put("errorCode",terminal.equals("TIMED_OUT")?"ORIGINAL_CALCULATION_TIMEOUT":
                terminal.equals("CANCELLED")?"ORIGINAL_CALCULATION_CANCELLED":recording.results.isEmpty()?"ORIGINAL_TOOL_NOT_CALLED":"ORIGINAL_TOOL_FAILED");
            output.put("toolCallCount",recording.results.size());output.put("httpAttempts",List.copyOf(attempts));output.put("businessWrites",0);
            output.put("businessQualification","NOT_EVALUATED");return output;
        }finally{Runner.release(nativeSession);}
    }
    private Map<String,Object> cancel(boolean closing){
        OriginalCalculationProvider provider;Thread thread;
        synchronized(state){
            if(closed)return Map.of("status","CLOSED","disclosureClosed",true);
            generation++;provider=activeProvider;thread=activeThread;latest=null;if(closing)closed=true;
        }
        boolean stopped=provider==null || provider.cancelAndClose();
        if(thread!=null)thread.interrupt();
        Map<String,Object> result=Map.of("status",stopped?"CANCELLED":"FAILED","workerStopped",stopped,"disclosureClosed",true,
            "errorCode",stopped?"CANCELLED":"SOURCE_STOP_NOT_CONFIRMED","businessWrites",0);
        synchronized(state){latest=result;}
        return result;
    }
    @Override public void close(){cancel(true);scope.close();}
    private Path asset(JsonNode reference) throws Exception{
        String source=reference.path("source").asText();if(!source.startsWith("repo://"))throw new SecurityException("CALCULATION_ASSET_INVALID");
        Path result=root.resolve("assets").resolve(source.substring(7)).normalize();
        if(!result.startsWith(root.resolve("assets")) || Files.isSymbolicLink(result))throw new SecurityException("CALCULATION_ASSET_INVALID");
        if(Files.isDirectory(result))result=result.resolve("pack.yaml");
        if(!result.toRealPath().equals(result))throw new SecurityException("CALCULATION_ASSET_INVALID");return result;
    }
    private void verifyFiles() throws Exception{
        if(!hash(Files.readAllBytes(root.resolve("service-lock.json"))).equals(lockDigest) || !lock.path("files").has("component-plan.json"))throw new SecurityException("CALCULATION_LOCK_CHANGED");
        var files=lock.path("files").fields();while(files.hasNext()){
            var item=files.next();if(!item.getKey().startsWith("assets/") && !item.getKey().equals("component-plan.json"))continue;
            Path file=root.resolve(item.getKey()).normalize();
            if(!file.startsWith(root) || !Files.isRegularFile(file) || !file.toRealPath().equals(file) || !hash(Files.readAllBytes(file)).equals("sha256:"+item.getValue().asText()))
                throw new SecurityException("CALCULATION_ARTIFACT_CHANGED");
        }
    }
    private static String hash(byte[] data)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
    private static final class Recording implements DescribedFinanceProvider,InvocationAccess {
        final BoundFinanceProvider delegate;final JsonNode selectedParameters;final List<CapabilityResultV1> results=new CopyOnWriteArrayList<>();
        Recording(BoundFinanceProvider delegate,JsonNode selectedParameters){this.delegate=delegate;this.selectedParameters=selectedParameters==null?null:selectedParameters.deepCopy();}
        public ProviderDescriptorV1 descriptor(){return delegate.descriptor();}
        public void checkAccess(CapabilityRequestV1 r){delegate.checkAccess(r);}
        public CapabilityResultV1 query(CapabilityRequestV1 r){
            if(selectedParameters!=null)r=new CapabilityRequestV1(r.schemaVersion(),r.capabilityId(),r.operation(),r.context(),Map.of("parameters",JSON.convertValue(selectedParameters,Map.class)),r.idempotencyKey(),r.taskRef(),r.continuation(),r.confirmation());
            var result=delegate.query(r);results.add(result);return result;
        }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 r){throw new SecurityException("QUERY_ONLY");}
    }
}
