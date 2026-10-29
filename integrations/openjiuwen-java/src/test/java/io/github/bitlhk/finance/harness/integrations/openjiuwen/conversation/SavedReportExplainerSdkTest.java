package io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class SavedReportExplainerSdkTest {
    private static ObjectNode report() {
        var value=HarnessJson.mapper().createObjectNode();
        value.putObject("binding").put("reportRef","synthetic-report-v3").put("revision",3);
        value.putObject("facts").put("discussionAmount",1000000).put("liquidityReserve",300000)
                .put("discussableBalance",700000).put("calculation","1000000-300000=700000")
                .put("basis","客户口述未独立核验，余额仅为确定性计算");
        value.putObject("state").put("execution","COMPLETED").put("validation","PASSED").put("review","PENDING");
        return value;
    }

    private static ObjectNode answer() {
        var value=HarnessJson.mapper().createObjectNode().put("schema","finance-saved-report-answer.v1")
                .put("answer","旧报告第3版不会自动修改；当前保留30万元、余额70万元，依据仍是未核验口述。");
        value.putArray("refs").add("/binding/reportRef").add("/binding/revision")
                .add("/facts/liquidityReserve").add("/facts/discussableBalance")
                .add("/facts/calculation").add("/facts/basis").add("/state/execution")
                .add("/state/validation").add("/state/review");
        return value;
    }

    @Test void nineRealLeavesAreStillRejectedRatherThanSilentlyTruncated() {
        var value=answer();
        for(var ref:value.path("refs"))assertTrue(report().at(ref.asText()).isValueNode());
        var error=assertThrows(IllegalArgumentException.class,()->SavedReportExplainer.validate(value.toString(),report()));
        assertEquals("CONVERSATION_ANSWER_INVALID",error.getMessage());
        assertEquals(9,value.path("refs").size());
    }

    @Test void eightLeavesPassButMissingDuplicateAndContainerReferencesFail() {
        var value=answer();((com.fasterxml.jackson.databind.node.ArrayNode)value.get("refs")).remove(8);
        var projection=report();var original=projection.deepCopy();
        assertEquals(value,SavedReportExplainer.validate(value.toString(),projection));
        for(String ref:List.of("/missing","/facts","/binding/reportRef","not-a-pointer")) {
            var invalid=value.deepCopy();
            ((com.fasterxml.jackson.databind.node.ArrayNode)invalid.get("refs")).set(7,HarnessJson.mapper().valueToTree(ref));
            assertThrows(IllegalArgumentException.class,()->SavedReportExplainer.validate(invalid.toString(),projection));
        }
        value.putArray("refs");
        assertThrows(IllegalArgumentException.class,()->SavedReportExplainer.validate(value.toString(),projection));
        assertEquals(original,projection);
    }

    @Test void answerLengthAndExactSchemaStayEnforced() {
        var value=answer();value.putArray("refs").add("/binding/revision");
        value.put("answer","x".repeat(4000));
        assertDoesNotThrow(()->SavedReportExplainer.validate(value.toString(),report()));
        value.put("answer","x".repeat(4001));
        assertThrows(IllegalArgumentException.class,()->SavedReportExplainer.validate(value.toString(),report()));
        value.put("answer","旧版本保持不变。").put("newGrant",true);
        assertThrows(IllegalArgumentException.class,()->SavedReportExplainer.validate(value.toString(),report()));
    }

    @Test @Timeout(30) void sdkReceivesTheSameBoundsAsTheOutputValidator() throws Exception {
        var requests=new CopyOnWriteArrayList<JsonNode>();
        var fixture=answer();fixture.putArray("refs").add("/binding/revision").add("/facts/basis");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.add(HarnessJson.mapper().readTree(exchange.getRequestBody()));
            var chunk=HarnessJson.mapper().writeValueAsString(Map.of("choices",List.of(Map.of("index",0,
                    "delta",Map.of("content",fixture.toString())))));
            var bytes=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var explainer=new SavedReportExplainer(new ManagedScenarioExtensionV01.ModelConnection("OpenAI",
                    "synthetic-only","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic"));
            var projection=report();var original=projection.deepCopy();
            assertEquals(fixture,explainer.explain("保留40万会修改旧报告吗？不要重新执行。",projection,()->false));
            assertEquals(original,projection);assertEquals(1,requests.size());
            var request=requests.getFirst();
            assertTrue(request.path("tools").isMissingNode()||request.path("tools").isEmpty());
            var policy=request.path("messages").get(0).path("content").asText();
            assertTrue(policy.contains("answer最多4000字符"));
            assertTrue(policy.contains("refs必须包含1到8条互不重复的引用"));
            assertTrue(policy.contains("不必逐项引用全部事实或状态"));
            assertFalse(request.path("messages").toString().contains("synthetic-only"));
        } finally {server.stop(0);}
    }
}
