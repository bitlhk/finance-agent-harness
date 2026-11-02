package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreditModelToolsTest {
    @Test void canonicalDigestMatchesPythonFloatReceiptVectors() throws Exception {
        assertEquals("c8b4301d31692cc55fc58ee2d4368e95e4971ac5d25036abceb45c33a05b0fcb",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1e-07}")));
        assertEquals("89a1d9e7ce676db7f66963901ee511773bb673fa95558bceb73c91bdcc28327b",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1e-06}")));
        assertEquals("dabf62dd3991a10b0387fe6421e7e7b839eb811e2dff9276ad71d75af293a3ff",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":0.0001}")));
        assertEquals("21d6bea42b17fd31195fc9fc560ab7d0494ba833cdd094be2836c21506e84464",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1e+16}")));
        assertEquals("bf32f56236899e13ef54db875d136c6cbcc65244464829c54911aa9069b0ae25",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1.0}")));
        assertEquals("529436936b90ebdff10ece80ead76834aeae76aa1e44d6c334ac3ca3832a8fba",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":-0.0}")));
        assertEquals("7014472282f3b3f4dc18ad3b8ef5a1c48e22c17db14f0a76ad8231fd29f98acf",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1.23e+23}")));
        assertEquals("0910c90722c64b28627c5d0f0045022e740272a189036c06f8a6f85331ab78fa",CreditContracts.hash(CreditContracts.JSON.readTree("{\"x\":1.2345678901234567e-06}")));
    }
    @Test void explicitFrozenPythonServiceReturnsTheExactExistingReceipt() throws Exception {
        String endpoint=System.getenv("CREDIT_MODEL_TOOLS_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(endpoint!=null,"explicit frozen tool service required");
        var fixture=CreditContracts.JSON.readTree(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(System.getenv("CREDIT_MODEL_TOOLS_FIXTURE"))));
        var turn=new CreditContracts.Turn("java-frozen-check","session-java-frozen","limit-risk","核对冻结预测",
                new CreditContracts.Binding(CreditContracts.object(fixture.path("binding"))),Instant.now().plusSeconds(30));
        var tools=new CreditModelTools(URI.create(endpoint),System.getenv("CREDIT_MODEL_TOOLS_TOKEN"));
        var result=tools.call("forecast",turn,"forecast-java-frozen",fixture.path("model_digest").asText());
        assertEquals(fixture.path("expected_forecast"),result.path("result"));
        assertEquals(fixture.path("snapshot"),tools.call("snapshot",turn,"snapshot-java-frozen",null).path("result"));
    }
    static ObjectNode snapshot() {
        var view=CreditContracts.JSON.createObjectNode();
        view.putObject("customer").put("customer_id","customer-1");
        view.putObject("case").put("case_id","case-1");
        return view;
    }
    static CreditContracts.Turn turn(ObjectNode view) throws Exception {
        var original=CreditStateStoreTest.turn("session-1","检查额度");
        var binding=original.binding().value();binding.put("snapshot_digest",CreditContracts.hash(view));
        return new CreditContracts.Turn(original.operationKey(),original.sessionRef(),original.scene(),
                original.question(),new CreditContracts.Binding(binding),Instant.now().plusSeconds(10));
    }
    @Test void actualHttpBindsRequestSnapshotAndRejectsRedirect() throws Exception {
        var calls=new AtomicInteger();var target=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var view=snapshot();var turn=turn(view);
        server.createContext("/v1/credit-tools/snapshot",e->{
            var body=CreditContracts.JSON.readTree(e.getRequestBody());
            assertEquals("Bearer synthetic-tools",e.getRequestHeaders().getFirst("Authorization"));
            assertEquals("session-1",body.path("session_ref").asText());
            int attempt=calls.incrementAndGet();
            if(attempt==3){e.getResponseHeaders().set("Location","/target");e.sendResponseHeaders(302,-1);e.close();return;}
            var result=CreditContracts.JSON.createObjectNode().put("schema_version","credit-model-tools.v1")
                    .put("request_id",body.path("request_id").asText()).put("status","complete")
                    .put("snapshot_digest",turn.binding().value().path("snapshot_digest").asText());
            ObjectNode payload=view.deepCopy();
            if(attempt==2)((ObjectNode)payload.path("customer")).put("customer_id","other-customer");
            result.set("result",payload);
            byte[] bytes=CreditContracts.JSON.writeValueAsBytes(result);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.createContext("/target",e->{target.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});
        server.start();
        try{
            var tools=new CreditModelTools(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"synthetic-tools");
            assertEquals(view,tools.call("snapshot",turn,"tool-1",null).get("result"));
            assertThrows(IllegalStateException.class,()->tools.call("snapshot",turn,"tool-2",null));
            assertThrows(IllegalStateException.class,()->tools.call("snapshot",turn,"tool-3",null));
            assertEquals(0,target.get());
            assertThrows(IllegalArgumentException.class,()->tools.call("query",turn,"tool-4",null));
            assertEquals(3,calls.get());
        }finally{server.stop(0);}
    }
    @Test void invalidBindingAndExpiredBudgetFailBeforeAnyIo() throws Exception {
        assertThrows(IllegalArgumentException.class,()->new CreditModelTools(URI.create("http://outside.invalid"),"token"));
        assertThrows(IllegalArgumentException.class,()->new CreditModelTools(URI.create("http://127.0.0.1/v1/query"),"token"));
        var original=turn(snapshot());
        var expired=new CreditContracts.Turn(original.operationKey(),original.sessionRef(),original.scene(),
                original.question(),original.binding(),Instant.parse("2000-01-01T00:00:00Z"));
        var tools=new CreditModelTools(URI.create("http://127.0.0.1:1"),"token");
        assertThrows(IllegalStateException.class,()->tools.call("snapshot",expired,"tool-1",null));
    }
}
