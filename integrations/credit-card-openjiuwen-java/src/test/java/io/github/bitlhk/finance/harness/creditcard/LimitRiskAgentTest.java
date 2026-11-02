package io.github.bitlhk.finance.harness.creditcard;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LimitRiskAgentTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path stateDirectory;
    @Test void invalidModelResultStillPersistsNativeAndPreflightCallAudit() throws Exception {
        var modelCalls=new AtomicInteger();var sourceCalls=new AtomicInteger();
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var view=CreditModelToolsTest.snapshot();var turn=CreditModelToolsTest.turn(view);
        source.createContext("/v1/credit-tools/snapshot",e->{
            sourceCalls.incrementAndGet();var request=CreditContracts.JSON.readTree(e.getRequestBody());
            var result=CreditContracts.JSON.createObjectNode().put("schema_version","credit-model-tools.v1")
                    .put("request_id",request.path("request_id").asText()).put("status","complete")
                    .put("snapshot_digest",CreditContracts.hash(view));result.set("result",view);
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(result);e.sendResponseHeaders(200,bytes.length);
            e.getResponseBody().write(bytes);e.close();
        });
        model.createContext("/",e->{
            e.getRequestBody().readAllBytes();modelCalls.incrementAndGet();
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(Map.of("id","probe","object","chat.completion",
                    "created",1,"model","deepseek-flash","choices",List.of(Map.of("index",0,"finish_reason","stop",
                    "message",Map.of("role","assistant","content","{\"status\":\"complete\",\"questions\":[]}")))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);
            e.getResponseBody().write(bytes);e.close();
        });
        source.start();model.start();
        try(var store=new CreditStateStore(stateDirectory)){
            var tools=new CreditModelTools(URI.create("http://127.0.0.1:"+source.getAddress().getPort()),"synthetic-tools");
            var agent=new LimitRiskAgent(tools,"http://127.0.0.1:"+model.getAddress().getPort()+"/v1",
                    "synthetic-model","deepseek-flash","b".repeat(64));
            try(var service=new CreditService(store,tools,agent,t->true)){
                var result=service.query(turn);
                assertEquals("failed",result.path("status").asText());
                assertEquals(2,result.path("execution").size());
                assertEquals("check-snapshot",result.path("execution").get(0).path("name").asText());
                assertEquals("native-agent",result.path("execution").get(1).path("kind").asText());
                assertEquals("NOT_OBSERVED",result.path("execution").get(1).path("usage_status").asText());
                assertEquals("failed",service.query(turn).path("status").asText());
                assertEquals(1,modelCalls.get());assertEquals(2,sourceCalls.get());
            }
        }finally{source.stop(0);model.stop(0);}
    }
    @Test void nativeLoopInvokesTwoRealHttpToolsAndReturnsOnlyTheirFinancialValues() throws Exception {
        var modelCalls=new AtomicInteger();var toolCalls=new AtomicInteger();
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var view=CreditModelToolsTest.snapshot();var turn=CreditModelToolsTest.turn(view);
        var receipt=CreditContracts.JSON.createObjectNode().put("schema_version","1.0")
                .put("customer_id","customer-1").put("case_id","case-1")
                .put("model_digest","b".repeat(64)).put("input_digest",CreditContracts.hash(view));
        var windows=receipt.putArray("results");
        for(int h:List.of(1,3,7))windows.addObject().put("horizon_days",h).put("status","available")
                .put("currency","CNY").put("p10_minor",100*h).put("p50_minor",200*h)
                .put("p90_minor",300*h).put("shortage_probability",0.1);
        receipt.put("receipt_id",CreditContracts.hash(receipt));
        source.createContext("/v1/credit-tools/",e->{
            var body=CreditContracts.JSON.readTree(e.getRequestBody());toolCalls.incrementAndGet();
            var result=CreditContracts.JSON.createObjectNode().put("schema_version","credit-model-tools.v1")
                    .put("request_id",body.path("request_id").asText()).put("status","complete")
                    .put("snapshot_digest",CreditContracts.hash(view)).put("model_digest","b".repeat(64));
            result.set("result",e.getRequestURI().getPath().endsWith("forecast")?receipt:view);
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(result);e.sendResponseHeaders(200,bytes.length);
            e.getResponseBody().write(bytes);e.close();
        });
        model.createContext("/",e->{
            e.getRequestBody().readAllBytes();int i=modelCalls.incrementAndGet();
            Object message=i<3?Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-"+i,
                    "type","function","function",Map.of("name",i==1?"credit_snapshot":"credit_forecast","arguments","{}")))):
                    Map.of("role","assistant","content","{\"status\":\"complete\",\"questions\":[]}");
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(Map.of("id","probe-"+i,"object","chat.completion",
                    "created",1,"model","deepseek-flash","choices",List.of(Map.of("index",0,
                    "message",message,"finish_reason",i<3?"tool_calls":"stop"))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);
            e.getResponseBody().write(bytes);e.close();
        });
        source.start();model.start();
        try {
            var agent=new LimitRiskAgent(new CreditModelTools(URI.create("http://127.0.0.1:"+source.getAddress().getPort()),"synthetic-tools"),
                    "http://127.0.0.1:"+model.getAddress().getPort()+"/v1","synthetic-model","deepseek-flash","b".repeat(64));
            var result=agent.run(turn,List.of(),()->true);
            assertEquals("complete",result.path("status").asText());
            assertEquals(view,result.get("snapshot"));assertEquals(receipt,result.get("forecast"));
            assertEquals(2,toolCalls.get());assertEquals(3,modelCalls.get());
        }finally{model.stop(0);source.stop(0);}
    }
    @Test void cancellationDuringRealHttpToolPreventsTheNextModelCallAndLateCommit() throws Exception {
        var modelCalls=new AtomicInteger();var sourceCalls=new AtomicInteger();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        var view=CreditModelToolsTest.snapshot();var turn=CreditModelToolsTest.turn(view);
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/v1/credit-tools/snapshot",e->{
            var body=CreditContracts.JSON.readTree(e.getRequestBody());
            if(sourceCalls.incrementAndGet()==2){entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
            var output=CreditContracts.JSON.createObjectNode().put("schema_version","credit-model-tools.v1")
                    .put("request_id",body.path("request_id").asText()).put("status","complete").put("snapshot_digest",CreditContracts.hash(view));output.set("result",view);
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(output);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        model.createContext("/",e->{
            e.getRequestBody().readAllBytes();modelCalls.incrementAndGet();
            var message=Map.of("role","assistant","tool_calls",List.of(Map.of("id","snapshot-call","type","function",
                    "function",Map.of("name","credit_snapshot","arguments","{}"))));
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(Map.of("id","probe","object","chat.completion","created",1,"model","deepseek-flash",
                "choices",List.of(Map.of("index",0,"finish_reason","tool_calls","message",message))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });source.start();model.start();
        var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        try(var store=new CreditStateStore(stateDirectory)){
            var tools=new CreditModelTools(URI.create("http://127.0.0.1:"+source.getAddress().getPort()),"synthetic-tools");
            var agent=new LimitRiskAgent(tools,"http://127.0.0.1:"+model.getAddress().getPort()+"/v1","synthetic-model","deepseek-flash","b".repeat(64));
            try(var service=new CreditService(store,tools,agent,t->true)){
                var result=executor.submit(()->service.query(turn));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("cancelled",service.cancel(turn).path("status").asText());release.countDown();
                assertEquals("cancelled",result.get(5,java.util.concurrent.TimeUnit.SECONDS).path("status").asText());
                assertEquals(1,modelCalls.get());assertEquals(2,sourceCalls.get());
                assertFalse(store.read(turn).path("result").has("forecast"));
            }
        }finally{release.countDown();executor.shutdownNow();source.stop(0);model.stop(0);}
    }
    @Test void aModelCannotDeclareCompleteWithoutToolEvidence() throws Exception {
        var calls=new AtomicInteger();
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",e->{
            e.getRequestBody().readAllBytes();calls.incrementAndGet();
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(Map.of("id","probe","object","chat.completion",
                    "created",1,"model","deepseek-flash","choices",List.of(Map.of("index",0,
                    "message",Map.of("role","assistant","content","{\"status\":\"complete\",\"questions\":[]}"),
                    "finish_reason","stop"))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);
            e.getResponseBody().write(bytes);e.close();
        });
        model.start();
        try {
            var tools=new CreditModelTools(URI.create("http://127.0.0.1:1"),"synthetic-tools");
            var agent=new LimitRiskAgent(tools,"http://127.0.0.1:"+model.getAddress().getPort()+"/v1",
                    "synthetic-model","deepseek-flash","b".repeat(64));
            var failure=assertThrows(IllegalStateException.class,()->agent.run(
                    CreditStateStoreTest.turn("session-1","未来7天是否有额度风险？"),List.of(),()->true));
            assertEquals("MISSING_REQUIRED_TOOL_EVIDENCE",failure.getMessage());
            assertEquals(1,calls.get());
        }finally{model.stop(0);}
    }
}
