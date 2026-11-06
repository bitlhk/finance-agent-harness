package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK with controlled model and protocol fixture source; full original source is a separate gate. */
class TaskInteractionNativeTest {
    @TempDir Path directory;
    @Test void installedMethodRunsRealRuntimeWithOnlyReadTool() throws Exception {
        var json=TaskInteractionSessionTest.JSON;var requests=new AtomicInteger();
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{
            var input=json.readTree(exchange.getRequestBody());int count=requests.incrementAndGet();
            var response=json.createObjectNode();response.put("id","controlled_"+count).put("object","chat.completion").put("created",1).put("model","controlled-task-model");
            var choice=response.putArray("choices").addObject();choice.put("index",0);var message=choice.putObject("message");message.put("role","assistant");
            if(count==1){
                assertEquals(1,input.path("tools").size());String tool=input.at("/tools/0/function/name").asText();assertTrue(tool.startsWith("custody_task_snapshot_"));
                var call=message.putArray("tool_calls").addObject();call.put("id","call_read").put("type","function");call.putObject("function").put("name",tool).put("arguments","{}");choice.put("finish_reason","tool_calls");
            }else{message.put("content","原指令等待认领；尚未复核或执行。");choice.put("finish_reason","stop");}
            response.putObject("usage").put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15);
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}exchange.close();
        });model.start();
        try(var fixture=new TaskInteractionSessionTest.Fixture(directory,new ManagedScenarioExtensionV01.ModelConnection("OpenAI","controlled-placeholder","http://127.0.0.1:"+model.getAddress().getPort()+"/v1","controlled-task-model"));var session=fixture.open()){
            var query=json.createObjectNode().put("query","现在这条指令进展如何？");var result=session.call("query",query);
            assertEquals("COMPLETED",result.get("status"));assertEquals(2,requests.get());assertEquals(0,fixture.writes.get());
            assertEquals(1,json.valueToTree(result).at("/metrics/toolCalls").asInt());assertEquals("openjiuwen-java@0.1.15",result.get("runtime"));
            assertEquals(20,json.valueToTree(result).at("/metrics/inputTokens").asInt());assertEquals(10,json.valueToTree(result).at("/metrics/outputTokens").asInt());
        }finally{model.stop(0);}
    }
}
