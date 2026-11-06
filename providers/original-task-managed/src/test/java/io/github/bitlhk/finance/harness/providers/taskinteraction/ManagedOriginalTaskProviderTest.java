package io.github.bitlhk.finance.harness.providers.taskinteraction;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedOriginalTaskProviderTest {
    static final ObjectMapper JSON=TaskInteractionMapper.json();
    static final String HASH="sha256:"+"1".repeat(64);
    static FinanceContextV1 context(){return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request_1","trace_1","actor",FinanceContextV1.PrincipalType.EMPLOYEE,"local","CUSTODY","customer","session_1",FinanceContextV1.AuthenticationLevel.STRONG,Set.of(ManagedOriginalTaskProvider.READ,ManagedOriginalTaskProvider.WRITE));}
    static CapabilityRequestV1 request(boolean confirmed,Map<String,Object> continuation){
        var body=Map.<String,Object>of("expectedVersion",HASH,"materialVersion",HASH,"action","claim","parameters",Map.of());
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,ManagedOriginalTaskProvider.ACTION,CapabilityRequestV1.Operation.TASK,context(),body,"request_key_1",continuation.isEmpty()?null:"task_1",continuation,
            confirmed?new CapabilityRequestV1.Confirmation(true,"Host confirmed",Instant.now()):null);
    }
    static ObjectNode snapshot(){
        var n=JSON.createObjectNode();n.put("schemaVersion","finance-task-snapshot.v1").put("taskRef","task_1").put("sourceObjectRef","instruction_1")
            .put("sourceCommit",TaskInteractionV1.SOURCE_COMMIT).put("sourceRelease",HASH).put("version",HASH).put("materialVersion",HASH)
            .put("originalState","pending").put("displayState","WAITING_INPUT");
        n.putArray("allowedActions").add("claim");n.putArray("inputRequired");n.putArray("unresolvedRequestIds");n.putObject("rawResult").put("id","instruction_1").put("status","pending");return n;
    }
    @Test void waitsForHostConfirmationAndRecoversLostResponseWithoutRepeatingPost() throws Exception {
        var posts=new AtomicInteger();var captured=new AtomicReference<JsonNode>();
        var receipt=JSON.createObjectNode();receipt.put("schemaVersion","finance-task-receipt.v1").put("requestId","request_key_1").put("taskRef","task_1")
            .put("action","claim").put("beforeVersion",HASH).put("materialVersion",HASH).put("status","SUCCEEDED").put("originalHttpStatus",200)
            .put("message","OK").put("afterVersion","sha256:"+"2".repeat(64)).put("actorRef","actor").put("recordedAt",Instant.now().toString());
        receipt.putObject("rawResult").put("id","instruction_1").put("status","pending_submit");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            byte[] body;int status=200;
            if(exchange.getRequestMethod().equals("POST")){posts.incrementAndGet();captured.set(JSON.readTree(exchange.getRequestBody()));status=503;body="{}".getBytes();}
            else if(exchange.getRequestURI().getPath().endsWith("/history")){
                var root=JSON.createObjectNode();var entry=root.putArray("entries").addObject();entry.putObject("input").put("requestId","request_key_1");entry.set("receipt",receipt);body=JSON.writeValueAsBytes(root);
            }else body=JSON.writeValueAsBytes(snapshot());
            exchange.sendResponseHeaders(status,body.length);try(var output=exchange.getResponseBody()){output.write(body);}exchange.close();
        });server.start();
        var sourceSession=new ManagedProviderFactoryV01.SourceSession("synthetic-server-credential","customer","actor","session_1",Instant.now().plusSeconds(300));
        var connection=new ManagedProviderFactoryV01.Connection(ManagedOriginalTaskFactoryV01.KIND,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),()->sourceSession,()->null,attempt->{});
        var config=new ManagedOriginalTaskFactoryV01.Config("original-task-bindings.v1",List.of(new ManagedOriginalTaskFactoryV01.Binding("actor","customer","session_1","task_1","instruction_1",HASH)));
        try(var provider=new ManagedOriginalTaskProvider(connection,config,Clock.systemUTC())){
            assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION,provider.executeTask(request(false,Map.of())).status());assertEquals(0,posts.get());
            assertEquals(CapabilityResultV1.Status.UNKNOWN,provider.executeTask(request(true,Map.of())).status());assertEquals(1,posts.get());
            assertEquals("request_key_1",captured.get().path("requestId").asText());
            assertEquals(CapabilityResultV1.Status.COMPLETED,provider.executeTask(request(false,Map.of("submitOutcomeUnknown",true))).status());assertEquals(1,posts.get());
        }finally{server.stop(0);}
    }
    @Test void refusesUnboundIdentityAndPublicEndpoint(){
        assertThrows(SecurityException.class,()->new ManagedOriginalTaskFactoryV01.Config("original-task-bindings.v1",List.of(new ManagedOriginalTaskFactoryV01.Binding("actor","customer","session_1","task_1","instruction_1",HASH))).binding("other","customer","session_1"));
        var factory=new ManagedOriginalTaskFactoryV01("missing","bad");assertThrows(IllegalArgumentException.class,factory::config);
        var connection=new ManagedProviderFactoryV01.Connection(ManagedOriginalTaskFactoryV01.KIND,URI.create("https://example.com"),Duration.ofSeconds(3),()->null,()->null,attempt->{});
        var config=new ManagedOriginalTaskFactoryV01.Config("original-task-bindings.v1",List.of(new ManagedOriginalTaskFactoryV01.Binding("actor","customer","session_1","task_1","instruction_1",HASH)));
        assertThrows(IllegalArgumentException.class,()->new ManagedOriginalTaskProvider(connection,config,Clock.systemUTC()));
    }
}
