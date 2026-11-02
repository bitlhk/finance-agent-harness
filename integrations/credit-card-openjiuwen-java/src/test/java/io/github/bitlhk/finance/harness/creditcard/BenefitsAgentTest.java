package io.github.bitlhk.finance.harness.creditcard;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenefitsAgentTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void nativeWorkflowPersistsExclusionAcrossTurnsAndIsolatesNewConversation() throws Exception {
        var modelCalls=new AtomicInteger();var view=BenefitsValidatorTest.view();
        var model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/v1/credit-tools/snapshot",e->{
            var request=CreditContracts.JSON.readTree(e.getRequestBody());
            var output=CreditContracts.JSON.createObjectNode().put("schema_version","credit-model-tools.v1")
                    .put("request_id",request.path("request_id").asText()).put("status","complete")
                    .put("snapshot_digest",CreditContracts.hash(view));output.set("result",view);
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(output);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        model.createContext("/",e->{
            var request=CreditContracts.JSON.readTree(e.getRequestBody());modelCalls.incrementAndGet();
            var messages=request.path("messages");var input=CreditContracts.JSON.readTree(messages.get(messages.size()-1).path("content").asText());
            ObjectNode answer;
            if(input.has("current_request")){
                answer=BenefitsDialogueTest.decision();String question=input.path("current_request").asText();
                if(question.equals("不要沙拉"))answer.withArray("changes").addObject().put("benefit_id","benefit-1").put("action","exclude").put("quote",question);
            }else{
                answer=CreditContracts.JSON.createObjectNode();var items=answer.putArray("items");
                for(var candidate:input.path("candidates"))items.addObject().put("benefit_id",candidate.path("benefit").path("benefit_id").asText()).put("reason","合成目标匹配");
            }
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(Map.of("id","probe","object","chat.completion","created",1,"model","deepseek-flash",
                    "choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",CreditContracts.JSON.writeValueAsString(answer))))));
            e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });source.start();model.start();
        try(var store=new CreditStateStore(directory)){
            var tools=new CreditModelTools(URI.create("http://127.0.0.1:"+source.getAddress().getPort()),"synthetic-tools");
            var agent=new BenefitsAgent(tools,"http://127.0.0.1:"+model.getAddress().getPort()+"/v1","synthetic-key","deepseek-flash",null,null);
            try(var service=new CreditService(store,tools,null,agent,t->true)){
                var first=service.query(turn(view,"op-first","session-benefits","推荐权益"));
                assertEquals("complete",first.path("status").asText(),first.toString());
                assertEquals(2,first.path("result").path("results").path("llm").path("items").size());
                var second=service.query(turn(view,"op-second","session-benefits","不要沙拉"));
                assertEquals("complete",second.path("status").asText(),second.toString());
                assertEquals(1,second.path("result").path("results").path("llm").path("items").size());
                assertEquals("benefit-2",second.path("result").path("results").path("llm").path("items").get(0).path("benefit").path("benefit_id").asText());
                var third=service.query(turn(view,"op-third","session-benefits","再推荐一次"));
                assertEquals(1,third.path("result").path("results").path("llm").path("items").size());
                var other=service.query(turn(view,"op-new","session-independent","推荐权益"));
                assertEquals(2,other.path("result").path("results").path("llm").path("items").size());
                int calls=modelCalls.get();service.query(turn(view,"op-new","session-independent","推荐权益"));assertEquals(calls,modelCalls.get());
                assertEquals(8,calls);
                var nodes=new HashSet<String>();for(var event:second.path("execution"))if("workflow-node".equals(event.path("kind").asText()))nodes.add(event.path("name").asText());
                assertEquals(Set.of("authorize","understand","rank","verify"),nodes);
            }
        }finally{model.stop(0);source.stop(0);}
    }
    static CreditContracts.Turn turn(ObjectNode view,String operation,String session,String question) throws Exception {
        var value=CreditModelToolsTest.turn(view).binding().value();
        value.set("visible_source_ids",view.path("case").path("visible_sources").deepCopy());
        value.put("observed_at",view.path("case").path("observed_at").asText());
        var binding=new CreditContracts.Binding(value);
        return new CreditContracts.Turn(operation,session,"benefits",question,binding,Instant.now().plusSeconds(20));
    }
}
