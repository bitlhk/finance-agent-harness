package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** One Java coordinator owns operation lifecycle; model and tool callbacks never commit terminal state. */
public final class CreditService implements AutoCloseable {
    private final CreditStateStore store;
    private final CreditModelTools tools;
    private final LimitRiskAgent limit;
    private final BenefitsAgent benefits;
    private final Predicate<CreditContracts.Turn> authorize;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public CreditService(CreditStateStore store,CreditModelTools tools,LimitRiskAgent limit,
                         Predicate<CreditContracts.Turn> authorize){
        this(store,tools,limit,null,authorize);
    }
    public CreditService(CreditStateStore store,CreditModelTools tools,LimitRiskAgent limit,
                         BenefitsAgent benefits,Predicate<CreditContracts.Turn> authorize){
        this.store=store;this.tools=tools;this.limit=limit;this.benefits=benefits;this.authorize=authorize;
    }
    private void check(CreditContracts.Turn turn){
        turn.check();if(!authorize.test(turn))throw new SecurityException("ACCESS_DENIED");
    }
    private void audit(CreditContracts.Turn turn,ObjectNode event){
        try{store.appendAudit(turn,event);}catch(IOException failure){throw new IllegalStateException("AUDIT_UNAVAILABLE");}
    }
    private void sourceCheck(CreditContracts.Turn turn,String phase){
        long started=System.nanoTime();String status="failed";
        String callId="call-"+java.util.UUID.randomUUID();
        audit(turn,CreditContracts.JSON.createObjectNode().put("call_id",callId).put("kind","tool")
                .put("name",phase+"-snapshot").put("status","running").put("elapsed_ms",0));
        try{tools.call("snapshot",turn,phase+"-"+CreditContracts.hash(turn.fingerprint()).substring(0,32),null);status="complete";}
        finally{audit(turn,CreditContracts.JSON.createObjectNode().put("call_id",callId).put("kind","tool").put("name",phase+"-snapshot")
                .put("status",status).put("elapsed_ms",(System.nanoTime()-started)/1_000_000.0));}
    }
    public ObjectNode query(CreditContracts.Turn turn) throws Exception {
        check(turn);
        ObjectNode prior=store.begin(turn);
        // Recheck source authority even when returning a previously persisted result.
        try{sourceCheck(turn,"check");check(turn);}
        catch(Exception failure){
            if(prior!=null)throw failure; // Never release the cached facts if current access fails.
            boolean timedOut=turn.remainingMillis()==0;
            return store.finish(turn,timedOut?"timeout":"failed",CreditContracts.JSON.createObjectNode().put("code",timedOut?"DEADLINE_EXCEEDED":"SOURCE_RECHECK_FAILED"));
        }
        if(prior!=null)return store.read(turn);
        Future<ObjectNode> pending=executor.submit(()->{
            if(turn.scene().equals("benefits")) {
                if(benefits==null)throw new IllegalArgumentException("SCENE_NOT_INSTALLED");
                return benefits.run(turn,store.history(turn),store.benefitsMemory(turn),()->{
                    try{return authorize.test(turn)&&store.running(turn);}catch(IOException failure){return false;}
                },event->audit(turn,event),verified->{
                    check(turn);try{store.retainVerified(turn,verified);}catch(IOException failure){throw new IllegalStateException("RETAIN_UNAVAILABLE");}
                });
            }
            if(!turn.scene().equals("limit-risk"))throw new IllegalArgumentException("SCENE_NOT_INSTALLED");
            return limit.run(turn,store.history(turn),()->{
                try{return authorize.test(turn)&&store.running(turn);}catch(IOException failure){return false;}
            },event->audit(turn,event));
        });
        ObjectNode result;String status;
        try {
            result=pending.get(turn.remainingMillis(),TimeUnit.MILLISECONDS);check(turn);
            status=result.path("status").asText();
        }catch(TimeoutException failure){
            pending.cancel(true);status="timeout";result=CreditContracts.JSON.createObjectNode().put("code","DEADLINE_EXCEEDED");
        }catch(InterruptedException failure){
            pending.cancel(true);Thread.currentThread().interrupt();status="cancelled";result=CreditContracts.JSON.createObjectNode().put("code","CANCELLED");
        }catch(Exception failure){
            pending.cancel(true);boolean timedOut=turn.remainingMillis()==0;status=timedOut?"timeout":"failed";
            result=CreditContracts.JSON.createObjectNode().put("code",timedOut?"DEADLINE_EXCEEDED":"CREDIT_QUERY_FAILED");
        }
        if(!authorize.test(turn)){
            status="failed";result=CreditContracts.JSON.createObjectNode().put("code","ACCESS_DENIED");
        }
        // Recheck the snapshot before releasing successful content; revoked data must not persist as success.
        if(status.equals("complete")||status.equals("needs-input")){
            try{sourceCheck(turn,"final");check(turn);}
            catch(Exception changed){boolean timedOut=turn.remainingMillis()==0;status=timedOut?"timeout":"failed";
                result=CreditContracts.JSON.createObjectNode().put("code",timedOut?"DEADLINE_EXCEEDED":"SOURCE_RECHECK_FAILED");}
        }
        return store.finish(turn,status,result);
    }
    public ObjectNode cancel(CreditContracts.Turn turn) throws IOException {
        if(!authorize.test(turn))throw new SecurityException("ACCESS_DENIED");
        var result=store.finish(turn,"cancelled",CreditContracts.JSON.createObjectNode().put("code","CANCELLED"));
        // Cancelling does not re-read business sources and must not release previously cached facts.
        return CreditContracts.JSON.createObjectNode().put("status",result.path("status").asText())
                .put("session_ref",turn.sessionRef()).put("operation_key",turn.operationKey());
    }
    @Override public void close(){executor.shutdownNow();}
}
