package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent rankers consume one frozen, eligibility-filtered candidate pool. */
public final class BenefitsEngines {
    @FunctionalInterface public interface Ranker {ObjectNode rank(ObjectNode context) throws Exception;}
    private final Map<String,Ranker> rankers;
    public BenefitsEngines(Map<String,Ranker> rankers){
        if(rankers.isEmpty()||!Set.of("llm","onerec").containsAll(rankers.keySet()))throw new IllegalArgumentException("INVALID_ENGINES");
        this.rankers=Map.copyOf(rankers);
    }
    public ObjectNode run(ObjectNode context,CreditContracts.Turn turn,Runnable guard,Consumer<ObjectNode> observer) throws Exception {
        return run(context,turn,guard,observer,ignored->{});
    }
    public ObjectNode run(ObjectNode context,CreditContracts.Turn turn,Runnable guard,Consumer<ObjectNode> observer,
                          Consumer<ObjectNode> retain) throws Exception {
        ObjectNode output=CreditContracts.JSON.createObjectNode();
        ObjectNode completed=CreditContracts.JSON.createObjectNode();
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        try {
            var futures=new LinkedHashMap<String,Future<ObjectNode>>();
            for(var entry:rankers.entrySet())futures.put(entry.getKey(),executor.submit(()->{
                guard.run();long start=System.nanoTime();String status="failed",id="call-"+UUID.randomUUID();
                Consumer<String> audit=s->observer.accept(CreditContracts.JSON.createObjectNode().put("call_id",id)
                        .put("kind","ranker").put("name",entry.getKey()).put("status",s)
                        .put("elapsed_ms",(System.nanoTime()-start)/1_000_000.0).put("usage_status","NOT_OBSERVED"));
                audit.accept("running");
                try{
                    ObjectNode raw=entry.getValue().rank(context.deepCopy());guard.run();
                    ObjectNode result=BenefitsValidator.ranking(context,raw);
                    if(raw.has("_worker_provenance"))result.set("provenance",raw.get("_worker_provenance"));
                    if(raw.has("_worker_calls"))result.set("reported_calls",raw.get("_worker_calls"));
                    guard.run();
                    synchronized(completed){completed.set(entry.getKey(),result.deepCopy());retain.accept(completed.deepCopy());}
                    status=result.path("status").asText();return result;
                }finally{audit.accept(status);}
            }));
            try{
                for(var entry:futures.entrySet()){
                    try{output.set(entry.getKey(),entry.getValue().get(turn.remainingMillis(),TimeUnit.MILLISECONDS));}
                    catch(ExecutionException failure){output.putObject(entry.getKey()).put("status","failed").put("code","RANKER_RESULT_UNAVAILABLE");}
                }
                guard.run();return output;
            }finally{futures.values().forEach(f->f.cancel(true));}
        }finally{executor.shutdownNow();}
    }
    public static Ranker worker(URI endpoint,String token,CreditContracts.Turn turn,Runnable guard){
        if(!"http".equals(endpoint.getScheme())||!"127.0.0.1".equals(endpoint.getHost())||endpoint.getPort()<1
                ||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null
                ||!Set.of("","/").contains(endpoint.getPath())||token==null||token.isBlank()
                ||token.chars().anyMatch(Character::isWhitespace))throw new IllegalArgumentException("INVALID_WORKER_BINDING");
        return context->{
            guard.run();var client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(3)).build();
            var body=CreditContracts.JSON.createObjectNode();body.set("context",context);
            var request=HttpRequest.newBuilder(endpoint.resolve("/rank")).timeout(Duration.ofMillis(turn.remainingMillis()))
                    .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(CreditContracts.JSON.writeValueAsBytes(body))).build();
            var future=client.sendAsync(request,ignored->new CreditModelTools.LimitedBody());
            HttpResponse<byte[]> response;
            try{response=future.get(turn.remainingMillis(),TimeUnit.MILLISECONDS);}
            finally{future.cancel(true);}
            guard.run();if(response.statusCode()!=200)throw new IllegalStateException("WORKER_UNAVAILABLE");
            var payload=CreditContracts.JSON.readTree(response.body());
            var ranking=CreditContracts.object(payload.path("ranking"));
            var provenance=CreditContracts.JSON.createObjectNode();
            for(String field:List.of("provider","model","base_revision","mapping_digest","adapter","torch","gpu","adapter_reload")){
                var value=payload.path("provenance").path(field);
                if(value.isTextual()&&value.asText().length()<=256)provenance.set(field,value);
            }
            ranking.set("_worker_provenance",provenance);
            var calls=ranking.putArray("_worker_calls");
            if(payload.path("calls").isArray()&&payload.path("calls").size()<=5)for(var raw:payload.path("calls")){
                var call=calls.addObject();
                for(String field:List.of("input_tokens","output_tokens","peak_allocated_bytes")){
                    var value=raw.path(field);
                    if(value.isIntegralNumber()&&value.canConvertToLong()&&value.longValue()>=0)call.set(field,value);
                }
                var elapsed=raw.path("elapsed_ms");
                if(elapsed.isNumber()&&Double.isFinite(elapsed.doubleValue())&&elapsed.doubleValue()>=0)call.set("elapsed_ms",elapsed);
            }
            return ranking;
        };
    }
}
