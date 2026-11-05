package io.github.bitlhk.finance.harness.integrations.material;

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
import io.github.bitlhk.finance.harness.providers.material.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Real native model/tool loop with current host authority and finite original facts. */
public final class MaterialProcessingSession implements ManagedScenarioExtensionV01.Session {
    private static final Object NATIVE_LOCK=new Object();
    private static final Map<Path,Set<String>> LEASED=new HashMap<>();
    private static final ObjectMapper JSON=HarnessJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedScenarioExtensionV01.Context context;
    private final Path root,authorityFile;
    private final String id="material-session-"+UUID.randomUUID(),lockDigest;
    private final JsonNode lock,plan;
    private final Authority initial;
    private final Lease selected;
    private final FinancialMaterialFactoryV01 factory;
    private volatile boolean closed;
    private volatile Map<String,Object> latest;
    private volatile Thread running;

    public record Lease(String principalId,String subjectRef,String taskRef,String sessionRef,
                        String credential,Instant expiresAt,Set<String> scopes,int generation) {
        public Lease {
            for(String s:List.of(principalId,subjectRef,taskRef,sessionRef,credential)) required(s);
            Objects.requireNonNull(expiresAt);scopes=Set.copyOf(scopes);if(generation<1)throw new IllegalArgumentException("MATERIAL_GENERATION_INVALID");
        }
        @Override public String toString() { return "Lease[redacted]"; }
    }
    public record Authority(String schemaVersion,String ownerRef,String endpoint,int timeoutMs,
                            String bindingsFile,String bindingsDigest,List<Lease> leases) {
        public Authority {
            if(!"financial-material-host-authority.v1".equals(schemaVersion) || timeoutMs<10 || timeoutMs>60000
                    || leases==null || leases.isEmpty() || leases.size()>128) throw new IllegalArgumentException("MATERIAL_AUTHORITY_INVALID");
            required(ownerRef);required(endpoint);required(bindingsFile);required(bindingsDigest);leases=List.copyOf(leases);
            Set<String> ids=new HashSet<>();for(Lease lease:leases) if(!ids.add(lease.sessionRef())) throw new IllegalArgumentException("MATERIAL_AUTHORITY_INVALID");
        }
        @Override public String toString() { return "Authority[redacted]"; }
    }

