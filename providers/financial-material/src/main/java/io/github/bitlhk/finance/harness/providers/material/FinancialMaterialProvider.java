package io.github.bitlhk.finance.harness.providers.material;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.material.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Model-visible parameters cannot select a customer, source file, credential or endpoint. */
public final class FinancialMaterialProvider implements DescribedFinanceProvider,InvocationAccess {
    public static final String ID="financial-material-original",EXTRACT="financial.material.extract",LOCATE="financial.material.locate",SCOPE="material:read";
    private static final ObjectMapper JSON=HarnessJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ManagedProviderFactoryV01.Connection connection;
    private final FinancialMaterialFactoryV01.Config config;
    private final Clock clock;
    private final HttpClient client;
    public FinancialMaterialProvider(ManagedProviderFactoryV01.Connection connection,FinancialMaterialFactoryV01.Config config,Clock clock){
        this.connection=Objects.requireNonNull(connection);this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);
        URI uri=connection.endpoint();
        if(!FinancialMaterialFactoryV01.KIND.equals(connection.kind())||!"http".equals(uri.getScheme())
                ||!"127.0.0.1".equals(uri.getHost())||uri.getPort()<1||uri.getUserInfo()!=null||uri.getQuery()!=null
                ||uri.getFragment()!=null||!Set.of("","/").contains(uri.getPath())||connection.timeout().compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("MATERIAL_CONNECTION_INVALID");
        client=HttpClient.newBuilder().connectTimeout(connection.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public ProviderDescriptorV1 descriptor(){return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,ID,"0.1.0-SNAPSHOT",
            List.of(new ProviderDescriptorV1.CapabilitySupport(EXTRACT,CapabilityRequestV1.Operation.QUERY),new ProviderDescriptorV1.CapabilitySupport(LOCATE,CapabilityRequestV1.Operation.QUERY)));}
    private ManagedProviderFactoryV01.SourceSession current(CapabilityRequestV1 request){
        var s=connection.currentSession().get();var c=request.context();
        if(s==null||!s.expiresAt().isAfter(clock.instant())||!s.principalId().equals(c.principalId())
                ||!s.subjectRef().equals(c.subjectRef())||!s.hostSessionRef().equals(c.sessionRef())||!c.scopes().contains(SCOPE))
            throw new SecurityException("ACCESS_DENIED");
        config.binding(c.principalId(),c.subjectRef(),c.sessionRef());return s;
    }
    public void checkAccess(CapabilityRequestV1 request){current(request);}
    public CapabilityResultV1 query(CapabilityRequestV1 request){
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        if(!Set.of(EXTRACT,LOCATE).contains(request.capabilityId())||request.operation()!=CapabilityRequestV1.Operation.QUERY
                ||!Set.of("pages","fields").containsAll(request.parameters().keySet())||request.idempotencyKey()!=null
                ||request.taskRef()!=null||!request.continuation().isEmpty()||request.confirmation()!=null)
            return failed(request,"MATERIAL_INPUT_INVALID",false);
        try{
            var session=current(request);var c=request.context();var binding=config.binding(c.principalId(),c.subjectRef(),c.sessionRef());
            var mode=LOCATE.equals(request.capabilityId())?MaterialExtractionV1.Mode.LOCATE:binding.mode();
            // The Host's upload selects the current task document. It is looked up
            // using the current private credential, never chosen by model arguments.
            var currentMessage=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/material/current"))
                    .timeout(connection.timeout()).header("Authorization","Bearer "+session.credential()).GET().build();
            pending=client.sendAsync(currentMessage,info->new BoundedBody());
            var selected=pending.get(connection.timeout().toMillis(),TimeUnit.MILLISECONDS);
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("GET","/v1/material/current",selected.statusCode(),clock.instant()));
            if(!session.equals(current(request)))throw new SecurityException("ACCESS_DENIED");
            if(selected.statusCode()!=200)return failed(request,"MATERIAL_HTTP_"+selected.statusCode(),selected.statusCode()>=500);
            var currentDocument=JSON.readTree(selected.body());
            var document=JSON.treeToValue(currentDocument.path("document"),MaterialExtractionV1.Document.class);
            int selectionRevision=currentDocument.path("selectionRevision").asInt(-1);
            if(!binding.principalId().equals(document.principalId())||!binding.subjectRef().equals(document.subjectRef())
                    ||!binding.taskRef().equals(document.taskRef())||!binding.sessionRef().equals(document.sessionRef())||selectionRevision<1)
                return failed(request,"MATERIAL_DOCUMENT_BINDING_MISMATCH",false);
            Map<String,Object> input=new LinkedHashMap<>(request.parameters());
            input.put("schemaVersion","financial-material-query.v1");input.put("requestId",c.requestId());
            input.put("documentRef",document.documentRef());input.put("documentVersion",document.version());input.put("selectionRevision",selectionRevision);input.put("mode",mode.name());
            var message=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/material/extract"))
                    .timeout(connection.timeout()).header("Content-Type","application/json").header("Authorization","Bearer "+session.credential())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build();
            pending=client.sendAsync(message,info->new BoundedBody());
            var response=pending.get(connection.timeout().toMillis(),TimeUnit.MILLISECONDS);
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST","/v1/material/extract",response.statusCode(),clock.instant()));
            if(!session.equals(current(request)))throw new SecurityException("ACCESS_DENIED");
            if(response.statusCode()!=200)return failed(request,"MATERIAL_HTTP_"+response.statusCode(),response.statusCode()>=500);
            if(!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))return failed(request,"MATERIAL_RESULT_INVALID",false);
            var expected=new MaterialEvidenceMapper.Expected(c.requestId(),binding.principalId(),binding.subjectRef(),binding.sessionRef(),binding.taskRef(),document.documentRef(),document.version(),document.sha256(),mode,binding.toolReleaseDigest());
            var material=MaterialEvidenceMapper.map(response.body(),expected);
            if(!session.equals(current(request)))throw new SecurityException("ACCESS_DENIED");
            boolean completed=material.status()==MaterialExtractionV1.Status.COMPLETED;
            boolean partial=material.status()==MaterialExtractionV1.Status.PARTIAL;
            var data=Map.<String,Object>of("material",JSON.convertValue(material,new TypeReference<Map<String,Object>>(){}));
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,c.requestId(),c.traceId(),
                    completed?CapabilityResultV1.Status.COMPLETED:partial?CapabilityResultV1.Status.NEED_USER:CapabilityResultV1.Status.FAILED,
                    completed?"已取得原材料处理结果；解析结果不代表业务核验通过。":partial?"部分材料尚未确认，请核对原文。":"材料处理未完成。",
                    data,material.warningCodes(),List.of(),ID,material.createdAt(),null,
                    completed||partial?null:new CapabilityResultV1.Failure("MATERIAL_PROCESSING_FAILED",material.status().name(),false,"材料处理未完成。",List.of()));
        }catch(SecurityException denied){return failed(request,"ACCESS_DENIED",false);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return failed(request,"MATERIAL_INTERRUPTED",true);
        }catch(TimeoutException timeout){return failed(request,"MATERIAL_TIMEOUT",true);
        }catch(IllegalArgumentException invalid){return failed(request,"MATERIAL_RESULT_INVALID",false);
        }catch(Exception error){return failed(request,"MATERIAL_UNAVAILABLE",true);
        }finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request){return failed(request,"MATERIAL_QUERY_ONLY",false);}
    private CapabilityResultV1 failed(CapabilityRequestV1 request,String code,boolean retryable){var c=request.context();return new CapabilityResultV1(
            CapabilityResultV1.SCHEMA_VERSION,c.requestId(),c.traceId(),CapabilityResultV1.Status.FAILED,"本次未取得有效材料结果。",Map.of(),List.of(),List.of(),ID,clock.instant(),null,
            new CapabilityResultV1.Failure("MATERIAL_QUERY_FAILED",code,retryable,"本次未取得有效材料结果。",List.of()));}
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){for(ByteBuffer b:buffers){if(bytes.size()+b.remaining()>2*1024*1024){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("MATERIAL_RESULT_LIMIT"));return;}byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);}subscription.request(1);}
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
