package io.github.bitlhk.finance.harness.integrations.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.providers.report.*;
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

class ReportNativeMethodTest {
    @TempDir Path temp;
    static final String METHOD="原专业方法：先取客户依据，再按实际事实形成画像。";
    static final ObjectMapper JSON=ReportMapper.json();
    static final class Fixture implements AutoCloseable {
        final Path artifact,state,authorityFile,methodFile;final HttpServer server;
        final ManagedScenarioExtensionV01.Context context;final JsonNode fixture;
        final Map<String,JsonNode> requests=new ConcurrentHashMap<>();
        final List<JsonNode> models=new CopyOnWriteArrayList<>();
        final AtomicInteger calls=new AtomicInteger();final AtomicBoolean failSource=new AtomicBoolean(),running=new AtomicBoolean(),stopped=new AtomicBoolean(),badReference=new AtomicBoolean(),skipTool=new AtomicBoolean(),omitUsage=new AtomicBoolean();
        final Set<String> cancelledRuns=ConcurrentHashMap.newKeySet();
        volatile String lastSource;
        Fixture(Path root) throws Exception {
            Path repo=Path.of("").toAbsolutePath();while(repo!=null&&!Files.isRegularFile(repo.resolve("conformance/professional-report-readonly/schema-cases.v1.json")))repo=repo.getParent();assertNotNull(repo);
            fixture=ReportMapper.parse(Files.readAllBytes(repo.resolve("conformance/professional-report-readonly/schema-cases.v1.json")));
            artifact=Files.createDirectory(root.resolve("artifact"));state=Files.createDirectory(root.resolve("private"));
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v1/reports/",ex->{
                JsonNode body=ReportMapper.parse(ex.getRequestBody().readAllBytes());String action=ex.getRequestURI().getPath().substring("/v1/reports/".length()),run=body.path("runId").asText();JsonNode out;
                if(action.equals("start")){requests.put(run,body.path("request").deepCopy());lastSource=run;}
                if(action.equals("input"))out=requests.get(run);
                else if(action.equals("result"))out=source(run);
                else if(action.equals("artifact"))out=JSON.valueToTree(Map.of("artifact",source(run).path("artifacts").get(0),"contentBase64",Base64.getEncoder().encodeToString(fixture.path("originalDocument").asText().getBytes(StandardCharsets.UTF_8))));
                else {if(action.equals("cancel")){stopped.set(true);cancelledRuns.add(run);}out=JSON.createObjectNode().put("runId",run).put("status",cancelledRuns.contains(run)?"CANCELLED":running.get()?"RUNNING":"SUCCEEDED").put("workerStopped",cancelledRuns.contains(run)||!running.get());}
                byte[] bytes=ReportMapper.bytes(out);ex.getResponseHeaders().set("Content-Type","application/json");try{ex.sendResponseHeaders(failSource.get()?503:200,bytes.length);ex.getResponseBody().write(bytes);}finally{ex.close();}
            });
            server.createContext("/chat/completions",ex->{
                JsonNode body=ReportMapper.parse(ex.getRequestBody().readAllBytes());models.add(body);int call=calls.incrementAndGet();JsonNode message;
                boolean tool=call%2==1&&!skipTool.get();
                if(tool)message=JSON.valueToTree(Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","call-"+call,"type","function","function",Map.of("name","wealth_assistant_customer_detail","arguments","{\"customer_id\":\"customerRef1\"}")))));
                else {
                    String ref=badReference.get()?"invented":lastSource+"_e1";
                    String content=JSON.writeValueAsString(Map.of("sections",List.of(Map.of("title","客户画像","text","依据本次原工具返回形成待复核说明。","evidenceRefs",List.of(ref)))));
                    message=JSON.valueToTree(Map.of("role","assistant","content",content));
                }
                var reply=new LinkedHashMap<String,Object>();reply.put("id","local-"+call);reply.put("object","chat.completion");reply.put("model","fixed-model");reply.put("choices",List.of(Map.of("index",0,"finish_reason",tool?"tool_calls":"stop","message",message)));
                if(!omitUsage.get())reply.put("usage",Map.of("prompt_tokens",17,"completion_tokens",5,"total_tokens",22));
                byte[] bytes=ReportMapper.bytes(JSON.valueToTree(reply));
                ex.getResponseHeaders().set("Content-Type","application/json");try{ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);}finally{ex.close();}
            });server.start();
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            methodFile=artifact.resolve("assets/private-methods/original-method/1.0.0/skills/original-method/SKILL.md");Files.createDirectories(methodFile.getParent());Files.writeString(methodFile,METHOD,StandardCharsets.UTF_8);
            ObjectNode binding=fixture.path("valid").path("binding").deepCopy();binding.put("methodId","original-method");binding.put("methodDigest",ReportMapper.sha(Files.readAllBytes(methodFile)));binding.put("instanceId","HOST_INSTANCE");
            ObjectNode request=JSON.createObjectNode().put("schemaVersion","finance-professional-report-request.v1").put("assetId","wealth-kyc").put("caseId","synthetic").put("tool","wealth_assistant_context_probe");request.set("arguments",JSON.createObjectNode());request.set("binding",binding);
            JsonNode config=JSON.valueToTree(Map.of("schemaVersion","professional-report-bindings.v1","bindings",List.of(Map.of("principalId","ownerRef1","subjectRef","customerRef1","sessionRef","source-session","context",fixture.path("valid").path("scope"),"request",request))));
            Path bindings=state.resolve("bindings.json");byte[] bytes=ReportMapper.bytes(config);Files.write(bindings,bytes);String configDigest=ReportMapper.sha(bytes);
            JsonNode authority=JSON.valueToTree(Map.of("schemaVersion","professional-report-host-authority.v1","ownerRef","ownerRef1","endpoint",endpoint,"timeoutMs",3000,"bindingsFile",bindings.toString(),"bindingsDigest",configDigest,"leases",List.of(Map.of("principalId","ownerRef1","subjectRef","customerRef1","taskRef","taskRef1","sessionRef","source-session","credential","private-fixture-token-not-a-real-key","expiresAt",Instant.now().plusSeconds(300).toString(),"scopes",List.of(ProfessionalReportProvider.SCOPE)))));
            authorityFile=state.resolve("report-authority.json");Files.write(authorityFile,ReportMapper.bytes(authority));
            JsonNode plan=JSON.valueToTree(Map.of("profileRef",Map.of("id","wealth-kyc-report-readonly"),"runtime",Map.of("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15"),"capabilityPackRefs",List.of(Map.of("source","repo://capability-packs/professional-report-readonly")),"domainPackRefs",List.of(Map.of("source","repo://domain-packs/professional-report"))));
            Path planFile=artifact.resolve("component-plan.json");Files.write(planFile,ReportMapper.bytes(plan));
            JsonNode lock=JSON.valueToTree(Map.of("scenarioExtension",Map.of("id",ReportScenarioExtensionV01.ID),"providerConfiguration",Map.of(ProfessionalReportProvider.ID,Map.of("binding-file-sha256",configDigest.substring(7))),"method",Map.of("assetId","original-method","version","1.0.0"),"files",Map.of("component-plan.json",ReportMapper.sha(Files.readAllBytes(planFile)).substring(7),artifact.relativize(methodFile).toString().replace('\\','/'),ReportMapper.sha(Files.readAllBytes(methodFile)).substring(7))));
            Files.write(artifact.resolve("service-lock.json"),ReportMapper.bytes(lock));
            context=new ManagedScenarioExtensionV01.Context(artifact,state,"buildId1","instanceId1","ownerRef1",new ManagedScenarioExtensionV01.ModelConnection("OpenAI","local-fixture-only",endpoint,"fixed-model"));
        }
        JsonNode source(String id) {
            ObjectNode n=fixture.path("valid").deepCopy();String root=requests.get(id).path("binding").path("executionId").asText();n.put("reportId",id);n.put("runId",root);n.set("binding",requests.get(id).path("binding"));
            for(JsonNode e:n.path("evidence")){((ObjectNode)e).put("runId",root);((ObjectNode)e).put("sourceResultId",id);}
            for(JsonNode a:n.path("artifacts")){((ObjectNode)a).put("runId",root);((ObjectNode)a).put("sourceResultId",id);((ObjectNode)a).put("name","raw-return.json");}return n;
        }
        void revoke() throws Exception {ObjectNode n=(ObjectNode)ReportMapper.parse(Files.readAllBytes(authorityFile));n.set("leases",JSON.createArrayNode());Files.write(authorityFile,ReportMapper.bytes(n));}
        @Override public void close(){server.stop(0);}
    }
    @Test void realNativeSdkLoadsOriginalMethodAndCallsTheOriginalNamedTool() throws Exception {
        try(var f=new Fixture(temp);var scope=new ReportSourceScope(f.context)) {
            var method=InstalledPreparationMethod.load("original-method","1.0.0",f.methodFile,ReportMapper.sha(Files.readAllBytes(f.methodFile)),"sha256:"+"a".repeat(64));
            try(var provider=new ProfessionalReportProvider(new ManagedProviderFactoryV01.Connection(ProfessionalReportFactoryV01.KIND,URI.create(scope.initial().endpoint()),Duration.ofSeconds(3),scope::session,()->null,a->{}),scope.executionConfig(),Clock.systemUTC())) {
                var run=new ReportMethodAgent(f.context,scope,provider,method,()->scope.current(),"native-run").run("请生成当前客户画像");
                assertEquals("SUCCEEDED",run.status());assertEquals(2,f.models.size());assertEquals(1,run.sourceResults().size());assertNotNull(run.report());
                assertTrue(f.models.getFirst().toString().contains(METHOD));assertEquals(3,f.models.getFirst().path("tools").size());
                assertTrue(f.models.getFirst().toString().contains(ReportMethodAgent.FORMAT_INSTRUCTION),"Actual model request must include the report format and evidence instructions");
                assertEquals(2,run.metrics().get("modelCalls"));assertEquals(34L,run.metrics().get("inputTokens"));assertEquals(10L,run.metrics().get("outputTokens"));assertNull(run.metrics().get("cachedInputTokens"));
                assertEquals("BLOCKED",run.report().document().path("qualification").path("professional").asText());assertEquals("MODEL_EXPLANATION",run.report().document().path("sections").get(1).path("origin").asText());
                assertEquals("instanceId1",run.report().document().path("binding").path("instanceId").asText());
                assertEquals("HOST_INSTANCE",scope.factory.config().bindings().getFirst().request().path("binding").path("instanceId").asText());
            }
        }
    }
    @Test void wrongInstalledMethodIsRejectedBeforeAnyModelCall() throws Exception {
        try(var f=new Fixture(temp);var scope=new ReportSourceScope(f.context);var provider=(ProfessionalReportProvider)scope.factory.create(new ManagedProviderFactoryV01.Connection(ProfessionalReportFactoryV01.KIND,URI.create(scope.initial().endpoint()),Duration.ofSeconds(3),scope::session,()->null,a->{}),Map.of())) {
            var wrong=new InstalledPreparationMethod("other","1.0.0","sha256:"+"b".repeat(64),"sha256:lock",METHOD);assertThrows(SecurityException.class,()->new ReportMethodAgent(f.context,scope,provider,wrong,()->scope.current(),"run"));assertEquals(0,f.calls.get());
        }
    }

    @Test void missingModelUsageIsNotRecordedAsZero() throws Exception {
        try(var f=new Fixture(temp);var s=new ReportSession(f.context)) {
            f.omitUsage.set(true);var result=s.call("generate",JSON.valueToTree(Map.of("query","客户画像")));
            assertEquals("SUCCEEDED",result.get("status"));var metrics=JSON.valueToTree(result.get("report")).path("metrics");
            assertTrue(metrics.path("inputTokens").isNull());assertTrue(metrics.path("outputTokens").isNull());assertTrue(metrics.path("cachedInputTokens").isNull());
        }
    }
}