    public MaterialProcessingSession(ManagedScenarioExtensionV01.Context context) throws Exception {
        this.context=Objects.requireNonNull(context);root=context.artifactRoot().toRealPath();
        Path state=context.privateStateRoot().toAbsolutePath().normalize();
        if(state.startsWith(root) || !state.toRealPath().equals(state) || Files.isSymbolicLink(state)) throw new SecurityException("PRIVATE_STATE_OUTSIDE_ARTIFACT_REQUIRED");
        authorityFile=state.resolve("material-host-authority.json");
        initial=readAuthority();
        if(!initial.ownerRef().equals(context.ownerRef())) throw new SecurityException("MATERIAL_OWNER_MISMATCH");
        selected=claim(initial);
        try {
            byte[] raw=Files.readAllBytes(root.resolve("service-lock.json"));lockDigest=hash(raw);lock=JSON.readTree(raw);
            verifyFiles();plan=JSON.readTree(Files.readAllBytes(root.resolve("component-plan.json")));
            if(!MaterialProcessingExtensionV01.ID.equals(lock.path("scenarioExtension").path("id").asText())
                    || !plan.path("runtime").path("templateRef").asText().equals("maven://com.openjiuwen:agent-core-java:0.1.15")
                    || !plan.path("profileRef").path("id").asText().equals("financial-material-processing")
                    || plan.path("domainPackRefs").size()!=1 || plan.path("capabilityPackRefs").size()!=1
                    || !plan.path("capabilityBindings").path(FinancialMaterialProvider.EXTRACT).asText().equals(FinancialMaterialProvider.ID))
                throw new SecurityException("MATERIAL_COMPOSITION_INVALID");
            Path bindingPath=Path.of(initial.bindingsFile());
            if(!bindingPath.isAbsolute() || bindingPath.toAbsolutePath().normalize().startsWith(root)) throw new SecurityException("PRIVATE_BINDINGS_REQUIRED");
            factory=new FinancialMaterialFactoryV01(bindingPath.toString(),initial.bindingsDigest());
            var actualConfiguration=JSON.valueToTree(Map.of(FinancialMaterialProvider.ID,factory.configurationDigests()));
            // Bare component fixtures own their authority directly. A managed Builder
            // artifact must honor the configuration bytes admitted during its build.
            if((lock.has("providerConfiguration")||lock.has("scenarioDelivery"))
                    && !actualConfiguration.equals(lock.path("providerConfiguration")))
                throw new SecurityException("MATERIAL_CONFIGURATION_NOT_LOCKED");
            var bindingFile=JSON.readTree(Files.readAllBytes(bindingPath));
            boolean bound=false;for(JsonNode b:bindingFile.path("bindings"))if(b.path("sessionRef").asText().equals(selected.sessionRef())&&b.path("taskRef").asText().equals(selected.taskRef())&&b.path("principalId").asText().equals(selected.principalId())&&b.path("subjectRef").asText().equals(selected.subjectRef()))bound=true;
            if(!bound)throw new SecurityException("MATERIAL_DOCUMENT_BINDING_MISMATCH");
            current();
        } catch(Exception error) { releaseClaim();throw error; }
    }
    private Lease claim(Authority authority) {
        synchronized(LEASED) {
            Set<String> used=LEASED.computeIfAbsent(authorityFile,key->new HashSet<>());
            for(Lease lease:authority.leases()) if(!used.contains(lease.sessionRef()) && lease.expiresAt().isAfter(Instant.now())
                    && lease.scopes().contains(FinancialMaterialProvider.SCOPE)) { used.add(lease.sessionRef());return lease; }
            throw new SecurityException("NO_CURRENT_AUTHORIZED_TASK");
        }
    }
    private void releaseClaim() { synchronized(LEASED) {
        Set<String> used=LEASED.get(authorityFile);if(used!=null) { used.remove(selected.sessionRef());if(used.isEmpty()) LEASED.remove(authorityFile); }
    } }
    private Authority readAuthority() {
        try {
            if(Files.isSymbolicLink(authorityFile) || Files.size(authorityFile)>262144) throw new IllegalArgumentException();
            return JSON.readValue(Files.readAllBytes(authorityFile),Authority.class);
        } catch(Exception error) { throw new SecurityException("MATERIAL_AUTHORITY_UNAVAILABLE"); }
    }
    private Lease current() {
        if(closed) throw new SecurityException("SCENARIO_SESSION_CLOSED");
        Authority now=readAuthority();
        if(!now.ownerRef().equals(context.ownerRef()) || !now.endpoint().equals(initial.endpoint())
                || now.timeoutMs()!=initial.timeoutMs() || !now.bindingsFile().equals(initial.bindingsFile())
                || !now.bindingsDigest().equals(initial.bindingsDigest())) throw new SecurityException("MATERIAL_AUTHORITY_CHANGED");
        Lease lease=now.leases().stream().filter(l->l.sessionRef().equals(selected.sessionRef())).findFirst()
                .orElseThrow(()->new SecurityException("MATERIAL_AUTHORIZATION_REVOKED"));
        if(!lease.principalId().equals(selected.principalId()) || !lease.subjectRef().equals(selected.subjectRef())
                || !lease.taskRef().equals(selected.taskRef())
                || lease.generation()!=selected.generation() || !lease.expiresAt().isAfter(Instant.now()) || !lease.scopes().contains(FinancialMaterialProvider.SCOPE))
            throw new SecurityException("MATERIAL_AUTHORIZATION_REJECTED");
        return lease;
    }
    private ManagedProviderFactoryV01.SourceSession sourceSession() {
        Lease lease=current();return new ManagedProviderFactoryV01.SourceSession(lease.credential(),lease.subjectRef(),lease.principalId(),lease.sessionRef(),lease.expiresAt());
    }
    private BoundFinanceProvider.Grant grant() {
        Lease lease=current();return new BoundFinanceProvider.Grant(lease.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of(lease.subjectRef()),Set.of("MATERIAL"),lease.scopes(),lease.sessionRef(),"host-private-task",lease.expiresAt());
    }
    @Override public String sessionId() { return id; }
    @Override public synchronized Map<String,Object> bootstrap() {
        Lease lease=current();return Map.of("scenarioId",MaterialProcessingExtensionV01.ID,"status","READY_NOT_EXECUTED",
                "subjectRef",lease.subjectRef(),"taskRef",lease.taskRef(),"artifactLockDigest",lockDigest,
                "runtime","openjiuwen-java@0.1.15","businessWrites",0,
                "principalId",lease.principalId(),"sourceSessionRef",lease.sessionRef());
    }
    @Override public synchronized Map<String,Object> status() { current();return latest==null?bootstrap():latest; }
    @Override public Map<String,Object> call(String action,JsonNode body) throws Exception {
        current();
        Set<String> fields=new HashSet<>();if(body!=null)body.fieldNames().forEachRemaining(fields::add);
        if(!"chat".equals(action) || body==null || !body.isObject() || !fields.contains("query")
                || !Set.of("query","runId").containsAll(fields) || !body.path("query").isTextual()
                || body.path("query").asText().isBlank() || body.path("query").asText().length()>1000)
            throw new IllegalArgumentException("MATERIAL_CHAT_INPUT_INVALID");
        synchronized(this) {
            current();if(running!=null)throw new IllegalStateException("MATERIAL_SESSION_BUSY");
            verifyFiles();latest=null;running=Thread.currentThread();
        }
        try { synchronized(NATIVE_LOCK) { current();return run(body.path("query").asText()); } }
        finally { synchronized(this) { running=null; } }
    }
    private Map<String,Object> run(String query) throws Exception {
        Lease lease=current();String runId="material-run-"+UUID.randomUUID();
        var output=new LinkedHashMap<String,Object>();
        output.put("schemaVersion","finance-material-run.v1");output.put("runId",runId);
        output.put("sessionId",id);output.put("buildId",context.buildId());output.put("artifactLockDigest",lockDigest);
        output.put("runtime","openjiuwen-java@0.1.15");output.put("modelMode",System.getenv().getOrDefault("FH_MODEL_MODE","UNSPECIFIED"));
        var observedCalls=new ArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var connection=new ManagedProviderFactoryV01.Connection(FinancialMaterialFactoryV01.KIND,URI.create(initial.endpoint()),
                Duration.ofMillis(initial.timeoutMs()),this::sourceSession,context.currentObservation(),observedCalls::add);
        var raw=factory.create(connection,Map.of());
        var bound=new BoundFinanceProvider(raw,this::grant,factory.requirements());
        var recording=new Recording(bound);
        var provider=new ObservedFinanceProvider(recording,FinancialMaterialProvider.ID,context.currentObservation());
        var model=Objects.requireNonNull(context.model());
        var agent=new ReActAgent(AgentCard.builder().id("material-"+UUID.randomUUID()).name("Financial material evidence").description("Pinned original document extraction and evidence").build());
        var configuration=ReActAgentConfig.builder().maxIterations(3)
                .promptTemplate(List.of(Map.of("role","system","content","仅依据已安装方法调用材料提取或定位工具；保留原值及缺失，不补造页码、金额、币种或区域。部分、阻断和失败必须明示，不代表业务核验通过。")))
                .build().configureModelClient(model.clientProvider(),model.apiKey(),model.apiBase(),model.modelName(),true);
        configuration.getModelConfigObj().setTemperature(0.0);configuration.getModelConfigObj().setMaxTokens(700);agent.configure(configuration);
        configuration.setModelClientConfig(ModelClientConfig.builder().clientProvider(model.clientProvider()).apiKey(model.apiKey())
                .apiBase(model.apiBase()).timeout(10).maxRetries(0).verifySsl(true).build());
        agent.configure(configuration);
        agent.registerCallback(AgentCallbackEvent.BEFORE_MODEL_CALL,event->{
            try { current(); } catch(SecurityException denied) { event.requestForceFinish(Map.of("error","AUTHORIZATION_REJECTED")); }
        },0);
        if(!Runner.start()) throw new IllegalStateException("RUNTIME_START_FAILED");
        var pack=JSON.readValue(Files.readAllBytes(asset(plan.path("capabilityPackRefs").get(0))),FinanceCapabilityPackV1.class);
        if(!new HashSet<>(pack.capabilities().stream().map(c->c.capabilityId()).toList()).equals(Set.of(FinancialMaterialProvider.EXTRACT,FinancialMaterialProvider.LOCATE))
                || pack.capabilities().stream().anyMatch(c->c.operation()!=FinanceCapabilityPackV1.Operation.QUERY))
            throw new SecurityException("MATERIAL_CAPABILITY_INVALID");
        var method=new FinanceDomainPackLoader().load(asset(plan.path("domainPackRefs").get(0)));
        if(!new HashSet<>(method.pack().capabilities()).equals(Set.of(FinancialMaterialProvider.EXTRACT,FinancialMaterialProvider.LOCATE)) || !method.pack().profile().equals("financial-material-processing"))
            throw new SecurityException("MATERIAL_METHOD_INVALID");
        var defaults=new OpenJiuwenFinanceBridge.InvocationDefaults(lease.principalId(),lease.subjectRef(),FinanceContextV1.PrincipalType.EMPLOYEE,
                "managed-local-host","MATERIAL",FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of(FinancialMaterialProvider.SCOPE),()->current().scopes(),true);
        var bindings=Map.of(FinancialMaterialProvider.EXTRACT,new OpenJiuwenCapabilityInstaller.ProviderBinding(FinancialMaterialProvider.ID,"0.1.0-SNAPSHOT",provider),FinancialMaterialProvider.LOCATE,new OpenJiuwenCapabilityInstaller.ProviderBinding(FinancialMaterialProvider.ID,"0.1.0-SNAPSHOT",provider));
        String nativeSession=UUID.randomUUID().toString();
        try(var domain=new OpenJiuwenDomainPackInstaller().install(agent,method);
            var capabilities=new OpenJiuwenCapabilityInstaller().install(agent,bindings,defaults,pack,false,new TaskCheckpointStore.Memory(),lease.sessionRef())) {
            output.put("methodInstallation",domain.receipt());output.put("capabilityInstallation",capabilities.receipt());
            StringBuilder text=new StringBuilder();
            var chunks=Runner.runAgentStreaming(agent,Map.of("query",query),nativeSession,null,List.of(StreamMode.OUTPUT));
            while(chunks.hasNext()) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Object next=chunks.next();
                if(next instanceof OutputSchema item && "llm_output".equals(item.getType())) {
                    Object payload=item.getPayload();String part=payload instanceof String s?s:payload instanceof Map<?,?> map&&map.get("content") instanceof String s?s:"";
                    if(part.startsWith(text.toString()))text.setLength(0);text.append(part);
                    if(text.length()>20000)throw new IllegalStateException("MODEL_OUTPUT_LIMIT");
                }
            }
            current();
            boolean completed=!recording.results.isEmpty() && recording.results.stream().allMatch(r->r.status()==CapabilityResultV1.Status.COMPLETED);
            output.put("status",completed?"COMPLETED":"FAILED");output.put("response",text.toString());
            if(!recording.results.isEmpty()) output.put("capabilityResult",recording.results.getLast());
            if(!completed) {
                output.put("errorCode",recording.results.isEmpty()?"ORIGINAL_TOOL_NOT_CALLED":"ORIGINAL_TOOL_FAILED");
                output.put("response","本次未取得完整材料结果，请检查工具状态并核对原文。");
            }
        } catch(SecurityException denied) { latest=null;throw denied;
        } catch(Exception error) {
            if(error instanceof InterruptedException)Thread.currentThread().interrupt();
            current();output.put("status","FAILED");output.put("errorCode","NATIVE_EXECUTION_FAILED");output.put("response","本次材料执行失败，未确认取得有效结果。");
        } finally { Runner.release(nativeSession); }
        output.put("toolCallCount",recording.results.size());output.put("httpAttempts",List.copyOf(observedCalls));
        output.put("businessWrites",0);
        synchronized(this) {
            current();
            latest=Collections.unmodifiableMap(output);
            return latest;
        }
    }
    private Path asset(JsonNode ref) {
        String source=ref.path("source").asText();
        if(!source.startsWith("repo://"))throw new SecurityException("MATERIAL_ASSET_INVALID");
        Path result=root.resolve("assets").resolve(source.substring(7)).normalize();
        if(!result.startsWith(root.resolve("assets")) || Files.isSymbolicLink(result))throw new SecurityException("MATERIAL_ASSET_INVALID");
        if(Files.isDirectory(result)) {
            for(String name:List.of("pack.yaml","domain-pack.v1.json","capability-pack.v1.json"))if(Files.isRegularFile(result.resolve(name)))return result.resolve(name);
        }
        return result;
    }
    private void verifyFiles() throws Exception {
        if(!hash(Files.readAllBytes(root.resolve("service-lock.json"))).equals(lockDigest)) throw new SecurityException("MATERIAL_LOCK_CHANGED");
        if(!lock.path("files").isObject() || !lock.path("files").has("component-plan.json")) throw new SecurityException("MATERIAL_LOCK_INVALID");
        var items=lock.path("files").fields();
        while(items.hasNext()) {
            var item=items.next();if(!item.getKey().startsWith("assets/") && !item.getKey().equals("component-plan.json"))continue;
            Path path=root.resolve(item.getKey()).normalize();
            if(!path.startsWith(root) || !Files.isRegularFile(path) || !path.toRealPath().equals(path)
                    || !hash(Files.readAllBytes(path)).equals("sha256:"+item.getValue().asText()))throw new SecurityException("MATERIAL_ARTIFACT_CHANGED");
        }
    }
    @Override public synchronized void close() {
        if(!closed) {
            closed=true;latest=null;
            if(running!=null) {
                running.interrupt();
                try {
                    var request=java.net.http.HttpRequest.newBuilder(URI.create(initial.endpoint()).resolve("/v1/material/cancel"))
                            .timeout(Duration.ofSeconds(3)).header("Authorization","Bearer "+selected.credential())
                            .header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
                    java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.discarding());
                } catch(Exception canceled) { /* Current disclosure is closed even if source is unavailable. */ }
            }
            releaseClaim();
        }
    }
    private static void required(String value) { if(value==null || value.isBlank())throw new IllegalArgumentException("MATERIAL_AUTHORITY_INVALID"); }
    private static String hash(byte[] bytes) throws Exception { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static final class Recording implements DescribedFinanceProvider,InvocationAccess {
        final BoundFinanceProvider delegate;final List<CapabilityResultV1> results=new ArrayList<>();
        Recording(BoundFinanceProvider delegate) { this.delegate=delegate; }
        public ProviderDescriptorV1 descriptor() { return delegate.descriptor(); }
        public void checkAccess(CapabilityRequestV1 request) { delegate.checkAccess(request); }
        public CapabilityResultV1 query(CapabilityRequestV1 request) { var result=delegate.query(request);results.add(result);return result; }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new SecurityException("QUERY_ONLY"); }
    }
}
