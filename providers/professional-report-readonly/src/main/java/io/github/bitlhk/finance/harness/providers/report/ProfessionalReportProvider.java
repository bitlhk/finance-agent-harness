package io.github.bitlhk.finance.harness.providers.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.report.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite original report operations; byte-verified evidence and current access on every read. */
public final class ProfessionalReportProvider implements DescribedFinanceProvider,InvocationAccess,AutoCloseable {
    public static final String ID="professional-report-readonly",SCOPE="professional-report:read";
    private static final ObjectMapper JSON=ReportMapper.json();
    private static final Set<String> WEALTH_TOOLS=Set.of("wealth_assistant_context_probe","wealth_assistant_customer_list","wealth_assistant_customer_detail");
    private final ManagedProviderFactoryV01.Connection connection;
    private final ProfessionalReportFactoryV01.Config config;
    private final Clock clock;
    private final HttpClient client;
    private final Map<String,Call> active=new ConcurrentHashMap<>();
    private final AtomicBoolean closed=new AtomicBoolean();
    private static final class Call {
        final String id;
        final ManagedProviderFactoryV01.SourceSession session;
        final ProfessionalReportFactoryV01.Binding binding;
        final AtomicBoolean cancelled=new AtomicBoolean();
        Call(ManagedProviderFactoryV01.SourceSession session,ProfessionalReportFactoryV01.Binding binding,String id) {
            if(id==null||!id.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("INVALID_SOURCE_RUN");
            this.session=session;this.binding=binding;this.id=id;
        }
    }
    public ProfessionalReportProvider(ManagedProviderFactoryV01.Connection c,ProfessionalReportFactoryV01.Config config,Clock clock) {
        connection=Objects.requireNonNull(c);this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);
        URI u=c.endpoint();
        if(!ProfessionalReportFactoryV01.KIND.equals(c.kind())||!"http".equals(u.getScheme())||!"127.0.0.1".equals(u.getHost())||u.getPort()<1
                ||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!Set.of("","/").contains(u.getPath())
                ||c.timeout().toMillis()<100||c.timeout().compareTo(Duration.ofSeconds(120))>0)throw new IllegalArgumentException("REPORT_CONNECTION_INVALID");
        client=HttpClient.newBuilder().connectTimeout(c.timeout()).followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector(){public List<java.net.Proxy> select(URI uri){return List.of(java.net.Proxy.NO_PROXY);}public void connectFailed(URI u,java.net.SocketAddress a,java.io.IOException e){}}).build();
    }
    public static String capability(String asset) {
        return switch(asset){case "wealth-kyc"->"professional.report.wealth.query";case "market-risk-report"->"professional.report.market.query";default->throw new IllegalArgumentException("REPORT_ASSET_REJECTED");};
    }
    @Override public ProviderDescriptorV1 descriptor(){return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,ID,"0.1.0-SNAPSHOT",List.of(
        new ProviderDescriptorV1.CapabilitySupport("professional.report.wealth.query",CapabilityRequestV1.Operation.QUERY),new ProviderDescriptorV1.CapabilitySupport("professional.report.market.query",CapabilityRequestV1.Operation.QUERY)));}
    private ManagedProviderFactoryV01.SourceSession current(CapabilityRequestV1 r) {
        if(closed.get())throw new SecurityException("REPORT_CLOSED");
        var s=connection.currentSession().get();var c=r.context();
        if(s==null||!s.expiresAt().isAfter(clock.instant())||!s.principalId().equals(c.principalId())||!s.subjectRef().equals(c.subjectRef())
                ||!s.hostSessionRef().equals(c.sessionRef())||!c.scopes().contains(SCOPE))throw new SecurityException("ACCESS_DENIED");
        if(!config.binding(c.principalId(),c.subjectRef(),c.sessionRef()).capability().equals(r.capabilityId()))throw new SecurityException("REPORT_CAPABILITY_NOT_BOUND");
        return s;
    }
    @Override public void checkAccess(CapabilityRequestV1 r){current(r);}
    private static JsonNode sorted(JsonNode value) {
        if(value.isObject()){var out=JSON.createObjectNode();var names=new ArrayList<String>();value.fieldNames().forEachRemaining(names::add);Collections.sort(names);for(String k:names)out.set(k,sorted(value.get(k)));return out;}
        if(value.isArray()){var out=JSON.createArrayNode();value.forEach(v->out.add(sorted(v)));return out;}
        if(value.isFloatingPointNumber())throw new IllegalArgumentException("FINITE_REPORT_ARGUMENTS_REQUIRED");return value;
    }
    private ObjectNode input(CapabilityRequestV1 r,ProfessionalReportFactoryV01.Binding binding) {
        if(!Set.of("tool","arguments").containsAll(r.parameters().keySet()))throw new IllegalArgumentException("REPORT_MODEL_FIELDS_REJECTED");
        ObjectNode n=(ObjectNode)binding.request();boolean wealth=n.path("assetId").asText().equals("wealth-kyc");
        if(wealth) {
            String tool=Objects.toString(r.parameters().getOrDefault("tool",n.path("tool").asText()));
            if(!WEALTH_TOOLS.contains(tool))throw new IllegalArgumentException("REPORT_TOOL_REJECTED");n.put("tool",tool);
            if(r.parameters().containsKey("arguments"))n.set("arguments",JSON.valueToTree(r.parameters().get("arguments")));
            JsonNode args=n.path("arguments");if(!args.isObject())throw new IllegalArgumentException("REPORT_ARGUMENTS_REJECTED");
            Set<String> allowed=switch(tool){case "wealth_assistant_context_probe"->Set.of();case "wealth_assistant_customer_list"->Set.of("search","page","page_size");default->Set.of("customer_id");};
            args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("REPORT_ARGUMENTS_REJECTED");});
            if(tool.equals("wealth_assistant_customer_detail")&&(!args.path("customer_id").asText().equals(binding.subjectRef())||args.size()!=1))throw new SecurityException("CURRENT_CUSTOMER_REQUIRED");
            if(args.has("search")&&(!args.get("search").isTextual()||args.get("search").asText().length()>128))throw new IllegalArgumentException("REPORT_ARGUMENTS_REJECTED");
            for(String k:List.of("page","page_size"))if(args.has(k)&&(!args.get(k).isIntegralNumber()||args.get(k).asInt()<1||args.get(k).asInt()>(k.equals("page")?100:20)))throw new IllegalArgumentException("REPORT_ARGUMENTS_REJECTED");
        }else if(!r.parameters().isEmpty())throw new IllegalArgumentException("FINITE_MARKET_REPORT_REQUIRED");
        String execution=r.context().traceId();if(!execution.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("REPORT_EXECUTION_INVALID");
        ((ObjectNode)n.path("binding")).put("executionId",execution);
        var identity=n.deepCopy();identity.remove("binding");
        ((ObjectNode)n.path("binding")).put("inputDigest",ReportMapper.sha(ReportMapper.bytes(sorted(identity))));
        return n;
    }
    @Override public CapabilityResultV1 query(CapabilityRequestV1 r) {
        if(r.operation()!=CapabilityRequestV1.Operation.QUERY||r.idempotencyKey()!=null||r.taskRef()!=null||r.confirmation()!=null||!r.continuation().isEmpty())return failed(r,"FINITE_REPORT_INPUT_REQUIRED",false);
        Call call=null;boolean success=false;long end=System.nanoTime()+connection.timeout().toNanos();
        try {
            var session=current(r);var c=r.context();var binding=config.binding(c.principalId(),c.subjectRef(),c.sessionRef());var input=input(r,binding);
            call=new Call(session,binding,UUID.randomUUID().toString().replace("-",""));active.put(call.id,call);
            if(closed.get()||call.cancelled.get())throw new CancellationException();
            JsonNode status=http(call,"start",Map.of("context",binding.context(),"request",input,"runId",call.id,"deadlineSeconds",connection.timeout().toMillis()/1000.),end);
            if(!call.id.equals(status.path("runId").asText()))throw new IllegalArgumentException("SOURCE_RUN_MISMATCH");
            while("RUNNING".equals(status.path("status").asText())) {
                if(call.cancelled.get()||Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(System.nanoTime()>=end)throw new TimeoutException();if(!session.equals(current(r)))throw new SecurityException("AUTHORITY_CHANGED");
                Thread.sleep(20);status=http(call,"status",Map.of("context",binding.context(),"runId",call.id),end);
            }
            if(!Set.of("SUCCEEDED","PARTIAL").contains(status.path("status").asText())||!status.path("workerStopped").asBoolean(false))return failed(r,"REPORT_SOURCE_"+status.path("status").asText("FAILED"),false);
            if(!http(call,"input",Map.of("context",binding.context(),"runId",call.id),end).equals(input))throw new IllegalArgumentException("SOURCE_INPUT_CHANGED");
            JsonNode result=http(call,"result",Map.of("context",binding.context(),"runId",call.id),end);
            String originalName=binding.request().path("assetId").asText().equals("wealth-kyc")?"raw-return.json":"source-return.json";
            JsonNode original=null;for(JsonNode a:result.path("artifacts"))if(a.path("name").asText().equals(originalName)){if(original!=null)throw new IllegalArgumentException("AMBIGUOUS_SOURCE_ARTIFACT");original=a;}
            if(original==null)throw new IllegalArgumentException("ORIGINAL_DOCUMENT_MISSING");
            JsonNode artifact=http(call,"artifact",Map.of("context",binding.context(),"runId",call.id,"artifactId",original.path("id").asText()),end);
            byte[] raw=decodeArtifact(artifact,original);
            var report=ReportMapper.mapSource(ReportMapper.bytes(result),new ReportMapper.Expected(call.id,c.traceId(),binding.context(),input.path("binding")),raw);
            if(call.cancelled.get()||!session.equals(current(r)))throw new SecurityException("REPORT_DISCLOSURE_CLOSED");
            Map<String,Object> data=new LinkedHashMap<>();data.put("report",JSON.convertValue(report.document(),new TypeReference<Map<String,Object>>(){}));
            data.put("input",JSON.convertValue(input,new TypeReference<Map<String,Object>>(){}));data.put("originalDocumentBase64",Base64.getEncoder().encodeToString(raw));
            success=true;return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,c.requestId(),c.traceId(),CapabilityResultV1.Status.COMPLETED,
                "已取得原方法结果；专业复核状态单独记录。",freeze(data),report.status().equals("PARTIAL")?List.of("REPORT_PARTIAL"):List.of(),List.of(),ID,clock.instant(),null,null);
        }catch(SecurityException denied){return failed(r,"ACCESS_DENIED",false);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return failed(r,"REPORT_CANCELLED",false);
        }catch(TimeoutException timeout){return failed(r,"REPORT_TIMEOUT",true);
        }catch(CancellationException cancelled){return failed(r,"REPORT_CANCELLED",false);
        }catch(IllegalArgumentException invalid){return failed(r,"REPORT_RESULT_INVALID",false);
        }catch(Exception failure){return failed(r,"REPORT_SOURCE_UNAVAILABLE",true);
        }finally{if(call!=null){if(!success)cancel(call);active.remove(call.id);}}
    }
    private static byte[] decodeArtifact(JsonNode payload,JsonNode expected) {
        if(!payload.path("artifact").equals(expected)||!payload.path("contentBase64").isTextual()||payload.path("contentBase64").asText().length()>3*1024*1024)throw new IllegalArgumentException("REPORT_ARTIFACT_INVALID");
        byte[] raw=Base64.getDecoder().decode(payload.path("contentBase64").asText());
        if(raw.length!=expected.path("bytes").asLong()||!ReportMapper.sha(raw).equals(expected.path("sha256").asText()))throw new IllegalArgumentException("REPORT_ARTIFACT_CHANGED");return raw;
    }
    public JsonNode artifact(CapabilityRequestV1 r,String sourceRun,JsonNode expected) {
        var session=current(r);var binding=config.binding(r.context().principalId(),r.context().subjectRef(),r.context().sessionRef());
        if(!sourceRun.equals(expected.path("sourceResultId").asText())||!expected.path("runId").asText().equals(r.context().traceId()))throw new SecurityException("REPORT_ARTIFACT_SCOPE_MISMATCH");
        try{var call=new Call(session,binding,sourceRun);var payload=http(call,"artifact",Map.of("context",binding.context(),"runId",sourceRun,"artifactId",expected.path("id").asText()),System.nanoTime()+connection.timeout().toNanos());
            decodeArtifact(payload,expected);if(!session.equals(current(r)))throw new SecurityException("ACCESS_DENIED");return payload.deepCopy();
        }catch(Exception failure){throw new SecurityException("REPORT_ARTIFACT_ACCESS_DENIED");}
    }
    public void checkStoredStatus(CapabilityRequestV1 r,String sourceRun) {
        try{var session=current(r);var binding=config.binding(r.context().principalId(),r.context().subjectRef(),r.context().sessionRef());
            var result=http(new Call(session,binding,sourceRun),"status",Map.of("context",binding.context(),"runId",sourceRun),System.nanoTime()+connection.timeout().toNanos());
            if(!sourceRun.equals(result.path("runId").asText())||!Set.of("SUCCEEDED","PARTIAL").contains(result.path("status").asText())||!result.path("workerStopped").asBoolean(false)||!session.equals(current(r)))throw new SecurityException("ACCESS_DENIED");
        }catch(Exception failure){throw new SecurityException("STORED_REPORT_ACCESS_DENIED");}
    }
    private JsonNode http(Call call,String action,Map<String,Object> body,long end) throws Exception{
        long remaining=end-System.nanoTime();if(remaining<=0)throw new TimeoutException();
        var message=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/reports/"+action))
            .timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json").header("Authorization","Bearer "+call.session.credential())
            .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        var pending=client.sendAsync(message,info->new BoundedBody());
        try{
            var response=awaitResponse(pending,remaining);
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST","/v1/reports/"+action,response.statusCode(),clock.instant()));
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
            JsonNode result=http(call,"cancel",Map.of("context",call.binding.context(),"runId",call.id),System.nanoTime()+Duration.ofSeconds(10).toNanos());
            return result.path("workerStopped").asBoolean(false) && Set.of("CANCELLED","TIMED_OUT","FAILED","SUCCEEDED").contains(result.path("status").asText());
        }catch(Exception unavailable){return false;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }

    public boolean cancelActive(){boolean stopped=true;for(Call c:List.copyOf(active.values()))stopped=cancel(c)&&stopped;return stopped;}
    public boolean cancelAndClose(){closed.set(true);return cancelActive();}
    @Override public void close(){cancelAndClose();}
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 r){return failed(r,"READ_ONLY_PROVIDER",false);}
    private CapabilityResultV1 failed(CapabilityRequestV1 r,String code,boolean retryable){return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,r.context().requestId(),r.context().traceId(),CapabilityResultV1.Status.FAILED,
        "本次报告未取得可披露结果。",Map.of(),List.of(),List.of(),ID,clock.instant(),null,new CapabilityResultV1.Failure("REPORT_SOURCE_FAILED",code,retryable,"请核对当前权限、输入与原服务状态。",List.of()));}
    @SuppressWarnings("unchecked") private static Map<String,Object> freeze(Map<String,Object> value){var out=new LinkedHashMap<String,Object>();value.forEach((k,v)->out.put(k,freezeValue(v)));return Collections.unmodifiableMap(out);}
    @SuppressWarnings("unchecked") private static Object freezeValue(Object value){if(value instanceof Map<?,?> m)return freeze((Map<String,Object>)m);if(value instanceof List<?> l)return l.stream().map(ProfessionalReportProvider::freezeValue).toList();return value;}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer b:buffers){if(bytes.size()+b.remaining()>4*1024*1024){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("RESULT_SIZE_LIMIT"));return;}
                byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}
            subscription.request(1);
        }
        public void onError(Throwable t){result.completeExceptionally(t);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
