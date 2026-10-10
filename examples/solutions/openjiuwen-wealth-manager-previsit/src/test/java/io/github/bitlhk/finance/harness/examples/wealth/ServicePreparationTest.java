package io.github.bitlhk.finance.harness.examples.wealth;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentWealthSession;
import io.github.bitlhk.finance.harness.providers.employeeagent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ServicePreparationTest {
    @Test void previewAllowsOnlyExplicitLoopbackOriginsAndUnambiguousSessionCookie() {
        var origins=ServicePreparationDemo.previewOrigins(18741,"http://localhost:49291");
        assertTrue(origins.contains("http://localhost:49291"));
        assertTrue(origins.contains("http://127.0.0.1:18741"));
        assertFalse(origins.contains("http://localhost:49292"));
        for(String origin:List.of("http://evil.test:49291","http://localhost.evil.test:49291","http://localhost:49291/x","http://u@localhost:49291"))
            assertThrows(IllegalArgumentException.class,()->ServicePreparationDemo.previewOrigins(18741,origin));
        assertTrue(ServicePreparationDemo.hasSessionCookie("lang=zh; prep=test; other=1","test"));
        assertFalse(ServicePreparationDemo.hasSessionCookie("prep=wrong; prep=test","test"));
        assertFalse(ServicePreparationDemo.hasSessionCookie(null,"test"));
    }
    @TempDir Path files;
    HttpServer server;
    ServicePreparation host;
    EmployeeAgentWealthSession session;
    final AtomicInteger calls=new AtomicInteger();
    final AtomicInteger models=new AtomicInteger();
    final AtomicReference<ServicePreparation.Mapping> mapping=new AtomicReference<>();
    final AtomicReference<EmployeeAgentWealthSession.Authority> authority=new AtomicReference<>();
    volatile Runnable beforeResponse=()->{};
    volatile int providerStatus=200;
    volatile boolean explainFail;
    volatile boolean explainEmpty;
    volatile boolean sparseFacts;
    volatile boolean unknownRef;
    volatile boolean missingDate;
    volatile String returnedCustomer;
    @BeforeEach void start() throws Exception {
        var runtime=new EmployeeAgentRuntimeIdentity("synthetic-runtime","synthetic-agent","synthetic-adoption");
        var expiry=Instant.now().plusSeconds(300);
        authority.set(new EmployeeAgentWealthSession.Authority("owner-a",
            new EmployeeAgentIdentityBinding("syn-manager",runtime,"grant-v1",expiry),
            new BoundFinanceProvider.Grant("syn-manager",FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of("syn-a","syn-b"),Set.of("WEALTH_PREVISIT"),Set.of("wealth:previsit:read"),"session-a","grant-v1",expiry),
            FinanceContextV1.AuthenticationLevel.BASIC));
        mapping.set(new ServicePreparation.Mapping("mapping-v1",Map.of("客户甲","syn-a","客户乙","syn-b","越权客户","syn-denied")));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/internal/platform-tools/mcp",x->{
            calls.incrementAndGet();
            var input=HarnessJson.mapper().readTree(x.getRequestBody());
            assertEquals("prepare_wealth_previsit_context",input.at("/params/name").asText());
            String customer=input.at("/params/arguments/customer_id").asText();
            beforeResponse.run();
            var context=new LinkedHashMap<String,Object>();context.put("schema","ea.wealth-previsit-context.v1");context.put("status",sparseFacts?"degraded":"ready");
            context.put("customer",Map.of("customerId",returnedCustomer==null?customer:returnedCustomer,"displayName",customer,
                    "customerStatus","active","totalAssets",customer.equals("syn-a")?1000:2000));
            context.put("profiles",sparseFacts?List.of(Map.of("profileType","wealth","detailSchemaVersion","wealth.v2"))
                    :List.of(Map.of("profileType","wealth","detailSchemaVersion","wealth.v2","detail",Map.of(
                            "totalAssets",customer.equals("syn-a")?1000:2000,
                            "riskAssessment",Map.of("riskLevel","C3","validUntil","2027-10-01T00:00:00Z"),
                            "recentEvents",List.of(Map.of("type","review_due","date","2026-10-15"))))));
            context.put("dataAsOf",missingDate?"":"2026-10-01T00:00:00Z");
            context.put("missingInputs",sparseFacts?List.of("CRM_PROFILE_BUSINESS_DETAIL_MISSING"):List.of());
            context.put("readiness",Map.of("status",sparseFacts?"PARTIAL":"READY",
                    "reasons",sparseFacts?List.of("CRM_PROFILE_BUSINESS_DETAIL_MISSING"):List.of()));
            var receipt=Map.of("receiptId","receipt-"+customer,"receiptFingerprint","a".repeat(64),"createdAt","2026-10-08T00:00:00Z");
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(Map.of("jsonrpc","2.0","id",input.path("id").asText(),
                "result",Map.of("content",List.of(Map.of("type","text","text","EA_WEALTH_PREVISIT_CONTEXT:"+HarnessJson.mapper().writeValueAsString(context))),
                "_meta",Map.of("eaContextReceipt",receipt))));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(providerStatus,bytes.length);
            x.getResponseBody().write(bytes);x.close();
        });server.start();
        session=new EmployeeAgentWealthSession("owner-a","session-a",runtime,authority::get,c->"synthetic-token",
            new ManagedProviderFactoryV01.Connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND,
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(5),()->null,()->null,c->{}),
            Set.of(ServicePreparation.CAPABILITY));
        host=new ServicePreparation("owner-a",new EmployeeAgentPreparationAccess(session),mapping::get,(goal,ids,context)->{
            assertFalse(context.has("factPresentation"),"display projection must not enter the model fact namespace");
            assertTrue(context.has("temporalAssessment"),"deterministic temporal assessment must reach validation context");
            models.incrementAndGet();if(explainFail)throw new TimeoutException("synthetic failure");if(explainEmpty)return List.of();
            return unknownRef?List.of("/unknown"):List.of("/customer/totalAssets");
        },files,Map.of("provider","employee-agent-reference@0.1.0-SNAPSHOT","capability","wealth-manager-previsit@0.1.0"));
    }
    @AfterEach void close(){host.close();server.stop(0);}
    private com.fasterxml.jackson.databind.node.ObjectNode prepare() throws Exception {
        return host.prepare("owner-a",host.select("owner-a","客户甲","了解资金情况"));
    }
    @Test void normalQueryReviewSaveAndReplayDoesNotRepeatTools() throws Exception {
        assertEquals(List.of("客户乙","客户甲"),host.customers("owner-a"));
        var r=prepare();String id=r.path("reportId").asText();
        assertEquals("READY_FOR_REVIEW",r.path("status").asText(),r.toString());
        assertEquals("READY",r.at("/factAssessment/status").asText(),r.toString());
        assertEquals("2026-10-01T00:00:00Z",r.path("dataAsOf").asText());
        assertFalse(r.path("sourceReceipt").isEmpty());
        int generatedRevision=r.path("reportRevision").asInt();
        var saved=host.review("owner-a",id,generatedRevision,"已核实事实，准备回访；未写 CRM");
        assertEquals("syn-manager",saved.path("reviewerRef").asText());
        assertEquals(generatedRevision+1,saved.path("reportRevision").asInt());
        assertEquals(1,saved.path("reviewHistory").size());
        assertEquals(saved,host.read("owner-a",id));host.read("owner-a",id);
        assertEquals("RUNNING",host.readVersion("owner-a",id,1).path("status").asText());
        assertEquals("PENDING",host.readVersion("owner-a",id,generatedRevision).path("reviewStatus").asText());
        assertEquals("REVIEWED",host.readVersion("owner-a",id,generatedRevision+1).path("reviewStatus").asText());
        assertEquals(1,calls.get());assertEquals(1,models.get());
        assertThrows(IllegalStateException.class,()->host.review("owner-a",id,generatedRevision,"重复意见"));
    }
    @Test void taskContextKeepsIndependentFactDimensionsAndNoteUsesCurrentVersionWithoutCalls() throws Exception {
        var context=HarnessJson.mapper().createObjectNode();context.put("schemaVersion","customer-manager-task-context.v0.1");
        context.put("taskId",UUID.randomUUID().toString());context.put("revision",2);context.putArray("questions").add("风险测评何时到期？");
        var fact=context.putArray("facts").addObject();fact.put("key","visit.liquidityAmount");fact.put("value",400000);fact.put("unit","元");
        fact.putObject("source").put("kind","USER_STATEMENT").put("ref","CURRENT_CONVERSATION");
        fact.putObject("verification").put("status","UNVERIFIED");fact.putObject("completeness").put("status","PRESENT");
        fact.putObject("validity").put("status","UNKNOWN");
        var report=host.prepare("owner-a",host.select("owner-a","客户甲","准备回访",context));
        assertEquals("USER_STATEMENT",report.at("/factState/items/3/source/kind").asText(),report.toString());
        assertEquals("UNVERIFIED",report.at("/factState/items/3/verification/status").asText());
        assertEquals("PRESENT",report.at("/factState/items/3/completeness/status").asText());
        assertEquals("UNKNOWN",report.at("/factState/items/3/validity/status").asText());
        int beforeCalls=calls.get(),beforeModels=models.get();
        var note=host.note("owner-a",report.path("reportId").asText(),report.path("reportRevision").asInt());
        assertTrue(note.path("content").asText().contains("400000"));
        assertTrue(note.path("content").asText().contains("用户口述")||note.path("content").asText().contains("USER_STATEMENT"));
        assertEquals(0,note.path("businessWriteCalls").asInt());
        assertEquals(0,note.path("businessFactProviderCalls").asInt());
        assertEquals(0,note.path("modelGenerationCalls").asInt());
        assertEquals(beforeCalls,calls.get());assertEquals(beforeModels,models.get());
        assertThrows(IllegalArgumentException.class,()->host.note("owner-a",UUID.randomUUID().toString(),null));
        assertEquals(beforeCalls,calls.get());assertEquals(beforeModels,models.get());
    }
    @Test void sparseProfilesArePartialAndExplainMissingBusinessFacts() throws Exception {
        sparseFacts=true;var report=prepare();
        assertEquals("PARTIAL",report.path("status").asText(),report.toString());
        assertEquals("PARTIAL",report.at("/factAssessment/status").asText());
        String missing=report.at("/factAssessment/missing").toString();
        assertTrue(missing.contains("未取得财富资产或持仓明细"),missing);
        assertTrue(missing.contains("未取得风险测评资料"),missing);
        assertTrue(missing.contains("未取得近期联系、事项或到期事件"),missing);
    }
    @Test void presentationKeepsValuesAndFlagsPastMaturityForVerification() throws Exception {
        var facts=HarnessJson.mapper().readTree("""
            {"dataAsOf":"2026-08-27T12:00:00Z","profiles":[{"profileType":"wealth","detail":{
              "portfolioSummary":{"totalAssets":1800000,"availableCash":360000,"investedAssets":1440000},
              "riskAssessment":{"riskLevel":"C3","validUntil":"2027-08-27T23:59:59Z"},
              "holdings":[{"productName":"稳健配置组合","balance":900000,"status":"active","maturityDate":"2027-03-15"}],
              "maturities":[{"productName":"到期现金管理计划","amount":300000,"status":"upcoming","maturityDate":"2026-09-18"}]
            }}]}""");
        var value=ServicePreparation.presentFacts(facts,Instant.parse("2026-10-10T00:00:00Z"));
        assertEquals(1800000,value.at("/portfolio/totalAssets").asInt());
        assertEquals("C3",value.at("/riskAssessment/riskLevel").asText());
        assertEquals("稳健配置组合",value.at("/holdings/0/productName").asText());
        assertEquals("PAST_DATE_STATUS_UNVERIFIED",value.at("/maturities/0/verificationStatus").asText());
        assertEquals("PAST_DUE_UNVERIFIED",value.at("/maturities/0/effectiveStatus").asText());
        assertTrue(value.at("/maturities/0/warning").asText().contains("待核实"));
        assertEquals("HISTORICAL_SNAPSHOT",value.path("freshnessStatus").asText());
    }
    @Test void expiredMaturityCannotBeDescribedAsUpcoming() throws Exception {
        var facts=HarnessJson.mapper().readTree("""
            {"temporalAssessment":{"maturities":[{"maturityDate":"2026-09-18","effectiveStatus":"PAST_DUE_UNVERIFIED"}]}}
            """);
        assertThrows(IllegalArgumentException.class,()->ServicePreparation.validateTemporalSemantics("该计划即将到期",facts));
        assertDoesNotThrow(()->ServicePreparation.validateTemporalSemantics("该计划到期日已过，状态待核实",facts));
    }
    @Test void emptyModelOutputHasDeterministicFailureCodeAndFallback() throws Exception {
        explainEmpty=true;var report=prepare();
        assertEquals("PARTIAL",report.path("status").asText());
        assertEquals("MODEL_OUTPUT_EMPTY",report.path("failureCode").asText(),report.toString());
        assertEquals("FINAL_OUTPUT_VALIDATION",report.at("/modelDiagnostics/stage").asText(),report.toString());
        assertEquals("MODEL_REPORT_EMPTY",report.at("/modelDiagnostics/failureReasonCode").asText(),report.toString());
        assertFalse(report.path("fallbackSuggestion").isEmpty());
    }
    @Test void latestVersionSummariesAndReplayArePureBusinessReads() throws Exception {
        var generated=prepare();String id=generated.path("reportId").asText();
        var reviewed=host.review("owner-a",id,generated.path("reportRevision").asInt(),"完成工程复核");
        assertEquals(id,host.latest("owner-a").orElseThrow().path("reportId").asText());
        var summaries=host.versionSummaries("owner-a",id);
        assertEquals(reviewed.path("reportRevision").asInt(),summaries.getLast().get("revision"));
        assertEquals("完成工程复核",summaries.getLast().get("reviewOpinion"));
        var replay=host.replay("owner-a",id,generated.path("reportRevision").asInt());
        assertEquals(0,replay.at("/replayEvidence/businessFactProviderCalls").asInt(),replay.toString());
        assertEquals(0,replay.at("/replayEvidence/modelGenerationCalls").asInt(),replay.toString());
        assertTrue(replay.at("/replayEvidence/authorizationRevalidated").asBoolean());
        assertEquals(generated.path("reportRevision").asInt(),replay.at("/report/reportRevision").asInt());
        assertEquals(1,calls.get());assertEquals(1,models.get());
    }
    @Test void unauthorizedCustomerNoCallOrReport() {
        assertThrows(SecurityException.class,()->host.select("owner-a","越权客户","回访"));
        assertEquals(0,calls.get());assertEquals(0,models.get());
    }
    @Test void missingMappingAndAdminCannotBypassScope() {
        mapping.set(null);
        assertThrows(SecurityException.class,()->host.select("owner-a","客户甲","回访"));
        assertThrows(SecurityException.class,()->host.select("admin","客户甲","回访"));
        assertEquals(0,calls.get());
    }
    @Test void missingEmployeeBindingNoCall() {
        authority.set(null);assertThrows(SecurityException.class,this::prepare);assertEquals(0,calls.get());
    }
    @Test void toolFailureDoesNotInventFactsOrCallExplanation() throws Exception {
        providerStatus=503;var r=prepare();
        assertEquals("FAILED",r.path("status").asText());assertFalse(r.has("facts"));
        assertEquals(0,models.get());assertEquals(1,calls.get());
    }
    @Test void explanationFailurePreservesFactsAndCannotBeReviewedIntoComplete() throws Exception {
        explainFail=true;var r=prepare();
        assertEquals("PARTIAL",r.path("status").asText());
        assertEquals(1000,r.at("/facts/customer/totalAssets").asInt());
        assertEquals("FAILED",r.path("explanationStatus").asText());
        assertEquals("REPORT_MODEL_TIMEOUT",r.path("failureCode").asText());
        assertEquals("MODEL_TRANSPORT",r.at("/modelDiagnostics/stage").asText(),r.toString());
        assertEquals("MODEL_TIMEOUT",r.at("/modelDiagnostics/failureReasonCode").asText(),r.toString());
        assertTrue(r.path("missingEvidence").toString().contains("超时"));
        var saved=host.review("owner-a",r.path("reportId").asText(),r.path("reportRevision").asInt(),"说明未完成，事实已核验");
        assertEquals("PARTIAL",saved.path("status").asText());assertEquals(1,calls.get());
    }
    @Test void unknownModelFactRejected() throws Exception {
        unknownRef=true;var report=prepare();assertEquals("PARTIAL",report.path("status").asText());
        assertEquals("UNKNOWN_FACT_REF",report.at("/modelDiagnostics/failureReasonCode").asText(),report.toString());
        assertEquals(1,calls.get());
    }
    @Test void factsCanBeReadButNotReviewedWhileExplanationIsRunning() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Path store=files.resolve("pending");
        try(var pending=new ServicePreparation("owner-a",new EmployeeAgentPreparationAccess(session),mapping::get,(g,ids,c)->{
                entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                return List.of("/customer/totalAssets");
            },store,Map.of());var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            long generation=pending.select("owner-a","客户甲","核实资金");
            var future=executor.submit(()->pending.prepare("owner-a",generation));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                String id;
                try(var paths=Files.list(store)) {
                    id=paths.filter(Files::isDirectory).findFirst().orElseThrow().getFileName().toString();
                }
                var partial=pending.read("owner-a",id);
                assertEquals(1000,partial.at("/facts/customer/totalAssets").asInt());
                assertEquals("RUNNING",partial.path("explanationStatus").asText());
                int currentRevision=partial.path("reportRevision").asInt();
                assertThrows(IllegalStateException.class,()->pending.review("owner-a",id,currentRevision,"不能提前保存"));
            } finally { release.countDown(); }
            var done=future.get(5,TimeUnit.SECONDS);
            var saved=pending.review("owner-a",done.path("reportId").asText(),done.path("reportRevision").asInt(),"完成后复核");
            assertEquals("完成后复核",saved.path("reviewOpinion").asText());assertEquals(1,calls.get());
        }
    }
    @Test void missingBusinessDateIsNotReplacedByReceiptTime() throws Exception {
        missingDate=true;var r=prepare();assertEquals("",r.path("dataAsOf").asText());assertEquals("PARTIAL",r.path("status").asText());
    }
    @Test void wrongCustomerReturnedNeverEntersReport() throws Exception {
        returnedCustomer="syn-b";var r=prepare();assertEquals("FAILED",r.path("status").asText());assertFalse(r.has("facts"));
    }
    @Test void customerSwitchDiscardsLateResultWithAuditAndNoExplanation() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        beforeResponse=()->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}};
        long first=host.select("owner-a","客户甲","回访");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> old=executor.submit(()->{assertThrows(SecurityException.class,()->host.prepare("owner-a",first));});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            long next=host.select("owner-a","客户乙","资金核实");release.countDown();old.get(5,TimeUnit.SECONDS);
            beforeResponse=()->{};var r=host.prepare("owner-a",next);
            assertEquals(2000,r.at("/facts/customer/totalAssets").asInt());assertEquals(1,models.get());
        }
        try(var paths=Files.walk(files)) {
            assertEquals(1,paths.filter(Files::isRegularFile).filter(p->{try{return Files.readString(p).contains("LATE_RESULT_DISCARDED");}catch(Exception e){throw new RuntimeException(e);}}).count());
        }
    }
    @Test void switchingPreventsSavingOldDraft() throws Exception {
        var r=prepare();host.select("owner-a","客户乙","回访");
        assertThrows(SecurityException.class,()->host.review("owner-a",r.path("reportId").asText(),1,"旧稿"));
    }
    @Test void readAndSaveRecheckOwnerAndRevocation() throws Exception {
        var r=prepare();String id=r.path("reportId").asText();
        assertThrows(SecurityException.class,()->host.read("owner-b",id));
        authority.set(null);assertThrows(SecurityException.class,()->host.read("owner-a",id));
        assertThrows(SecurityException.class,()->host.review("owner-a",id,1,"复核"));assertEquals(1,calls.get());
    }
    @Test void repeatedPrepareCannotDispatchTwice() throws Exception {
        long g=host.select("owner-a","客户甲","回访");host.prepare("owner-a",g);
        assertThrows(IllegalStateException.class,()->host.prepare("owner-a",g));assertEquals(1,calls.get());
    }
    @Test void mappingChangeDuringQueryDiscardsFacts() throws Exception {
        beforeResponse=()->mapping.set(new ServicePreparation.Mapping("mapping-v2",Map.of("客户甲","syn-b")));
        assertThrows(SecurityException.class,this::prepare);assertEquals(0,models.get());
    }
    @Test void closeDuringQueryDoesNotResurrectSession() throws Exception {
        beforeResponse=host::close;assertThrows(SecurityException.class,this::prepare);
        assertThrows(SecurityException.class,()->host.customers("owner-a"));assertEquals(0,models.get());
    }
    @Test void reportFileSurvivesHostRestartWithoutReexecution() throws Exception {
        var r=prepare();String id=r.path("reportId").asText();
        try(var reopened=new ServicePreparation("owner-a",new EmployeeAgentPreparationAccess(session),mapping::get,(g,i,c)->{throw new AssertionError("Replay must not call model");},files,Map.of())) {
            assertEquals(HarnessJson.mapper().readTree(r.toString()),reopened.read("owner-a",id));assertEquals(1,calls.get());
            var reviewed=reopened.review("owner-a",id,r.path("reportRevision").asInt(),"重启后完成复核");
            assertEquals("REVIEWED",reviewed.path("reviewStatus").asText());
            assertEquals(List.of(1,2,3,4),reopened.versions("owner-a",id));
        }
    }
    @Test void reportPathCannotEscapePrivateStore() {
        assertThrows(IllegalArgumentException.class,()->host.read("owner-a","../another-owner"));
    }
    @Test void eligibleProductsCannotBeExplainedAsIneligible() throws Exception {
        var facts=HarnessJson.mapper().readTree("""
                {"productReview":{"eligibleProducts":[{"name":"稳健产品"}]}}
                """);
        assertThrows(IllegalArgumentException.class,()->ServicePreparation.validateProductSemantics(
                "当前没有同时满足条件的合格候选",facts));
        assertDoesNotThrow(()->ServicePreparation.validateProductSemantics(
                "当前有一个待经理复核的合格候选",facts));
    }
    @Test void nativeOpenJiuwenModelSelectsOnlyExistingFactReferences() throws Exception {
        var modelCalls=new AtomicInteger();
        server.createContext("/v1/chat/completions",x->{
            modelCalls.incrementAndGet();var input=HarnessJson.mapper().readTree(x.getRequestBody());
            assertTrue(input.path("tools").isMissingNode()||input.path("tools").isEmpty());
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(Map.of("id","synthetic-model-result","model","synthetic-model",
                "object","chat.completion","created",1,"choices",List.of(Map.of("index",0,"finish_reason","stop",
                "message",Map.of("role","assistant","content","[\"/customer/totalAssets\"]"))),
                "usage",Map.of("prompt_tokens",10,"completion_tokens",5,"total_tokens",15)));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
        });
        try(var nativeHost=new ServicePreparation("owner-a",new EmployeeAgentPreparationAccess(session),mapping::get,
                ServicePreparationDemo.nativeExplanation("synthetic-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-model"),
                files.resolve("native"),Map.of("model","synthetic-model"))) {
            var r=nativeHost.prepare("owner-a",nativeHost.select("owner-a","客户甲","核实资金"));
            assertEquals("READY",r.path("explanationStatus").asText(),r.toString());
            assertEquals("/customer/totalAssets",r.at("/modelSuggestion/communicationFocus/0/factRefs/0").asText());
            assertEquals(1,modelCalls.get());assertEquals(1,calls.get());
        } finally { com.openjiuwen.core.runner.Runner.stop(); }
    }
    @Test void installedMethodContentDrivesStructuredModelReportAndIsLocked() throws Exception {
        Path skill=files.resolve("SKILL.md");
        Files.writeString(skill,"# 独特服务准备方法\n必须先核对事实日期，再形成沟通建议。\n");
        byte[] skillBytes=Files.readAllBytes(skill);
        String digest="sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(skillBytes));
        var method=InstalledPreparationMethod.load("customer-manager-service-preparation","0.1.0",skill,digest,"sha256:lock-1");
        var modelCalls=new AtomicInteger();
        server.createContext("/v1/chat/completions",x->{
            modelCalls.incrementAndGet();String request=new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(request.contains("独特服务准备方法"));assertTrue(request.contains(digest));assertTrue(request.contains("核实资金"));
            String generated=HarnessJson.mapper().writeValueAsString(Map.of(
                "communicationFocus",List.of(Map.of("text","结合已核实资产事实准备沟通","factRefs",List.of("/customer/totalAssets"))),
                "preparationAdvice",List.of(Map.of("text","先向客户确认当前服务目标","factRefs",List.of("/customer/totalAssets"))),
                "pendingItems",List.of("确认缺失资料"),"reviewChecklist",List.of("核对来源与业务日期")));
            byte[] bytes=HarnessJson.mapper().writeValueAsBytes(Map.of("id","model-report","model","synthetic-model",
                "object","chat.completion","created",1,"choices",List.of(Map.of("index",0,"finish_reason","stop",
                "message",Map.of("role","assistant","content",generated))),
                "usage",Map.of("prompt_tokens",50,"completion_tokens",20,"total_tokens",70)));
            x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);
            x.getResponseBody().write(bytes);x.close();
        });
        try(var methodHost=new ServicePreparation("owner-a",new EmployeeAgentPreparationAccess(session),mapping::get,
                ServicePreparationDemo.nativeReportGenerator("synthetic-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-model"),
                files.resolve("method-driven"),Map.of("model","synthetic-model"),method)) {
            var report=methodHost.prepare("owner-a",methodHost.select("owner-a","客户甲","核实资金"));
            assertEquals("READY",report.path("explanationStatus").asText(),report.toString());
            assertEquals(digest,report.at("/method/digest").asText());
            assertEquals("sha256:lock-1",report.at("/method/lockDigest").asText());
            assertEquals("/customer/totalAssets",report.at("/modelSuggestion/preparationAdvice/0/factRefs/0").asText());
            assertEquals(1,modelCalls.get());
        } finally { com.openjiuwen.core.runner.Runner.stop(); }
        assertThrows(SecurityException.class,()->InstalledPreparationMethod.load("method","1",skill,"sha256:wrong","sha256:lock"));
    }
}
