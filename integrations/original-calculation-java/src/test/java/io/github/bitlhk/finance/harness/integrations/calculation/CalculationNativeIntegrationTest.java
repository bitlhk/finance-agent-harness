package io.github.bitlhk.finance.harness.integrations.calculation;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.providers.calculation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real SDK installation/model/tool transport boundary; source fault injection is not algorithm qualification. */
class CalculationNativeIntegrationTest {
    static final ObjectMapper JSON=HarnessJson.mapper();
    @TempDir Path temp;
    @Test @Timeout(60) void actualInstalledToolFailureCannotBecomeAgentSuccess() throws Exception{
        verifySourceFailure("HTTP_FAILURE");
    }
    @Test @Timeout(60) void actualSourceTimeoutRemainsTimeoutThroughNativeSession() throws Exception{
        verifySourceFailure("SOURCE_TIMEOUT");
    }
    @Test @Timeout(60) void providerDeadlineRemainsTimeoutAndRequestsWorkerCleanup() throws Exception{
        verifySourceFailure("PROVIDER_DEADLINE");
    }
    private void verifySourceFailure(String mode) throws Exception{
        var requests=new CopyOnWriteArrayList<String>();var sourceCalls=new AtomicInteger();
        var cleanupCalls=new AtomicInteger();
        HttpServer source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.createContext("/",exchange->{
            sourceCalls.incrementAndGet();JsonNode input=JSON.readTree(exchange.getRequestBody().readAllBytes());
            boolean cancel=exchange.getRequestURI().getPath().endsWith("/cancel");if(cancel)cleanupCalls.incrementAndGet();
            String state=mode.equals("SOURCE_TIMEOUT")||cancel?"TIMED_OUT":"RUNNING";
            byte[] bytes=JSON.writeValueAsBytes(Map.of("runId",input.path("runId").asText(),"status",state,"workerStopped",cancel||mode.equals("SOURCE_TIMEOUT")));
            if(mode.equals("HTTP_FAILURE"))bytes="{\"errorCode\":\"CONTROLLED_SOURCE_FAILURE\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(mode.equals("HTTP_FAILURE")?503:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});source.start();
        HttpServer model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        model.createContext("/",exchange->{
            String request=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requests.add(request);
            boolean returned=false;for(JsonNode m:JSON.readTree(request).path("messages"))if("tool".equals(m.path("role").asText()))returned=true;
            Map<String,Object> delta=returned?Map.of("role","assistant","content","本次工具返回失败，没有可披露结果。"):
                Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","finite-call","type","function","function",Map.of("name","calculation_ftp_query","arguments","{}"))));
            String text="data: "+JSON.writeValueAsString(Map.of("id","fixed","model","controlled","choices",List.of(Map.of("index",0,"delta",delta))))+"\n\n"+
                "data: "+JSON.writeValueAsString(Map.of("id","fixed","model","controlled","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",returned?"stop":"tool_calls"))))+"\n\ndata: [DONE]\n\n";
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });model.start();
        try{
            Path artifact=artifact(),state=temp.resolve("private");Files.createDirectories(state);
            JsonNode input=JSON.valueToTree(Map.of("schemaVersion","finance-original-calculation-request.v1","assetId","ftp","operationId","ftp","dataAsOf","2024-01-31","input",Map.of("frozen","fixture"),"parameters",Map.of("action","price")));
            var binding=new OriginalCalculationFactoryV01.Binding("owner","customer","session","task","ftp","ftp","b".repeat(40),
                "community/finance/scenario/bank-ftp-transfer-pricing","a".repeat(64),"a".repeat(64),"a".repeat(64),input);
            byte[] config=JSON.writeValueAsBytes(new OriginalCalculationFactoryV01.Config("original-calculation-bindings.v1",List.of(binding)));
            Path file=state.resolve("bindings.json");Files.write(file,config);
            var lease=new CalculationSourceScope.Lease("owner","customer","task","session","private-fixture-token",Instant.now().plusSeconds(300),Set.of(OriginalCalculationProvider.SCOPE));
            var authority=new CalculationSourceScope.Authority("original-calculation-host-authority.v1","owner","http://127.0.0.1:"+source.getAddress().getPort(),mode.equals("PROVIDER_DEADLINE")?100:3000,file.toString(),hash(config),List.of(lease));
            Files.write(state.resolve("calculation-authority.json"),JSON.writeValueAsBytes(authority));
            var context=new ManagedScenarioExtensionV01.Context(artifact,state,"native-unit","instance","owner",
                new ManagedScenarioExtensionV01.ModelConnection("OpenAI","controlled-key","http://127.0.0.1:"+model.getAddress().getPort()+"/v1","controlled"));
            try(var session=new CalculationSession(context)){
                assertEquals("READY_NOT_EXECUTED",session.bootstrap().get("status"));
                var result=session.call("calculate",JSON.readTree("{\"query\":\"请调用本次固定FTP计算。\"}"));
                assertEquals(mode.equals("HTTP_FAILURE")?"FAILED":"TIMED_OUT",result.get("status"),JSON.writeValueAsString(result));
                if(!mode.equals("HTTP_FAILURE")){
                    assertEquals("ORIGINAL_CALCULATION_TIMEOUT",result.get("errorCode"));
                    assertTrue(cleanupCalls.get()>0,"Provider must ask source to stop after a timeout");
                    assertEquals("TIMED_OUT",session.status().get("status"));
                    assertTrue(JSON.valueToTree(session.call("history",JSON.createObjectNode())).path("items").isEmpty());
                }
                assertEquals(1,((Number)result.get("toolCallCount")).intValue());
                assertEquals(2,requests.size());assertTrue(sourceCalls.get()>=1);
                assertTrue(requests.getFirst().contains("CALCULATION-PROCEDURE-MARKER"));
                assertThrows(IllegalStateException.class,()->session.call("result",JSON.createObjectNode()));
                assertFalse(JSON.writeValueAsString(result).contains("private-fixture-token"));
            }
        }finally{model.stop(0);source.stop(0);}
    }
    @Test void extensionIsDiscoverableWithoutAcquiringSourceAuthority(){
        assertTrue(ServiceLoader.load(ManagedScenarioExtensionV01.class).stream().anyMatch(x->x.type()==CalculationScenarioExtensionV01.class));
        assertEquals(Set.of("chat","calculate","result","cancel","status","history"),new CalculationScenarioExtensionV01().actions());
    }
    private Path artifact() throws Exception{
        Path root=temp.resolve("artifact");Files.createDirectories(root);
        Map<String,Object> plan=Map.of("runtime",Map.of("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15"),"profileRef",Map.of("id","original-calculation-readonly"),
            "capabilityPackRefs",List.of(Map.of("source","repo://cap/pack.json")),"domainPackRefs",List.of(Map.of("source","repo://domain/pack.json")));
        List<Map<String,Object>> capabilities=new ArrayList<>();
        for(String family:new TreeSet<>(OriginalCalculationProvider.FAMILIES))capabilities.add(Map.of("capabilityId",OriginalCalculationProvider.capability(family),"toolName",("calculation_"+family+"_query").replace('-','_'),
            "name","Original "+family,"description","Finite source-bound "+family,"operation","QUERY","inputSchema",Map.of("type","object","properties",Map.of(),"additionalProperties",false),"requiredScopes",List.of(OriginalCalculationProvider.SCOPE)));
        Map<String,byte[]> files=new TreeMap<>();files.put("component-plan.json",JSON.writeValueAsBytes(plan));
        files.put("assets/cap/pack.json",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-capability-pack.v1","packId","original-calculation-readonly","version","0.1.0","systemPrompt","Use installed original calculations only.","capabilities",capabilities)));
        files.put("assets/domain/pack.json",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-domain-pack.v1","packId","original-calculation","version","0.1.0","profile","original-calculation-readonly",
            "capabilities",new TreeSet<>(OriginalCalculationProvider.FAMILIES).stream().map(OriginalCalculationProvider::capability).toList(),"skills",List.of(Map.of("directory","original-calculation","entry","skills/original-calculation/SKILL.md")))));
        files.put("assets/domain/skills/original-calculation/SKILL.md","# Finite calculation\nCALCULATION-PROCEDURE-MARKER\nOnly installed tools provide numeric results.\n".getBytes(StandardCharsets.UTF_8));
        Map<String,String> hashes=new TreeMap<>();
        for(var item:files.entrySet()){Path file=root.resolve(item.getKey());Files.createDirectories(file.getParent());Files.write(file,item.getValue());hashes.put(item.getKey(),hash(item.getValue()));}
        Files.write(root.resolve("service-lock.json"),JSON.writeValueAsBytes(Map.of("files",hashes,"scenarioExtension",Map.of("id",CalculationScenarioExtensionV01.ID))));
        return root;
    }
    static String hash(byte[] data)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}
}
