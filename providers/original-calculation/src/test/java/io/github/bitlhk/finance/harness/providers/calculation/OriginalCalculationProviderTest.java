package io.github.bitlhk.finance.harness.providers.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.calculation.CalculationResultMapper;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OriginalCalculationProviderTest {
    static final String HASH="a".repeat(64),REV="b".repeat(40);
    static final Instant NOW=Instant.parse("2026-11-04T12:00:00Z");
    HttpServer server;
    AtomicReference<ManagedProviderFactoryV01.SourceSession> authority=new AtomicReference<>();
    AtomicInteger requests=new AtomicInteger(),status=new AtomicInteger(200);
    AtomicBoolean running=new AtomicBoolean(false),cancelled=new AtomicBoolean(false),revoke=new AtomicBoolean(false);
    List<ManagedProviderFactoryV01.HttpAttempt> attempts=new CopyOnWriteArrayList<>();
    @BeforeEach void start() throws Exception{
        authority.set(new ManagedProviderFactoryV01.SourceSession("private-fixture-token","customer","owner","session",NOW.plusSeconds(60)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/calculations/",exchange->{
            requests.incrementAndGet();String action=exchange.getRequestURI().getPath().substring("/v1/calculations/".length());
            JsonNode request=HarnessJson.mapper().readTree(exchange.getRequestBody());
            assertEquals("Bearer private-fixture-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("owner",request.path("context").path("subjectRef").asText());
            assertEquals("customer",request.path("context").path("customerRef").asText());
            String run=request.path("runId").asText();JsonNode result;
            if(action.equals("input"))result=input();
            else if(action.equals("result"))result=result(run);
            else {
                if(action.equals("cancel"))cancelled.set(true);
                ObjectNode state=HarnessJson.mapper().createObjectNode().put("runId",run).put("status",cancelled.get()?"CANCELLED":running.get()?"RUNNING":"SUCCEEDED")
                    .put("workerStopped",cancelled.get()||!running.get()).put("inputDigest",HASH).put("parameterDigest",HASH);
                result=state;
            }
            if(revoke.get() && action.equals("start"))authority.set(null);
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(result);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            try{exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    JsonNode input(){
        return HarnessJson.mapper().valueToTree(Map.of("schemaVersion","finance-original-calculation-request.v1","assetId","ftp","operationId","ftp", "dataAsOf","2024-01-31",
            "input",Map.of("frozen","fixture"),"parameters",Map.of("action","price")));
    }
    OriginalCalculationFactoryV01.Binding binding(){return new OriginalCalculationFactoryV01.Binding("owner","customer","session","task","ftp","ftp",REV,
        "community/finance/scenario/bank-ftp-transfer-pricing",HASH,HASH,HASH,input());}
    OriginalCalculationProvider provider(){
        var connection=new ManagedProviderFactoryV01.Connection(OriginalCalculationFactoryV01.KIND,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
            Duration.ofSeconds(2),authority::get,()->null,attempts::add);
        return new OriginalCalculationProvider(connection,new OriginalCalculationFactoryV01.Config("original-calculation-bindings.v1",List.of(binding())),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    CapabilityRequestV1 request(Map<String,Object> params){
        var c=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request","trace","owner",FinanceContextV1.PrincipalType.EMPLOYEE,"fixture","CALCULATION","customer","session",FinanceContextV1.AuthenticationLevel.STRONG,Set.of(OriginalCalculationProvider.SCOPE));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,OriginalCalculationProvider.capability("ftp"),CapabilityRequestV1.Operation.QUERY,c,params,null,null,Map.of(),null);
    }
    JsonNode result(String run){
        ObjectNode raw=HarnessJson.mapper().createObjectNode().put("kind","float").put("value","-0.0").put("hex","-0x0.0p+0");
        var result=HarnessJson.mapper().createObjectNode().put("schemaVersion","finance-original-calculation-result.v1").put("runId",run).put("generation",1).put("status","SUCCEEDED")
            .put("assetId","ftp").put("operationId","ftp").put("inputDigest",HASH).put("parameterDigest",HASH).put("calculatedAt",NOW.toString()).putNull("dataAsOf").put("rawDigest",CalculationResultMapper.digest(raw));
        result.set("source",HarnessJson.mapper().valueToTree(Map.of("repository","https://gitcode.com/openJiuwen/agent-store","commit",REV,"entity",binding().sourceEntity(),"filesDigest",HASH)));
        result.set("tool",HarnessJson.mapper().valueToTree(Map.of("version","0.1.0","adapterDigest",HASH,"lockDigest",HASH)));
        result.set("rawValue",raw);result.set("projection",HarnessJson.mapper().valueToTree(Map.of("metrics",List.of(),"tables",List.of(),"series",List.of(),"warnings",List.of())));
        result.set("timings",HarnessJson.mapper().valueToTree(Map.of("elapsedMs",1.,"calls",1)));
        return result;
    }
    @Test void fullHttpProtocolPreservesNegativeZeroAndMissingSourceDate(){
        var p=provider();var result=p.query(request(Map.of()));
        assertEquals(CapabilityResultV1.Status.COMPLETED,result.status());
        var data=HarnessJson.mapper().valueToTree(result.data());
        assertEquals("-0.0",data.path("calculation").path("rawValue").path("value").asText());
        assertEquals(List.of("SOURCE_AS_OF_MISSING"),result.missingInputs());
        assertEquals(3,attempts.size()); // start already SUCCEEDED, then input and result.
    }
    @Test void currentAuthorityAndIdentityCannotBeChosenByModel(){
        try(var p=provider()){
            for(String field:List.of("taskRef","input","assetId","module","factor_code")){
                assertEquals(CapabilityResultV1.Status.FAILED,p.query(request(Map.of(field,"forged"))).status());
            }
            authority.set(null);assertEquals("ACCESS_DENIED",p.query(request(Map.of())).error().sourceCode());
            assertEquals(0,requests.get());
        }
    }
    @Test void revocationDuringStartClosesResultAndRequestsCleanup(){
        revoke.set(true);
        try(var p=provider()){
            var result=p.query(request(Map.of()));
            assertEquals(CapabilityResultV1.Status.FAILED,result.status());assertTrue(result.data().isEmpty());
            assertTrue(cancelled.get());
        }
    }
    @Test void realCancellationRequestIsSeparateFromHttpFutureCancellation() throws Exception{
        running.set(true);
        try(var p=provider()){
            var executor=Executors.newSingleThreadExecutor();
            try{
                var task=executor.submit(()->p.query(request(Map.of())));
                long deadline=System.nanoTime()+Duration.ofSeconds(1).toNanos();
                while(requests.get()==0 && System.nanoTime()<deadline)Thread.sleep(5);
                assertTrue(p.cancelActive());
                var result=task.get(3,TimeUnit.SECONDS);
                assertEquals(CapabilityResultV1.Status.FAILED,result.status());assertTrue(cancelled.get());assertTrue(result.data().isEmpty());
            }finally{executor.shutdownNow();}
        }
    }
    @Test void sourceHttpFailureIsNotACompletedCalculation(){
        status.set(503);
        try(var p=provider()){
            var result=p.query(request(Map.of()));assertEquals(CapabilityResultV1.Status.FAILED,result.status());assertTrue(result.data().isEmpty());
            assertFalse(result.toString().contains("private-fixture-token"));
        }
    }
    @Test void cancellationClosesLateToolCallsBeforeTheyCanStart(){
        try(var p=provider()){
            assertTrue(p.cancelAndClose());
            assertEquals(CapabilityResultV1.Status.FAILED,p.query(request(Map.of())).status());
            assertEquals(0,requests.get());
        }
    }
    @Test void historicalResultReadsRecheckCurrentSourceAuthority(){
        try(var p=provider()){
            p.checkStoredStatus("a".repeat(32));
            status.set(403);
            assertThrows(SecurityException.class,()->p.checkStoredStatus("a".repeat(32)));
            authority.set(null);
            assertThrows(SecurityException.class,()->p.checkStoredStatus("a".repeat(32)));
        }
    }
    @Test void exactPrivateConfigurationIsFrozenAndDiscoverable(@org.junit.jupiter.api.io.TempDir Path root) throws Exception{
        assertTrue(ServiceLoader.load(ManagedProviderFactoryV01.class).stream().anyMatch(x->x.type()==OriginalCalculationFactoryV01.class));
        var bytes=HarnessJson.mapper().writeValueAsBytes(new OriginalCalculationFactoryV01.Config("original-calculation-bindings.v1",List.of(binding())));
        Path file=root.resolve("bindings.json");Files.write(file,bytes);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var factory=new OriginalCalculationFactoryV01(file.toString(),digest);
        assertEquals(Map.of("binding-file-sha256",digest),factory.configurationDigests());
        Files.writeString(file,"{}");assertEquals(Map.of("binding-file-sha256",digest),factory.configurationDigests());
        assertThrows(IllegalArgumentException.class,()->new OriginalCalculationFactoryV01(file.toString(),HASH).configurationDigests());
    }
    @Test void asynchronousRequestTimeoutRetainsDeadlineReason(){
        var pending=CompletableFuture.failedFuture(new java.net.http.HttpTimeoutException("request timed out"));
        assertThrows(TimeoutException.class,()->OriginalCalculationProvider.awaitResponse(pending,TimeUnit.SECONDS.toNanos(1)));
    }
    @Test void asynchronousConnectionTimeoutRetainsDeadlineReason(){
        var pending=CompletableFuture.failedFuture(new java.net.http.HttpConnectTimeoutException("connect timed out"));
        assertThrows(TimeoutException.class,()->OriginalCalculationProvider.awaitResponse(pending,TimeUnit.SECONDS.toNanos(1)));
    }
    @Test void unrelatedIoFailureDoesNotBecomeTimeout(){
        var cause=new java.io.IOException("connection reset");var pending=CompletableFuture.failedFuture(cause);
        var failure=assertThrows(ExecutionException.class,()->OriginalCalculationProvider.awaitResponse(pending,TimeUnit.SECONDS.toNanos(1)));
        assertSame(cause,failure.getCause());
    }
}
