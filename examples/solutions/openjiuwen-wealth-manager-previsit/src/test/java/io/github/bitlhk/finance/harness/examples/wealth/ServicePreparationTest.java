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
            var context=Map.of("schema","ea.wealth-previsit-context.v1","status","ready",
                "customer",Map.of("customerId",returnedCustomer==null?customer:returnedCustomer,"totalAssets",customer.equals("syn-a")?1000:2000),
                "dataAsOf",missingDate?"":"2026-10-01T00:00:00Z",
                "readiness",Map.of("status","READY"));
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
        host=new ServicePreparation("owner-a",session,mapping::get,(goal,ids,context)->{
            models.incrementAndGet();if(explainFail)throw new TimeoutException("synthetic failure");
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
        assertEquals("2026-10-01T00:00:00Z",r.path("dataAsOf").asText());
        assertFalse(r.path("execution").path("evidence").isEmpty());
        var saved=host.review("owner-a",id,1,"已核实事实，准备回访；未写 CRM");
        assertEquals("syn-manager",saved.path("reviewerRef").asText());
        assertEquals(2,saved.path("reportRevision").asInt());
        assertEquals(1,saved.path("reviewHistory").size());
        assertEquals(saved,host.read("owner-a",id));host.read("owner-a",id);
        assertEquals(1,calls.get());assertEquals(1,models.get());
        assertThrows(IllegalStateException.class,()->host.review("owner-a",id,1,"重复意见"));
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
        var saved=host.review("owner-a",r.path("reportId").asText(),1,"说明未完成，事实已核验");
        assertEquals("PARTIAL",saved.path("status").asText());assertEquals(1,calls.get());
    }
    @Test void unknownModelFactRejected() throws Exception {
        unknownRef=true;assertEquals("PARTIAL",prepare().path("status").asText());assertEquals(1,calls.get());
    }
    @Test void factsCanBeReadButNotReviewedWhileExplanationIsRunning() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        Path store=files.resolve("pending");
        try(var pending=new ServicePreparation("owner-a",session,mapping::get,(g,ids,c)->{
                entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
                return List.of("/customer/totalAssets");
            },store,Map.of());var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            long generation=pending.select("owner-a","客户甲","核实资金");
            var future=executor.submit(()->pending.prepare("owner-a",generation));
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                String id;
                try(var paths=Files.list(store)) {
                    id=paths.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow()
                        .getFileName().toString().replace(".json","");
                }
                var partial=pending.read("owner-a",id);
                assertEquals(1000,partial.at("/facts/customer/totalAssets").asInt());
                assertEquals("RUNNING",partial.path("explanationStatus").asText());
                assertThrows(IllegalStateException.class,()->pending.review("owner-a",id,1,"不能提前保存"));
            } finally { release.countDown(); }
            var done=future.get(5,TimeUnit.SECONDS);
            var saved=pending.review("owner-a",done.path("reportId").asText(),1,"完成后复核");
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
        try(var paths=Files.list(files)) {
            assertEquals(1,paths.filter(p->{try{return Files.readString(p).contains("LATE_RESULT_DISCARDED");}catch(Exception e){throw new RuntimeException(e);}}).count());
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
        try(var reopened=new ServicePreparation("owner-a",session,mapping::get,(g,i,c)->{throw new AssertionError("Replay must not call model");},files,Map.of())) {
            assertEquals(HarnessJson.mapper().readTree(r.toString()),reopened.read("owner-a",id));assertEquals(1,calls.get());
            assertThrows(SecurityException.class,()->reopened.review("owner-a",id,1,"必须重新选择当前任务"));
        }
    }
    @Test void reportPathCannotEscapePrivateStore() {
        assertThrows(IllegalArgumentException.class,()->host.read("owner-a","../another-owner"));
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
        try(var nativeHost=new ServicePreparation("owner-a",session,mapping::get,
                ServicePreparationDemo.nativeExplanation("synthetic-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-model"),
                files.resolve("native"),Map.of("model","synthetic-model"))) {
            var r=nativeHost.prepare("owner-a",nativeHost.select("owner-a","客户甲","核实资金"));
            assertEquals("READY",r.path("explanationStatus").asText(),r.toString());
            assertEquals("/customer/totalAssets",r.path("focusFactRefs").get(0).asText());
            assertEquals(1,modelCalls.get());assertEquals(1,calls.get());
        } finally { com.openjiuwen.core.runner.Runner.stop(); }
    }
}
