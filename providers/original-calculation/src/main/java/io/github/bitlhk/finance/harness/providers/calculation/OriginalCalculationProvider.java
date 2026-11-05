package io.github.bitlhk.finance.harness.providers.calculation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.calculation.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Seven finite QUERY families over a host-owned immutable input snapshot. */
public final class OriginalCalculationProvider implements DescribedFinanceProvider,InvocationAccess,AutoCloseable {
    public static final String ID="original-calculation",SCOPE="calculation:read";
    public static final Set<String> FAMILIES=Set.of("ftp","liquidity","market","operational","logistic","portfolio","factor-backtest");
    private static final ObjectMapper JSON=HarnessJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedProviderFactoryV01.Connection connection;
    private final OriginalCalculationFactoryV01.Config config;
    private final Clock clock;
    private final HttpClient client;
    private final Map<String,Call> active=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private static final class Call {
        final String id;
        final ManagedProviderFactoryV01.SourceSession session;
        final OriginalCalculationFactoryV01.Binding binding;
        final AtomicBoolean cancelled=new AtomicBoolean();
        Call(ManagedProviderFactoryV01.SourceSession session,OriginalCalculationFactoryV01.Binding binding){this(session,binding,UUID.randomUUID().toString().replace("-",""));}
        Call(ManagedProviderFactoryV01.SourceSession session,OriginalCalculationFactoryV01.Binding binding,String id){
            if(id==null || !id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("INVALID_SOURCE_RUN");
            this.session=session;this.binding=binding;this.id=id;
        }
    }
    public OriginalCalculationProvider(ManagedProviderFactoryV01.Connection connection,OriginalCalculationFactoryV01.Config config,Clock clock){
        this.connection=Objects.requireNonNull(connection);this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);
        URI uri=connection.endpoint();
        if(!OriginalCalculationFactoryV01.KIND.equals(connection.kind()) || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort()<1 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath())
                || connection.timeout().toMillis()<10 || connection.timeout().compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("CALCULATION_CONNECTION_INVALID");
        client=HttpClient.newBuilder().connectTimeout(connection.timeout()).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector(){
                public List<java.net.Proxy> select(URI u){return List.of(java.net.Proxy.NO_PROXY);}
                public void connectFailed(URI u,java.net.SocketAddress a,java.io.IOException e){}
            }).build();
    }
    public static String capability(String family){return "calculation."+family+".query";}
    @Override public ProviderDescriptorV1 descriptor(){
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,ID,"0.1.0-SNAPSHOT",FAMILIES.stream().sorted()
            .map(f->new ProviderDescriptorV1.CapabilitySupport(capability(f),CapabilityRequestV1.Operation.QUERY)).toList());
    }
    private ManagedProviderFactoryV01.SourceSession current(CapabilityRequestV1 request){
        if(closed.get())throw new SecurityException("CALCULATION_CLOSED");
        var s=connection.currentSession().get();var c=request.context();
        if(s==null || !s.expiresAt().isAfter(clock.instant()) || !s.principalId().equals(c.principalId()) || !s.subjectRef().equals(c.subjectRef())
                || !s.hostSessionRef().equals(c.sessionRef()) || !c.scopes().contains(SCOPE))throw new SecurityException("ACCESS_DENIED");
        var b=config.binding(c.principalId(),c.subjectRef(),c.sessionRef());
        if(!b.capability().equals(request.capabilityId()))throw new SecurityException("CAPABILITY_NOT_BOUND");
        return s;
    }
    @Override public void checkAccess(CapabilityRequestV1 request){current(request);}
    @Override public CapabilityResultV1 query(CapabilityRequestV1 request){
        if(request.operation()!=CapabilityRequestV1.Operation.QUERY || request.idempotencyKey()!=null || request.taskRef()!=null
                || request.confirmation()!=null || !request.continuation().isEmpty() || !Set.of("parameters").containsAll(request.parameters().keySet()))
            return failed(request,"FINITE_CALCULATION_INPUT_REQUIRED",false);
        Call call=null;boolean success=false;
        long end=System.nanoTime()+connection.timeout().toNanos();
        try{
            var session=current(request);var c=request.context();var binding=config.binding(c.principalId(),c.subjectRef(),c.sessionRef());
            ObjectNode input=(ObjectNode)binding.request();
            if(request.parameters().containsKey("parameters")){
                JsonNode parameters=JSON.valueToTree(request.parameters().get("parameters"));
                if(!parameters.isObject())return failed(request,"FINITE_PARAMETERS_REQUIRED",false);
                input.set("parameters",parameters);
            }
            call=new Call(session,binding);active.put(call.id,call);
            if(closed.get() || call.cancelled.get())throw new CancellationException();
            var start=http(call,"start",Map.of("context",binding.sourceContext(),"request",input,"runId",call.id,
                "deadlineSeconds",Math.max(.01,Math.min(60,connection.timeout().toMillis()/1000.))),end);
            if(!call.id.equals(start.path("runId").asText()))throw new IllegalArgumentException("SOURCE_RUN_ID_MISMATCH");
            JsonNode status=start;
            while("RUNNING".equals(status.path("status").asText())){
                if(call.cancelled.get() || Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>=end)throw new TimeoutException();
                if(!session.equals(current(request)))throw new SecurityException("AUTHORIZATION_CHANGED");
                Thread.sleep(20);status=http(call,"status",Map.of("context",binding.sourceContext(),"runId",call.id),end);
            }
            if(!"SUCCEEDED".equals(status.path("status").asText()) || !status.path("workerStopped").asBoolean(false))
                return failed(request,"ORIGINAL_"+status.path("status").asText("FAILED"),false);
            // Confirm the saved original input against exactly what this host sent.
            JsonNode saved=http(call,"input",Map.of("context",binding.sourceContext(),"runId",call.id),end);
            if(!saved.equals(input))throw new IllegalArgumentException("SOURCE_INPUT_CHANGED");
            JsonNode result=http(call,"result",Map.of("context",binding.sourceContext(),"runId",call.id),end);
            var facts=CalculationResultMapper.map(result,new CalculationResultMapper.Expected(call.id,binding.assetId(),binding.operationId(),
                binding.sourceCommit(),binding.sourceEntity(),binding.filesDigest(),binding.adapterDigest(),binding.lockDigest(),start.path("inputDigest").asText(),start.path("parameterDigest").asText()));
            if(call.cancelled.get() || !session.equals(current(request)))throw new SecurityException("RESULT_DISCLOSURE_CLOSED");
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("calculation",JSON.convertValue(facts,new TypeReference<Map<String,Object>>(){}));
            payload.put("originalReturn",JSON.convertValue(result,new TypeReference<Map<String,Object>>(){}));
            payload.put("input",JSON.convertValue(saved,new TypeReference<Map<String,Object>>(){}));
            payload.put("asOfSemantics","CALCULATION_TIME_NOT_SOURCE_DATE");
            success=true;
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,c.requestId(),c.traceId(),CapabilityResultV1.Status.COMPLETED,
                "已取得本次原算法计算结果。",freeze(payload),facts.dataAsOf()==null?List.of("SOURCE_AS_OF_MISSING"):List.of(),List.of(),ID,facts.calculatedAt(),null,null);
        }catch(SecurityException denied){return failed(request,"ACCESS_DENIED",false);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return failed(request,"ORIGINAL_CALCULATION_CANCELLED",false);
        }catch(TimeoutException timeout){return failed(request,"ORIGINAL_CALCULATION_TIMEOUT",true);
        }catch(CancellationException cancelled){return failed(request,"ORIGINAL_CALCULATION_CANCELLED",false);
        }catch(IllegalArgumentException invalid){return failed(request,"ORIGINAL_CALCULATION_RESULT_INVALID",false);
        }catch(Exception unavailable){return failed(request,"ORIGINAL_CALCULATION_UNAVAILABLE",true);
        }finally{
            if(call!=null){if(!success)cancel(call);active.remove(call.id);}
        }
    }
    private JsonNode http(Call call,String action,Map<String,Object> body,long end) throws Exception{
        long remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();
        var message=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/calculations/"+action))
            .timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json").header("Authorization","Bearer "+call.session.credential())
            .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var pending=client.sendAsync(message,info->new BoundedBody());
        try{
            var response=awaitResponse(pending,remaining);
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST","/v1/calculations/"+action,response.statusCode(),clock.instant()));
            if(response.statusCode()!=200 || !response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))throw new IllegalArgumentException("SOURCE_HTTP_FAILURE");
            return JSON.readTree(response.body());
        }finally{if(!pending.isDone())pending.cancel(true);}
    }
    static <T> T awaitResponse(CompletableFuture<T> pending,long remaining) throws Exception{
        try{return pending.get(remaining,TimeUnit.NANOSECONDS);}
        catch(ExecutionException failed){
            // Either the caller's wait or HttpClient's own timer may expire first.
            if(failed.getCause() instanceof HttpTimeoutException)throw new TimeoutException();
            throw failed;
        }
    }
    private boolean cancel(Call call){
        call.cancelled.set(true);
        // Cleanup uses the captured owner binding and remains callable after query authorization withdrawal.
        boolean interrupted=Thread.interrupted();
        try{
            JsonNode result=http(call,"cancel",Map.of("context",call.binding.sourceContext(),"runId",call.id),System.nanoTime()+Duration.ofSeconds(10).toNanos());
            return result.path("workerStopped").asBoolean(false) && Set.of("CANCELLED","TIMED_OUT","FAILED","SUCCEEDED").contains(result.path("status").asText());
        }catch(Exception unavailable){return false;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    public boolean cancelActive(){boolean stopped=true;for(Call call:List.copyOf(active.values()))stopped=cancel(call)&&stopped;return stopped;}
    /** Reauthorize a stored result without rerunning an algorithm or appending a business tool call. */
    public void checkStoredStatus(String sourceRunId){
        try{
            if(closed.get())throw new SecurityException("CALCULATION_CLOSED");
            var session=connection.currentSession().get();
            if(session==null || !session.expiresAt().isAfter(clock.instant()))throw new SecurityException("ACCESS_DENIED");
            var binding=config.binding(session.principalId(),session.subjectRef(),session.hostSessionRef());
            var call=new Call(session,binding,sourceRunId);
            var result=http(call,"status",Map.of("context",binding.sourceContext(),"runId",sourceRunId),
                System.nanoTime()+Math.min(connection.timeout().toNanos(),Duration.ofSeconds(2).toNanos()));
            if(!sourceRunId.equals(result.path("runId").asText()) || !"SUCCEEDED".equals(result.path("status").asText()) || !result.path("workerStopped").asBoolean(false)
                    || !session.equals(connection.currentSession().get()))throw new SecurityException("STORED_RESULT_ACCESS_DENIED");
        }catch(Exception error){throw new SecurityException("STORED_RESULT_ACCESS_DENIED");}
    }
    public boolean cancelAndClose(){closed.set(true);return cancelActive();}
    @Override public void close(){cancelAndClose();}
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request){return failed(request,"READ_ONLY_PROVIDER",false);}
    private CapabilityResultV1 failed(CapabilityRequestV1 r,String code,boolean retryable){
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,r.context().requestId(),r.context().traceId(),CapabilityResultV1.Status.FAILED,
            "本次计算未取得可披露结果。",Map.of(),List.of(),List.of(),ID,clock.instant(),null,
            new CapabilityResultV1.Failure("ORIGINAL_CALCULATION_FAILED",code,retryable,"请检查授权、输入或原工具状态。",List.of()));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freeze(Map<String,Object> value){
        Map<String,Object> out=new LinkedHashMap<>();value.forEach((k,v)->out.put(k,freezeValue(v)));return Collections.unmodifiableMap(out);
    }
    @SuppressWarnings("unchecked") private static Object freezeValue(Object value){
        if(value instanceof Map<?,?> m)return freeze((Map<String,Object>)m);
        if(value instanceof List<?> l)return l.stream().map(OriginalCalculationProvider::freezeValue).toList();return value;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer b:buffers){if(bytes.size()+b.remaining()>8*1024*1024){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("RESULT_SIZE_LIMIT"));return;}
                byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable t){result.completeExceptionally(t);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
