package io.github.bitlhk.finance.harness.providers.taskinteraction;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Finite managed task adapter; confirmation/recovery stay in existing Host TaskCoordination. */
public final class ManagedOriginalTaskProvider implements DescribedFinanceProvider,InvocationAccess,AutoCloseable {
    public static final String ID="original-task-managed",QUERY="custody.task.query",ACTION="custody.task.action",READ="custody-task:read",WRITE="custody-task:operate";
    private static final ObjectMapper JSON=TaskInteractionMapper.json();
    private final ManagedProviderFactoryV01.Connection connection;
    private final ManagedOriginalTaskFactoryV01.Config config;
    private final Clock clock;
    private final HttpClient client;
    private volatile boolean closed;
    public ManagedOriginalTaskProvider(ManagedProviderFactoryV01.Connection c,ManagedOriginalTaskFactoryV01.Config config,Clock clock){
        this.connection=Objects.requireNonNull(c);this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);
        URI u=c.endpoint();
        if(!ManagedOriginalTaskFactoryV01.KIND.equals(c.kind())||!"http".equals(u.getScheme())||!"127.0.0.1".equals(u.getHost())||u.getPort()<1
                ||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!Set.of("","/").contains(u.getPath())||c.timeout().toMillis()<100||c.timeout().compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("TASK_CONNECTION_INVALID");
        client=HttpClient.newBuilder().connectTimeout(c.timeout()).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,java.io.IOException e){}}).build();
    }
    @Override public ProviderDescriptorV1 descriptor(){return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,ID,"0.1.0-SNAPSHOT",List.of(
        new ProviderDescriptorV1.CapabilitySupport(QUERY,CapabilityRequestV1.Operation.QUERY),new ProviderDescriptorV1.CapabilitySupport(ACTION,CapabilityRequestV1.Operation.TASK)));}
    private ManagedProviderFactoryV01.SourceSession current(CapabilityRequestV1 r){
        var session=connection.currentSession().get();var ctx=r.context();
        if(closed||session==null||!session.expiresAt().isAfter(clock.instant())||!session.principalId().equals(ctx.principalId())||!session.subjectRef().equals(ctx.subjectRef())
                ||!session.hostSessionRef().equals(ctx.sessionRef())||!ctx.scopes().contains(r.capabilityId().equals(ACTION)?WRITE:READ))throw new SecurityException("TASK_ACCESS_DENIED");
        binding(r);return session;
    }
    private ManagedOriginalTaskFactoryV01.Binding binding(CapabilityRequestV1 r){var c=r.context();return config.binding(c.principalId(),c.subjectRef(),c.sessionRef());}
    @Override public void checkAccess(CapabilityRequestV1 r){current(r);}
    public TaskInteractionV1 snapshot(CapabilityRequestV1 r){
        var session=current(r);var b=binding(r);
        var result=TaskInteractionMapper.snapshot(http(session,"GET",b.taskRef(),"snapshot",null),b.expected());
        if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");return result;
    }
    public JsonNode history(CapabilityRequestV1 r){
        var session=current(r);var b=binding(r);JsonNode result=TaskInteractionMapper.parse(http(session,"GET",b.taskRef(),"history",null));
        TaskInteractionMapper.fields(result,Set.of("entries"));if(!result.path("entries").isArray())TaskInteractionMapper.invalid();
        if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");return result.deepCopy();
    }
    public JsonNode material(CapabilityRequestV1 r,String ref){
        TaskInteractionMapper.id(ref);var session=current(r);var b=binding(r);
        JsonNode result=TaskInteractionMapper.parse(http(session,"GET",b.taskRef(),"materials/"+ref,null));
        TaskInteractionMapper.fields(result,Set.of("materialRef","digest","bytes","mediaType","contentBase64"));
        if(!ref.equals(result.path("materialRef").asText())||!result.path("bytes").isIntegralNumber()||result.path("bytes").asLong()<1||result.path("bytes").asLong()>65536)TaskInteractionMapper.invalid();
        try{
            byte[] raw=Base64.getDecoder().decode(result.path("contentBase64").asText());
            if(raw.length!=result.path("bytes").asInt()||!("sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw))).equals(result.path("digest").asText()))TaskInteractionMapper.invalid();
        }catch(Exception failure){throw new IllegalArgumentException("TASK_MATERIAL_INVALID");}
        if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");return result.deepCopy();
    }
    private static Map<String,Object> document(TaskInteractionV1 value){
        var node=JSON.valueToTree(value);((ObjectNode)node).put("schemaVersion",TaskInteractionV1.SCHEMA_VERSION);
        return JSON.convertValue(node,new TypeReference<Map<String,Object>>(){});
    }
    @Override public CapabilityResultV1 query(CapabilityRequestV1 r){
        if(!QUERY.equals(r.capabilityId())||r.operation()!=CapabilityRequestV1.Operation.QUERY||!r.parameters().isEmpty()||r.confirmation()!=null||r.taskRef()!=null||!r.continuation().isEmpty()||r.idempotencyKey()!=null)return failed(r,"TASK_QUERY_INPUT_REJECTED");
        try{return result(r,CapabilityResultV1.Status.COMPLETED,Map.of("taskInteraction",document(snapshot(r))),null,null);}
        catch(SecurityException denied){return failed(r,"ACCESS_DENIED");}
        catch(Exception failure){return failed(r,"TASK_SOURCE_UNAVAILABLE");}
    }
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 r){
        if(!ACTION.equals(r.capabilityId())||r.operation()!=CapabilityRequestV1.Operation.TASK)return failed(r,"TASK_OPERATION_REJECTED");
        var b=binding(r);boolean submitted=false;
        try{
            var session=current(r);
            if(r.taskRef()!=null&& !b.taskRef().equals(r.taskRef()))throw new SecurityException("TASK_REFERENCE_MISMATCH");
            if(Boolean.TRUE.equals(r.continuation().get("submitOutcomeUnknown"))){
                // Only reads after uncertainty. Never resend a bank/action POST here.
                JsonNode history=TaskInteractionMapper.parse(http(session,"GET",b.taskRef(),"history",null));
                for(JsonNode entry:history.path("entries"))if(entry.path("input").path("requestId").asText().equals(r.idempotencyKey())){
                    var receipt=entry.path("receipt");
                    if(!b.taskRef().equals(receipt.path("taskRef").asText())||!r.context().principalId().equals(receipt.path("actorRef").asText()))throw new SecurityException("TASK_RECEIPT_BINDING_MISMATCH");
                    if(Set.of("SUCCEEDED","REJECTED").contains(receipt.path("status").asText())){
                        if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");
                        return result(r,receipt.path("status").asText().equals("SUCCEEDED")?CapabilityResultV1.Status.COMPLETED:CapabilityResultV1.Status.FAILED,
                            Map.of("receipt",JSON.convertValue(receipt,new TypeReference<Map<String,Object>>(){})),null,
                            receipt.path("status").asText().equals("REJECTED")?new CapabilityResultV1.Failure("BUSINESS_REJECTION","ORIGINAL_REJECTED",false,"原操作未通过",List.of()):null);
                    }
                }
                return unknown(r,b.taskRef());
            }
            ObjectNode body=JSON.valueToTree(r.parameters());
            TaskInteractionMapper.fields(body,Set.of("action","expectedVersion","materialVersion","parameters"));
            // These values are derived from trusted TASK coordination, never model controls.
            body.put("schemaVersion","finance-task-action.v1");
            body.put("requestId",Objects.requireNonNull(r.idempotencyKey()));
            body.put("taskRef",b.taskRef());body.put("executionConfirmed",true);
            var action=TaskInteractionActions.parse(body);
            TaskInteractionActions.validate(snapshot(r),action);
            if(r.confirmation()==null||!r.confirmation().confirmed())return result(r,CapabilityResultV1.Status.NEED_CONFIRMATION,
                Map.of("taskInteraction",document(snapshot(r)),"requestedAction",action.action(),"expectedVersion",action.expectedVersion()),
                new CapabilityResultV1.TaskState(b.taskRef(),"AWAITING_CONFIRMATION",true,Map.of()),null);
            if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");
            submitted=true;
            JsonNode receipt=TaskInteractionMapper.parse(http(session,"POST",b.taskRef(),"actions",JSON.writeValueAsBytes(body)));
            if(!b.taskRef().equals(receipt.path("taskRef").asText())||!action.requestId().equals(receipt.path("requestId").asText())||!action.action().equals(receipt.path("action").asText())
                    ||!r.context().principalId().equals(receipt.path("actorRef").asText())||!action.expectedVersion().equals(receipt.path("beforeVersion").asText()))throw new IllegalArgumentException("TASK_RECEIPT_BINDING_MISMATCH");
            if(!session.equals(current(r)))throw new SecurityException("TASK_AUTHORITY_CHANGED");
            var data=Map.<String,Object>of("receipt",JSON.convertValue(receipt,new TypeReference<Map<String,Object>>(){}));
            return switch(receipt.path("status").asText()){
                case "SUCCEEDED" -> result(r,CapabilityResultV1.Status.COMPLETED,data,null,null);
                case "REJECTED" -> result(r,CapabilityResultV1.Status.FAILED,data,null,new CapabilityResultV1.Failure("BUSINESS_REJECTION","ORIGINAL_REJECTED",false,"原操作未通过",List.of()));
                default -> unknown(r,b.taskRef());
            };
        }catch(SecurityException denied){return failed(r,"ACCESS_DENIED");}
        catch(Exception failure){return submitted?unknown(r,b.taskRef()):failed(r,"TASK_REQUEST_REJECTED");}
    }
    private CapabilityResultV1 unknown(CapabilityRequestV1 r,String task){return result(r,CapabilityResultV1.Status.UNKNOWN,Map.of(),new CapabilityResultV1.TaskState(task,"UNKNOWN",false,Map.of("submitOutcomeUnknown",true)),null);}
    private CapabilityResultV1 failed(CapabilityRequestV1 r,String code){return result(r,CapabilityResultV1.Status.FAILED,Map.of(),null,new CapabilityResultV1.Failure("ACCESS_DENIED".equals(code)?"AUTHORIZATION":"VALIDATION",code,false,"任务操作未完成",List.of()));}
    private CapabilityResultV1 result(CapabilityRequestV1 r,CapabilityResultV1.Status status,Map<String,Object> data,CapabilityResultV1.TaskState task,CapabilityResultV1.Failure error){
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,r.context().requestId(),r.context().traceId(),status,"原托管任务；业务状态见记录",data,List.of(),List.of(),ID,clock.instant(),task,error);
    }
    private byte[] http(ManagedProviderFactoryV01.SourceSession session,String method,String task,String route,byte[] body){
        var request=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/tasks/"+task+"/"+route)).timeout(connection.timeout()).header("Authorization","Bearer "+session.credential()).header("Accept","application/json");
        if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try{
            var response=client.send(request.build(),info->new LimitedBody());
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt(method,"/v1/tasks/{task}/"+route,response.statusCode(),clock.instant()));
            if(!Set.of(200,202,422).contains(response.statusCode()))throw new IllegalArgumentException("TASK_SOURCE_HTTP_FAILED");
            return response.body();
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("TASK_SOURCE_INTERRUPTED");}
        catch(Exception failure){throw new IllegalStateException("TASK_SOURCE_HTTP_FAILED");}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            try{for(var b:buffers){if((long)bytes.size()+b.remaining()>262144)throw new IllegalArgumentException("TASK_SOURCE_LIMIT");byte[] raw=new byte[b.remaining()];b.get(raw);bytes.write(raw);}subscription.request(1);}
            catch(Exception failure){subscription.cancel();result.completeExceptionally(failure);}
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
    @Override public void close(){closed=true;client.shutdownNow();}
}
