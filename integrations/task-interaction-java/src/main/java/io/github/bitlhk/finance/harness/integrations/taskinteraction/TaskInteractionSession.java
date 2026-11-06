package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.execution.*;
import io.github.bitlhk.finance.harness.providers.taskinteraction.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Host-injected Journal; source remains owner of the business task and its operation receipts. */
public final class TaskInteractionSession implements ManagedScenarioExtensionV01.Session {
    private static final ObjectMapper JSON=TaskInteractionMapper.json();
    private final ManagedScenarioExtensionV01.Context context;
    private final Path root;
    private final JsonNode lock;
    private final String lockDigest;
    private final String id="task-session-"+UUID.randomUUID();
    private final TaskInteractionSourceScope scope;
    private final ManagedOriginalTaskProvider provider;
    private final InstalledPreparationMethod method;
    private final TaskCoordination coordination;
    private final Object state=new Object();
    private int generation;
    private boolean closed;
    private Thread active;
    private Map<String,Object> latest;

    public TaskInteractionSession(ManagedScenarioExtensionV01.Context context,TaskCoordination.Journal journal) throws Exception {
        this.context=context;root=context.artifactRoot().toRealPath();
        byte[] bytes=Files.readAllBytes(root.resolve("service-lock.json"));lock=TaskInteractionMapper.parse(bytes);lockDigest=sha(bytes);
        JsonNode plan=TaskInteractionMapper.parse(Files.readAllBytes(root.resolve("component-plan.json")));
        if(!TaskInteractionScenarioExtensionV01.ID.equals(lock.path("scenarioExtension").path("id").asText())
                ||!"original-task-review-managed".equals(plan.at("/profileRef/id").asText())
                ||!"maven://com.openjiuwen:agent-core-java:0.1.15".equals(plan.at("/runtime/templateRef").asText()))throw new SecurityException("TASK_COMPOSITION_INVALID");
        verifyFiles();scope=new TaskInteractionSourceScope(context);
        try{
            if(!JSON.valueToTree(Map.of(ManagedOriginalTaskProvider.ID,scope.factory.configurationDigests())).equals(lock.path("providerConfiguration")))throw new SecurityException("TASK_CONFIGURATION_NOT_LOCKED");
            String mid=lock.at("/method/assetId").asText(),version=lock.at("/method/version").asText();
            if(!mid.matches("[a-z0-9-]{1,100}")||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("TASK_METHOD_NOT_LOCKED");
            String relative="assets/private-methods/"+mid+"/"+version+"/skills/"+mid+"/SKILL.md";
            method=InstalledPreparationMethod.load(mid,version,root.resolve(relative),"sha256:"+lock.path("files").path(relative).asText(),lockDigest);
            provider=new ManagedOriginalTaskProvider(new ManagedProviderFactoryV01.Connection(ManagedOriginalTaskFactoryV01.KIND,
                URI.create(scope.initial().endpoint()),Duration.ofMillis(scope.initial().timeoutMs()),scope::session,context.currentObservation(),attempt->{}),scope.factory.config(),Clock.systemUTC());
            String ownerBinding=TaskCheckpointPolicy.fingerprint(Map.of("owner",context.ownerRef(),"customer",scope.current().subjectRef(),"task",scope.current().taskRef(),"lock",lockDigest));
            coordination=new TaskCoordination(ownerBinding,Objects.requireNonNull(journal));
            coordination.register(ManagedOriginalTaskProvider.ACTION,new TaskCoordination.Backend(){
                public Map<String,Object> parameters(Map<String,Object> args){return TaskProtocol.parameters(args);}
                public Map<String,Object> initialCheckpoint(String task,Map<String,Object> args){return TaskProtocol.initial(ManagedOriginalTaskProvider.ACTION,task,ownerBinding,args);}
                public void authorize(TaskCoordination.Saved task){currentAuthority();if(!ownerBinding.equals(task.scope()))throw new SecurityException("TASK_SCOPE_CHANGED");}
                public TaskCoordination.Outcome execute(TaskCoordination.Saved saved,TaskCoordination.Approval approval){
                    var bound=new BoundFinanceProvider(provider,scope::grant,scope.factory.requirements());
                    var observed=new ObservedFinanceProvider(bound,ManagedOriginalTaskProvider.ID,context.currentObservation());
                    var result=ProviderTaskExecution.execute(observed,()->request(ManagedOriginalTaskProvider.ACTION,saved.arguments()),
                        new ProviderTaskExecution.Invocation(saved.taskId(),saved.capabilityId(),ownerBinding,saved.arguments(),saved.checkpoint()),approval);
                    if(result.result()==null)return new TaskCoordination.Outcome(Map.of("status",result.decision()==ProviderTaskExecution.Decision.UNOBSERVED?"UNKNOWN":"FAILED","errorCode",result.decision().name()),result.checkpoint(),result.decision()==ProviderTaskExecution.Decision.ACCESS_DENIED);
                    Map<String,Object> response=JSON.convertValue(result.result(),new TypeReference<Map<String,Object>>(){});
                    return new TaskCoordination.Outcome(response,result.checkpoint(),false);
                }
                public Map<String,Object> denied(TaskCoordination.Saved task){return Map.of("status","FAILED","errorCode","ACCESS_DENIED");}
                public Map<String,Object> declined(TaskCoordination.Saved task){return Map.of("status","CANCELLED","businessCancelled",false,"content","已停止本次本地确认；未撤销原业务。");}
            });
        }catch(Exception failure){scope.close();throw failure;}
    }
    private static String sha(byte[] bytes){
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception failure){throw new IllegalArgumentException("TASK_DIGEST_INVALID");}
    }
    private void verifyFiles(){
        try{
            if(!sha(Files.readAllBytes(root.resolve("service-lock.json"))).equals(lockDigest)||!lock.path("files").has("component-plan.json"))throw new SecurityException("TASK_LOCK_CHANGED");
            var entries=lock.path("files").fields();while(entries.hasNext()){
                var item=entries.next();String relative=item.getKey();if(!relative.startsWith("assets/")&&!relative.equals("component-plan.json"))continue;
                Path p=root.resolve(relative).normalize();
                if(!p.startsWith(root)||!Files.isRegularFile(p)||!p.toRealPath().equals(p)||!sha(Files.readAllBytes(p)).equals("sha256:"+item.getValue().asText()))throw new SecurityException("TASK_ARTIFACT_CHANGED");
            }
        }catch(java.io.IOException failure){throw new SecurityException("TASK_ARTIFACT_UNAVAILABLE");}
    }
    private void currentAuthority(){verifyFiles();scope.current();synchronized(state){if(closed)throw new SecurityException("TASK_SESSION_CLOSED");}}
    private void current(int expected){currentAuthority();synchronized(state){if(expected!=generation)throw new SecurityException("TASK_RESPONSE_SUPERSEDED");}}
    private CapabilityRequestV1 request(String capability,Map<String,Object> parameters){
        var l=scope.current();var c=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"task-request-"+UUID.randomUUID(),"task-trace-"+UUID.randomUUID(),l.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,
            "managed-local-host","CUSTODY",l.subjectRef(),l.sessionRef(),FinanceContextV1.AuthenticationLevel.STRONG,l.scopes());
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,capability,capability.equals(ManagedOriginalTaskProvider.QUERY)?CapabilityRequestV1.Operation.QUERY:CapabilityRequestV1.Operation.TASK,c,parameters,null,null,Map.of(),null);
    }
    @Override public String sessionId(){return id;}
    @Override public Map<String,Object> bootstrap(){currentAuthority();return Map.of("scenarioId",TaskInteractionScenarioExtensionV01.ID,"sessionId",id,"status","READY_NOT_EXECUTED","methodId",method.id(),"methodDigest",method.digest(),"runtime","openjiuwen-java@0.1.15","taskRef",scope.current().taskRef());}
    @Override public Map<String,Object> status(){
        currentAuthority();Map<String,Object> response;int expected;
        synchronized(state){expected=generation;response=new LinkedHashMap<>(latest==null?bootstrap():latest);if(active!=null)response.put("status","RUNNING");}
        var result=provider.query(request(ManagedOriginalTaskProvider.QUERY,Map.of()));current(expected);
        if(result.status()!=CapabilityResultV1.Status.COMPLETED)return Map.of("status","FAILED","errorCode","TASK_SNAPSHOT_UNAVAILABLE");
        response.put("taskInteraction",result.data().get("taskInteraction"));return Collections.unmodifiableMap(response);
    }
    public String taskBinding(String handle){currentAuthority();return coordination.confirmationBinding(handle);}
    public void taskAuthorize(boolean approval){currentAuthority();if(approval&&!scope.current().scopes().contains(ManagedOriginalTaskProvider.WRITE))throw new SecurityException("TASK_ACTION_DENIED");}
    public Object taskConfirm(String handle,TaskCoordination.Approval approval){currentAuthority();var result=coordination.confirm(handle,approval);currentAuthority();return result;}
    public Object taskResume(String handle){currentAuthority();var result=coordination.invoke(ManagedOriginalTaskProvider.ACTION,Map.of(TaskCoordination.HANDLE_ARGUMENT,handle));currentAuthority();return result;}
    public Object taskDecline(String handle){currentAuthority();return coordination.decline(handle);}
    @Override public Map<String,Object> call(String action,JsonNode body) throws Exception {
        TaskInteractionCommands.validate(action,body);
        if(action.equals("cancel")||action.equals("end"))return cancel(action.equals("end"));
        currentAuthority();int expected;synchronized(state){expected=generation;}
        if(action.equals("status"))return status();
        if(action.equals("history")){var history=provider.history(request(ManagedOriginalTaskProvider.QUERY,Map.of()));current(expected);return JSON.convertValue(history,new TypeReference<Map<String,Object>>(){});}
        if(action.equals("material")){var value=provider.material(request(ManagedOriginalTaskProvider.QUERY,Map.of()),body.path("materialRef").asText());current(expected);return JSON.convertValue(value,new TypeReference<Map<String,Object>>(){});}
        if(action.equals("taskSnapshot")){
            var result=provider.query(request(ManagedOriginalTaskProvider.QUERY,Map.of()));current(expected);
            if(result.status()!=CapabilityResultV1.Status.COMPLETED)throw new IllegalStateException("TASK_SNAPSHOT_UNAVAILABLE");return result.data();
        }
        if(action.equals("propose")){
            Map<String,Object> params=new LinkedHashMap<>(JSON.convertValue(body,new TypeReference<Map<String,Object>>(){}));
            var result=coordination.invoke(ManagedOriginalTaskProvider.ACTION,params);current(expected);return result;
        }
        synchronized(state){if(active!=null)throw new IllegalStateException("TASK_REQUEST_BUSY");active=Thread.currentThread();latest=null;}
        try{
            var result=new TaskInteractionMethodAgent(context,method,()->provider.query(request(ManagedOriginalTaskProvider.QUERY,Map.of())),()->current(expected)).run(body.path("query").asText());
            current(expected);synchronized(state){current(expected);latest=result;}return result;
        }finally{synchronized(state){if(active==Thread.currentThread())active=null;}}
    }
    private Map<String,Object> cancel(boolean end){
        scope.current();synchronized(state){generation++;if(active!=null)active.interrupt();if(end)closed=true;
            latest=Map.of("status",end?"CLOSED":"CANCELLED","businessCancelled",false,"content","停止本地等待不表示原业务已撤销。");return latest;}
    }
    @Override public void close(){synchronized(state){generation++;closed=true;if(active!=null)active.interrupt();}scope.close();provider.close();}
}
