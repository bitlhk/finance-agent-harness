package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.workflow.*;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.context.ModelContext;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCreditAgentsTest {
    @Test void reactActuallyInvokesConfiguredModel() throws Exception {
        var calls = new AtomicInteger();
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", e -> {
            var request = new ObjectMapper().readTree(e.getRequestBody());
            assertEquals("deepseek-flash", request.path("model").asText());
            calls.incrementAndGet();
            String result = "{\"id\":\"probe\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"native-credit-probe\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}";
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().set("Content-Type", "application/json");
            e.sendResponseHeaders(200, bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        http.start();
        String session = "react-probe-"+UUID.randomUUID();
        try {
            var agent=NativeCreditAgents.limitAgent("credit-"+UUID.randomUUID(),
                    "http://127.0.0.1:"+http.getAddress().getPort()+"/v1", "synthetic-probe",
                    "deepseek-flash", "Respond only with the supplied probe result.");
            assertTrue(Runner.start());
            Object result=Runner.runAgent(agent,Map.of("query","probe"),session,null);
            assertNotNull(result);
            assertTrue(result.toString().contains("native-credit-probe"),result.toString());
            assertEquals(1,calls.get());
            String blockedId="credit-"+UUID.randomUUID();
            var blocked=NativeCreditAgents.limitAgent(blockedId,
                    "http://127.0.0.1:"+http.getAddress().getPort()+"/v1", "synthetic-probe",
                    "deepseek-flash", "probe", ()->{throw new java.util.concurrent.CancellationException();});
            try {
                try { Runner.runAgent(blocked,Map.of("query","probe"),session+"-blocked",null); }
                catch (Exception expected) { /* Native SDK can propagate or envelope the failure. */ }
                assertEquals(1,calls.get(),"Revoked guard must prevent model HTTP requests");
            } finally { NativeCreditAgents.releaseModel(blockedId); Runner.release(session+"-blocked"); }
        } finally {Runner.release(session);http.stop(0);}
    }

    @Test void workflowAgentActuallyExecutesNativeNodes() throws Exception {
        var calls = new ArrayList<String>();
        var workflow = new Workflow(new WorkflowCard("wf-"+UUID.randomUUID(),"0.1.0"));
        workflow.setStartComp("authorize", new WorkflowComponent() {
            @Override public Object invoke(Object input, NodeSessionApi session, ModelContext context) {
                calls.add("authorize");return Map.of("authorized",true);
            }
        },Map.of());
        workflow.setEndComp("verify", new WorkflowComponent() {
            @Override public Object invoke(Object input, NodeSessionApi session, ModelContext context) {
                calls.add("verify");return Map.of("result","native-workflow-probe");
            }
        },Map.of());
        workflow.addConnection("authorize","verify");
        var agent=NativeCreditAgents.benefitsAgent("benefits-"+UUID.randomUUID(),workflow);
        String session="workflow-probe-"+UUID.randomUUID();
        try {
            assertTrue(Runner.start());
            Object output=Runner.runAgent(agent,Map.of("query","probe"),session,null);
            assertEquals(List.of("authorize","verify"),calls);
            var result=(com.openjiuwen.core.controller.schema.ControllerOutput)output;
            String serialized=new ObjectMapper().writeValueAsString(result.getData());
            assertTrue(serialized.contains("native-workflow-probe"),serialized);
        } finally {Runner.release(session);}
    }
}
