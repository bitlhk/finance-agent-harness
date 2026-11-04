package io.github.bitlhk.finance.harness.providers.material;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.material.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.material.MaterialExtractionV1.*;

class FinancialMaterialProviderTest {
    static final String HASH="sha256:"+"a".repeat(64);
    HttpServer server;AtomicInteger calls=new AtomicInteger();AtomicInteger http=new AtomicInteger(200);
    AtomicReference<ManagedProviderFactoryV01.SourceSession> current=new AtomicReference<>();
    AtomicReference<String> task=new AtomicReference<>("task");AtomicReference<Status> status=new AtomicReference<>(Status.COMPLETED);
    Runnable received=()->{};
    @BeforeEach void start() throws Exception {
        current.set(new ManagedProviderFactoryV01.SourceSession("private-fixture-credential","customer","reviewer","session",Instant.now().plusSeconds(300)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/material/current",x->{
            var doc=new Document("financial-material-document.v1","doc-1",1,HASH,"application/pdf",100,1,"owner","reviewer","customer","task","session");
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(Map.of("document",doc,"selectionRevision",1));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
        });
        server.createContext("/v1/material/extract",x->{
            calls.incrementAndGet();var input=HarnessJson.mapper().readTree(x.getRequestBody().readAllBytes());
            assertEquals("doc-1",input.path("documentRef").asText());assertFalse(input.has("principalId"));received.run();
            var doc=new Document("financial-material-document.v1","doc-1",1,HASH,"application/pdf",100,1,"owner","reviewer","customer",task.get(),"session");
            var raw=new Result("financial-material-result.v1","run-1",input.path("requestId").asText(),doc,Mode.PDF_TABLES,status.get(),List.of(),
                    HarnessJson.mapper().createArrayNode(),List.of(new Unit(0,status.get()==Status.COMPLETED?"OBSERVED":"UNCONFIRMED",null)),new Source(REVISION,HASH,"extract_tables_from_pdf"),"run-1",Instant.now(),0,List.of());
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(raw);
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(http.get(),bytes.length);x.getResponseBody().write(bytes);x.close();
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    CapabilityRequestV1 request(Map<String,Object> params){
        var c=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request","trace","reviewer",FinanceContextV1.PrincipalType.EMPLOYEE,
                "fixture","MATERIAL","customer","session",FinanceContextV1.AuthenticationLevel.STRONG,Set.of("material:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,FinancialMaterialProvider.EXTRACT,CapabilityRequestV1.Operation.QUERY,c,params,null,null,Map.of(),null);
    }
    FinancialMaterialProvider provider(){
        var connection=new ManagedProviderFactoryV01.Connection(FinancialMaterialFactoryV01.KIND,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),current::get,()->null,a->{});
        var binding=new FinancialMaterialFactoryV01.Binding("reviewer","customer","session","task",Mode.PDF_TABLES,HASH);
        return new FinancialMaterialProvider(connection,new FinancialMaterialFactoryV01.Config("financial-material-bindings.v1",List.of(binding)),Clock.systemUTC());
    }
    @Test void actualHttpResultAndPartialStateAreDistinct(){
        var p=provider();assertEquals(CapabilityResultV1.Status.COMPLETED,p.query(request(Map.of())).status());
        status.set(Status.PARTIAL);var result=p.query(request(Map.of()));
        assertEquals(CapabilityResultV1.Status.NEED_USER,result.status());assertTrue(result.data().containsKey("material"));
    }
    @Test void modelCannotSelectIdentityOrSourcePath(){
        for(String key:List.of("documentRef","taskRef","principalId","path","endpoint","script"))
            assertEquals(CapabilityResultV1.Status.FAILED,provider().query(request(Map.of(key,"forged"))).status());
        assertEquals(0,calls.get());
    }
    @Test void currentRevocationBeforeOrAfterHttpClosesDisclosure(){
        var p=provider();current.set(null);assertEquals("ACCESS_DENIED",p.query(request(Map.of())).error().sourceCode());assertEquals(0,calls.get());
    }
    @Test void inFlightRevocationAndSubstitutedTaskAreRejected(){
        received=()->current.set(null);var result=provider().query(request(Map.of()));assertTrue(result.data().isEmpty());assertEquals("ACCESS_DENIED",result.error().sourceCode());
    }
    @Test void taskMismatchAnd503AreNotCompletedFacts(){
        task.set("other");assertEquals("MATERIAL_RESULT_INVALID",provider().query(request(Map.of())).error().sourceCode());
        http.set(503);var failed=provider().query(request(Map.of()));assertTrue(failed.data().isEmpty());assertEquals("MATERIAL_HTTP_503",failed.error().sourceCode());
    }
    @Test void factoryRejectsRelativeOrUnlockedConfiguration(){
        assertThrows(IllegalArgumentException.class,()->new FinancialMaterialFactoryV01("relative.json",HASH).configurationDigests());
        assertEquals(2,new FinancialMaterialFactoryV01().requirements().size());
    }
}
