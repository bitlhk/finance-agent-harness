package io.github.bitlhk.finance.harness.providers.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.report.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ProfessionalReportProviderTest {
    static final Instant NOW=Instant.parse("2026-11-05T12:00:00Z");
    static final ObjectMapper JSON=ReportMapper.json();
    HttpServer server;JsonNode fixture,input;
    AtomicReference<ManagedProviderFactoryV01.SourceSession> authority=new AtomicReference<>();
    AtomicBoolean running=new AtomicBoolean(),cancelled=new AtomicBoolean(),revoke=new AtomicBoolean(),tamper=new AtomicBoolean();
    AtomicInteger requests=new AtomicInteger(),httpStatus=new AtomicInteger(200);
    @TempDir Path privateRoot;
    @BeforeEach void start() throws Exception {
        Path p=Path.of("").toAbsolutePath();while(p!=null&&!Files.isRegularFile(p.resolve("conformance/professional-report-readonly/schema-cases.v1.json")))p=p.getParent();assertNotNull(p);
        fixture=ReportMapper.parse(Files.readAllBytes(p.resolve("conformance/professional-report-readonly/schema-cases.v1.json")));
        authority.set(new ManagedProviderFactoryV01.SourceSession("private-fixture-token","customerRef1","ownerRef1","session",NOW.plusSeconds(120)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/reports/",ex->{
            requests.incrementAndGet();String action=ex.getRequestURI().getPath().substring("/v1/reports/".length());
            JsonNode r=ReportMapper.parse(ex.getRequestBody().readAllBytes());assertEquals("Bearer private-fixture-token",ex.getRequestHeaders().getFirst("Authorization"));assertEquals(fixture.path("valid").path("scope"),r.path("context"));
            String id=r.path("runId").asText();JsonNode out;
            if(action.equals("start"))input=r.path("request").deepCopy();
            if(action.equals("input"))out=input;
            else if(action.equals("result"))out=result(id);
            else if(action.equals("artifact")) {
                var artifact=result(id).path("artifacts").get(0);String original=fixture.path("originalDocument").asText()+(tamper.get()?" ":"");
                out=JSON.valueToTree(Map.of("artifact",artifact,"contentBase64",Base64.getEncoder().encodeToString(original.getBytes(StandardCharsets.UTF_8))));
            }else {
                if(action.equals("cancel"))cancelled.set(true);
                out=JSON.createObjectNode().put("runId",id).put("status",cancelled.get()?"CANCELLED":running.get()?"RUNNING":"SUCCEEDED").put("workerStopped",cancelled.get()||!running.get());
            }
            if(action.equals("start")&&revoke.get())authority.set(null);
            byte[] bytes=ReportMapper.bytes(out);ex.getResponseHeaders().set("Content-Type","application/json");
            try{ex.sendResponseHeaders(httpStatus.get(),bytes.length);ex.getResponseBody().write(bytes);}finally{ex.close();}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    JsonNode result(String id) {
        ObjectNode n=fixture.path("valid").deepCopy();n.put("reportId",id);n.put("runId","trace");n.set("binding",input.path("binding"));
        for(JsonNode e:n.path("evidence")){((ObjectNode)e).put("runId","trace");((ObjectNode)e).put("sourceResultId",id);}
        for(JsonNode a:n.path("artifacts")){((ObjectNode)a).put("runId","trace");((ObjectNode)a).put("sourceResultId",id);((ObjectNode)a).put("name","raw-return.json");}
        return n;
    }
    ProfessionalReportFactoryV01.Binding binding() {
        ObjectNode r=JSON.createObjectNode().put("schemaVersion","finance-professional-report-request.v1").put("assetId","wealth-kyc").put("caseId","synthetic").put("tool","wealth_assistant_context_probe");
        r.set("arguments",JSON.createObjectNode());r.set("binding",fixture.path("valid").path("binding"));
        return new ProfessionalReportFactoryV01.Binding("ownerRef1","customerRef1","session",fixture.path("valid").path("scope"),r);
    }
    ProfessionalReportProvider provider() {
        return new ProfessionalReportProvider(new ManagedProviderFactoryV01.Connection(ProfessionalReportFactoryV01.KIND,URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(3),authority::get,()->null,a->{}),
            new ProfessionalReportFactoryV01.Config("professional-report-bindings.v1",List.of(binding())),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    CapabilityRequestV1 request(Map<String,Object> params) {
        var c=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request","trace","ownerRef1",FinanceContextV1.PrincipalType.EMPLOYEE,"local","REPORT","customerRef1","session",FinanceContextV1.AuthenticationLevel.STRONG,Set.of(ProfessionalReportProvider.SCOPE));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,"professional.report.wealth.query",CapabilityRequestV1.Operation.QUERY,c,params,null,null,Map.of(),null);
    }
    @Test void reportOriginalBytesAndRootExecutionAreVerified() {
        try(var p=provider()) {
            var r=p.query(request(Map.of()));assertEquals(CapabilityResultV1.Status.COMPLETED,r.status());
            var data=JSON.valueToTree(r.data());assertEquals("trace",data.path("report").path("runId").asText());
            assertTrue(data.path("report").path("metrics").path("inputTokens").isNull());assertEquals("BLOCKED",data.path("report").path("qualification").path("professional").asText());
            assertNotEquals(fixture.path("valid").path("binding").path("inputDigest"),input.path("binding").path("inputDigest"));
            var a=data.path("report").path("artifacts").get(0);p.checkStoredStatus(request(Map.of()),a.path("sourceResultId").asText());assertNotNull(p.artifact(request(Map.of()),a.path("sourceResultId").asText(),a));
            authority.set(null);assertThrows(SecurityException.class,()->p.artifact(request(Map.of()),a.path("sourceResultId").asText(),a));
        }
    }
    @Test void forgedIdentityEndpointAndToolAreRejectedBeforeSourceAccess() {
        try(var p=provider()) {
            for(var params:List.of(Map.<String,Object>of("ownerRef","forged"),Map.<String,Object>of("tool","execute_python"),Map.<String,Object>of("tool","wealth_assistant_customer_detail","arguments",Map.of("customer_id","other")))) {
                assertEquals(CapabilityResultV1.Status.FAILED,p.query(request(params)).status());
            }
            assertEquals(0,requests.get());authority.set(null);assertEquals("ACCESS_DENIED",p.query(request(Map.of())).error().sourceCode());
        }
        assertThrows(IllegalArgumentException.class,()->new ProfessionalReportProvider(new ManagedProviderFactoryV01.Connection(ProfessionalReportFactoryV01.KIND,URI.create("http://example.com"),Duration.ofSeconds(1),authority::get,()->null,a->{}),new ProfessionalReportFactoryV01.Config("professional-report-bindings.v1",List.of(binding())),Clock.systemUTC()));
    }
    @Test void changedOriginalDocumentCannotBecomeSuccessfulReport() {
        tamper.set(true);try(var p=provider()){assertEquals(CapabilityResultV1.Status.FAILED,p.query(request(Map.of())).status());assertTrue(cancelled.get());}
    }
    @Test void revokedAuthorityAfterStartDiscardsResultAndRequestsCleanup() {
        revoke.set(true);try(var p=provider()){var r=p.query(request(Map.of()));assertEquals(CapabilityResultV1.Status.FAILED,r.status());assertTrue(r.data().isEmpty());assertTrue(cancelled.get());}
    }
    @Test void cancellationReachesSourceAndNeverReturnsLateSuccess() throws Exception {
        running.set(true);try(var p=provider();var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->p.query(request(Map.of())));long end=System.nanoTime()+Duration.ofSeconds(1).toNanos();while(requests.get()==0&&System.nanoTime()<end)Thread.sleep(5);
            assertTrue(p.cancelActive());assertEquals(CapabilityResultV1.Status.FAILED,pending.get(3,TimeUnit.SECONDS).status());assertTrue(cancelled.get());
        }
    }
    @Test void source503IsExplicitFailureWithNoResultOrCredentialLeak() {
        httpStatus.set(503);try(var p=provider()){var r=p.query(request(Map.of()));assertEquals(CapabilityResultV1.Status.FAILED,r.status());assertTrue(r.data().isEmpty());assertFalse(r.toString().contains("private-fixture-token"));}
    }
    @Test void immutableConfigurationRequiresExactDigestAndRejectsUnknownFields() throws Exception {
        JsonNode config=JSON.valueToTree(new ProfessionalReportFactoryV01.Config("professional-report-bindings.v1",List.of(binding())));Path file=privateRoot.resolve("binding.json");byte[] bytes=ReportMapper.bytes(config);Files.write(file,bytes);
        var factory=new ProfessionalReportFactoryV01(file.toString(),ReportMapper.sha(bytes));assertEquals(2,factory.requirements().size());assertEquals(ReportMapper.sha(bytes).substring(7),factory.configurationDigests().get("binding-file-sha256"));
        String originalDigest=ReportMapper.sha(bytes);Files.writeString(file,"{}");assertSame(factory.config(),factory.config());assertThrows(IllegalArgumentException.class,()->new ProfessionalReportFactoryV01(file.toString(),originalDigest).config());
        ((ObjectNode)config).put("unexpected",true);bytes=ReportMapper.bytes(config);Files.write(file,bytes);String digest=ReportMapper.sha(bytes);assertThrows(IllegalArgumentException.class,()->new ProfessionalReportFactoryV01(file.toString(),digest).config());
    }
}
