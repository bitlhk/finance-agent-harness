package io.github.bitlhk.finance.harness.providers.marketrisk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.marketrisk.*;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Host-bound QUERY adapter for the unchanged original Python standard formula. */
public final class MarketRiskCapitalProvider implements DescribedFinanceProvider,InvocationAccess {
    public static final String ID="market-risk-capital-original",CAPABILITY="market.risk.capital.query",SCOPE="market-risk:read";
    private static final ObjectMapper JSON=HarnessJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final ManagedProviderFactoryV01.Connection connection;
    private final MarketRiskCapitalFactoryV01.Config config;
    private final Clock clock;
    private final HttpClient client;
    public MarketRiskCapitalProvider(ManagedProviderFactoryV01.Connection connection,
            MarketRiskCapitalFactoryV01.Config config,Clock clock) {
        this.connection=Objects.requireNonNull(connection);this.config=Objects.requireNonNull(config);this.clock=Objects.requireNonNull(clock);
        URI uri=connection.endpoint();
        if(!MarketRiskCapitalFactoryV01.KIND.equals(connection.kind()) || !"http".equals(uri.getScheme())
                || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null || !Set.of("","/").contains(uri.getPath())
                || connection.timeout().compareTo(Duration.ofSeconds(60))>0)
            throw new IllegalArgumentException("MARKET_RISK_CONNECTION_INVALID");
        client=HttpClient.newBuilder().connectTimeout(connection.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,ID,"0.1.0-SNAPSHOT",
                List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY,CapabilityRequestV1.Operation.QUERY)));
    }
    private ManagedProviderFactoryV01.SourceSession current(CapabilityRequestV1 request) {
        var s=connection.currentSession().get(); var c=request.context();
        if(s==null || !s.expiresAt().isAfter(clock.instant()) || !s.principalId().equals(c.principalId())
                || !s.subjectRef().equals(c.subjectRef()) || !s.hostSessionRef().equals(c.sessionRef())
                || !c.scopes().contains(SCOPE)) throw new SecurityException("ACCESS_DENIED");
        config.binding(c.principalId(),c.subjectRef(),c.sessionRef());
        return s;
    }
    @Override public void checkAccess(CapabilityRequestV1 request) { current(request); }
    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if(!CAPABILITY.equals(request.capabilityId()) || request.operation()!=CapabilityRequestV1.Operation.QUERY
                || !request.parameters().isEmpty() || request.idempotencyKey()!=null || request.taskRef()!=null
                || !request.continuation().isEmpty() || request.confirmation()!=null
                || !request.context().requestId().matches("[A-Za-z0-9_.-]{1,100}")) return failed(request,"READ_ONLY_INPUT_REQUIRED",false);
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            var session=current(request); var c=request.context();
            var binding=config.binding(c.principalId(),c.subjectRef(),c.sessionRef());
            Map<String,Object> input=new LinkedHashMap<>();
            input.put("schemaVersion","finance-original-market-risk-capital-query.v1"); input.put("requestId",c.requestId());
            input.put("principalId",c.principalId());input.put("subjectRef",c.subjectRef());input.put("sessionRef",c.sessionRef());
            input.put("taskRef",binding.taskRef());input.put("snapshotRef",binding.snapshotRef());
            var message=HttpRequest.newBuilder(connection.endpoint().resolve("/v1/market-risk-capital/query"))
                    .timeout(connection.timeout()).header("Content-Type","application/json")
                    .header("Authorization","Bearer "+session.credential()).POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(input))).build();
            pending=client.sendAsync(message,info->new BoundedBody());
            var response=pending.get(connection.timeout().toMillis(),TimeUnit.MILLISECONDS);
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST","/v1/market-risk-capital/query",response.statusCode(),clock.instant()));
            if(!session.equals(current(request))) throw new SecurityException("ACCESS_DENIED");
            if(response.statusCode()!=200) return failed(request,"ORIGINAL_TOOL_HTTP_"+response.statusCode(),response.statusCode()>=500);
            if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                return failed(request,"ORIGINAL_TOOL_RESULT_INVALID",false);
            JsonNode raw=JSON.readTree(response.body());
            var facts=MarketRiskCapitalMapper.map(raw,binding.expected(c.requestId()));
            if(!session.equals(current(request))) throw new SecurityException("ACCESS_DENIED");
            Map<String,Object> payload=new LinkedHashMap<>();
            payload.put("capital",JSON.convertValue(facts,new TypeReference<Map<String,Object>>(){}));
            payload.put("originalReturn",JSON.convertValue(raw,new TypeReference<Map<String,Object>>(){}));
            payload.put("asOfSemantics","CALCULATION_TIME_NOT_SOURCE_DATE");
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,c.requestId(),c.traceId(),CapabilityResultV1.Status.COMPLETED,
                    "已取得本次原标准法资本计算结果。",freeze(payload),facts.source().asOf()==null?List.of("SOURCE_AS_OF_MISSING"):List.of(),
                    List.of(),ID,facts.calculatedAt(),null,null);
        } catch(SecurityException denied) { return failed(request,"ACCESS_DENIED",false);
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return failed(request,"ORIGINAL_TOOL_INTERRUPTED",true);
        } catch(TimeoutException timeout) { return failed(request,"ORIGINAL_TOOL_TIMEOUT",true);
        } catch(IllegalArgumentException invalid) { return failed(request,"ORIGINAL_TOOL_RESULT_INVALID",false);
        } catch(Exception transport) { return failed(request,"ORIGINAL_TOOL_UNAVAILABLE",true);
        } finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { return failed(request,"READ_ONLY_PROVIDER",false); }
    private CapabilityResultV1 failed(CapabilityRequestV1 r,String code,boolean retryable) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,r.context().requestId(),r.context().traceId(),CapabilityResultV1.Status.FAILED,
                "本次资本结果未取得，请检查授权、数据或工具服务。",Map.of(),List.of(),List.of(),ID,clock.instant(),null,
                new CapabilityResultV1.Failure("MARKET_RISK_QUERY_FAILED",code,retryable,"本次资本结果未取得。",List.of()));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freeze(Map<String,Object> value) {
        Map<String,Object> result=new LinkedHashMap<>();value.forEach((k,v)->result.put(k,freezeValue(v)));return Collections.unmodifiableMap(result);
    }
    @SuppressWarnings("unchecked") private static Object freezeValue(Object value) {
        if(value instanceof Map<?,?> m) return freeze((Map<String,Object>)m);
        if(value instanceof List<?> l) return l.stream().map(MarketRiskCapitalProvider::freezeValue).toList();
        return value;
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { subscription=s;s.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer b:buffers) {
                if(bytes.size()+b.remaining()>1048576) { subscription.cancel();result.completeExceptionally(new IllegalArgumentException("RESULT_TOO_LARGE"));return; }
                byte[] data=new byte[b.remaining()];b.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
