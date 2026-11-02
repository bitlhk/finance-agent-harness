package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded, nonredirecting tool transport. It cannot invoke the Python Agent endpoint. */
public final class CreditModelTools {
    private final URI endpoint;
    private final String token;
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();
    public CreditModelTools(URI endpoint,String token) {
        if(!Set.of("127.0.0.1","localhost").contains(endpoint.getHost())
                ||!"http".equals(endpoint.getScheme())||endpoint.getUserInfo()!=null
                ||endpoint.getQuery()!=null||endpoint.getFragment()!=null
                ||!Set.of("","/").contains(endpoint.getPath())
                ||token==null||token.isBlank()||token.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("INVALID_MODEL_TOOL_BINDING");
        this.endpoint=endpoint;this.token=token;
    }
    public ObjectNode call(String operation,CreditContracts.Turn turn,String suffix,String modelDigest) {
        if(!Set.of("snapshot","forecast").contains(operation))throw new IllegalArgumentException("INVALID_TOOL");
        turn.check();
        ObjectNode body=CreditContracts.JSON.createObjectNode();
        body.put("schema_version","credit-model-tools.v1").put("request_id","request-"+UUID.randomUUID())
                .put("operation_key",CreditContracts.id(suffix)).put("session_ref",turn.sessionRef())
                .put("observed_at",turn.binding().value().path("observed_at").asText())
                .put("deadline_at",turn.deadline().toString()).put("remaining_ms",Math.min(180000,turn.remainingMillis()));
        body.set("binding",turn.binding().value());
        if(operation.equals("forecast")) {
            if(modelDigest==null||!modelDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MODEL_UNBOUND");
            body.put("model_version",modelDigest).put("model_digest",modelDigest).put("method","model");
        }
        try {
            var request=HttpRequest.newBuilder(endpoint.resolve("/v1/credit-tools/"+operation))
                    .timeout(Duration.ofMillis(turn.remainingMillis()))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(CreditContracts.JSON.writeValueAsBytes(body))).build();
            var future=client.sendAsync(request,ignored->new LimitedBody());
            HttpResponse<byte[]> response;
            try{response=future.get(turn.remainingMillis(),TimeUnit.MILLISECONDS);}
            catch(Exception failure){future.cancel(true);throw failure;}
            turn.check();
            if(response.statusCode()!=200)throw new IllegalStateException("MODEL_TOOL_HTTP_"+response.statusCode());
            ObjectNode result=CreditContracts.object(CreditContracts.JSON.readTree(response.body()));
            if(!"credit-model-tools.v1".equals(result.path("schema_version").asText())
                    ||!body.get("request_id").equals(result.get("request_id"))
                    ||!turn.binding().value().get("snapshot_digest").equals(result.get("snapshot_digest"))
                    ||!"complete".equals(result.path("status").asText()))
                throw new IllegalStateException("MODEL_TOOL_RESULT_REJECTED");
            if(operation.equals("snapshot")) {
                JsonNode view=result.path("result");
                if(!turn.binding().customer().equals(view.path("customer").path("customer_id").asText())
                        ||!turn.binding().value().path("case_id").equals(view.path("case").path("case_id"))
                        ||!turn.binding().value().path("snapshot_digest").asText().equals(CreditContracts.hash(view)))
                    throw new IllegalStateException("SNAPSHOT_IDENTITY_REJECTED");
            } else {
                ObjectNode receipt=CreditContracts.object(result.path("result"));
                String receiptId=CreditContracts.required(receipt,"receipt_id");
                receipt.remove("receipt_id");
                if(!modelDigest.equals(result.path("model_digest").asText())
                        ||!modelDigest.equals(receipt.path("model_digest").asText())
                        ||!turn.binding().customer().equals(receipt.path("customer_id").asText())
                        ||!turn.binding().value().path("case_id").equals(receipt.path("case_id"))
                        ||!turn.binding().value().path("snapshot_digest").equals(receipt.path("input_digest"))
                        ||!receiptId.equals(CreditContracts.hash(receipt)))
                    throw new IllegalStateException("MODEL_IDENTITY_REJECTED");
                Set<Integer> seen=new HashSet<>();
                if(!receipt.path("results").isArray())throw new IllegalStateException("INVALID_FORECAST");
                for(JsonNode window:receipt.path("results")){
                    if(!window.path("horizon_days").isIntegralNumber()
                            ||!seen.add(window.path("horizon_days").intValue())
                            ||!"available".equals(window.path("status").asText())
                            ||!"CNY".equals(window.path("currency").asText()))
                        throw new IllegalStateException("INVALID_FORECAST");
                    long previous=0;
                    for(String field:List.of("p10_minor","p50_minor","p90_minor")){
                        JsonNode value=window.path(field);
                        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<previous)
                            throw new IllegalStateException("INVALID_FORECAST_AMOUNT");
                        previous=value.longValue();
                    }
                    JsonNode probability=window.path("shortage_probability");
                    if(!probability.isNumber()||!Double.isFinite(probability.doubleValue())
                            ||probability.doubleValue()<0||probability.doubleValue()>1)
                        throw new IllegalStateException("INVALID_FORECAST_PROBABILITY");
                }
                if(!seen.equals(Set.of(1,3,7)))throw new IllegalStateException("MISSING_FORECAST_HORIZON");
            }
            return result;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new CancellationException();}
        catch(TimeoutException failure){throw new IllegalStateException("DEADLINE_EXCEEDED");}
        catch(java.io.IOException|ExecutionException failure){throw new IllegalStateException("MODEL_TOOLS_UNAVAILABLE");}
    }
    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers){
            for(ByteBuffer b:buffers){
                if(b.remaining()>2*1024*1024-bytes.size()){
                    subscription.cancel();result.completeExceptionally(new IllegalStateException("TOOL_BODY_TOO_LARGE"));return;
                }
                byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error){result.completeExceptionally(error);}
        public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
