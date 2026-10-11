package io.github.bitlhk.finance.harness.integrations.fraud;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.providers.fraud.FraudMiningProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FraudVerificationTaskTest {
    @TempDir Path temp;
    HttpServer server;
    final java.util.concurrent.CountDownLatch slowEntered=new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.CountDownLatch releaseSlow=new java.util.concurrent.CountDownLatch(1);
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/samples",exchange->{
            if(exchange.getRequestURI().getRawQuery().contains("keyword=SLOW-CINO")){
                slowEntered.countDown();
                try{if(!releaseSlow.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            }
            byte[] body="""
                {"code":200,"data":{"total":1,"page":1,"page_size":100,"items":[
                {"id":7,"cino":"%s","label":1,"match_status":"matched","match_confidence":0.91,
                "prediction_result":"fraud","prediction_score":0.78,"created_at":"2026-10-01T00:00:00"}]}}
                """.formatted(exchange.getRequestURI().getRawQuery().contains("keyword=SLOW-CINO")?"SLOW-CINO":"SYN-CINO-001").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    FraudVerificationTask task()throws Exception{
        var provider=new FraudMiningProvider(new FraudMiningProvider.Config(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2)));
        var mapping=(java.util.function.Supplier<FraudSubjectMapping>)()->new FraudSubjectMapping("owner","rev-1",Map.of("合成核验对象甲","SYN-CINO-001"));
        var methodFile=temp.resolve("SKILL.md");Files.writeString(methodFile,"只读核验方法");
        var method=InstalledPreparationMethod.load("fraud-risk-verification","0.1.0",methodFile,digest(methodFile),"sha256:lock");
        return new FraudVerificationTask("owner",provider,mapping,(m,p,refs,facts)->{
            var value=HarnessJson.mapper().createObjectNode();
            var summary=value.putArray("summary").addObject();summary.put("text","命中来源中的已知记录，需人工核验，不代表诈骗认定。");summary.putArray("factRefs").add("/verificationStatus");
            var evidence=value.putArray("evidenceInterpretation").addObject();evidence.put("text","来源查询已完成并保留记录标签。");evidence.putArray("factRefs").add("/queryComplete");
            value.putArray("pendingItems").add("核对对象与来源记录");value.putArray("reviewChecklist").add("不得据此自动放行或阻断");return value;
        },method,temp.resolve("reports"),Map.of("provider","test"));
    }
    @Test void immutableReviewAndReplayDoNotExecute()throws Exception{
        try(var task=task()){
            long generation=task.select("owner","合成核验对象甲","核验已知涉诈记录");
            var report=task.verify("owner",generation,FraudVerificationTask.Listener.NONE);
            assertEquals("KNOWN_RISK_MATCH",report.path("verificationStatus").asText());assertTrue(report.path("queryComplete").asBoolean());
            int initial=report.path("reportRevision").asInt();var reviewed=task.review("owner",report.path("reportId").asText(),initial,"已核对，需继续人工调查");
            assertEquals(initial+1,reviewed.path("reportRevision").asInt());assertEquals("PENDING",task.readVersion("owner",report.path("reportId").asText(),initial).path("reviewStatus").asText());
            assertEquals(List.of(initial,initial+1),task.versions("owner",report.path("reportId").asText()));
            var replay=task.replay("owner",report.path("reportId").asText(),initial);
            assertEquals(0,replay.path("replayEvidence").path("businessProviderCalls").asInt());assertEquals(0,replay.path("replayEvidence").path("modelGenerationCalls").asInt());
        }
    }
    @Test void authorityRevisionChangeRejectsReadAndLateCommit()throws Exception{
        var provider=new FraudMiningProvider(new FraudMiningProvider.Config(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2)));
        var revision=new java.util.concurrent.atomic.AtomicReference<>("rev-1");
        var methodFile=temp.resolve("M.md");Files.writeString(methodFile,"方法");var method=InstalledPreparationMethod.load("m","1",methodFile,digest(methodFile),"sha256:l");
        try(var task=new FraudVerificationTask("owner",provider,()->new FraudSubjectMapping("owner",revision.get(),Map.of("对象","SYN-CINO-001")),
                (m,p,r,f)->{revision.set("revoked");return HarnessJson.mapper().createObjectNode();},method,temp.resolve("r"),Map.of())){
            long generation=task.select("owner","对象","核验");assertThrows(SecurityException.class,()->task.verify("owner",generation,FraudVerificationTask.Listener.NONE));
        }
    }
    @Test void historicalVersionTamperingIsDetected()throws Exception{
        try(var task=task()){
            long generation=task.select("owner","合成核验对象甲","核验");
            var report=task.verify("owner",generation,FraudVerificationTask.Listener.NONE);
            Path version=temp.resolve("reports").resolve(report.path("reportId").asText())
                    .resolve(String.format("v%06d.json",report.path("reportRevision").asInt()));
            Files.writeString(version,"{}",StandardOpenOption.TRUNCATE_EXISTING);
            assertThrows(SecurityException.class,()->task.readVersion("owner",report.path("reportId").asText(),report.path("reportRevision").asInt()));
        }
    }
    @Test void objectSwitchRejectsLateResultAndDoesNotPromoteDiscardedReport()throws Exception{
        var provider=new FraudMiningProvider(new FraudMiningProvider.Config(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(5)));
        var methodFile=temp.resolve("switch.md");Files.writeString(methodFile,"方法");
        var method=InstalledPreparationMethod.load("m","1",methodFile,digest(methodFile),"sha256:l");
        try(var task=new FraudVerificationTask("owner",provider,
                ()->new FraudSubjectMapping("owner","rev-1",Map.of("慢对象","SLOW-CINO","新对象","SYN-CINO-001")),
                (m,p,r,f)->HarnessJson.mapper().createObjectNode(),method,temp.resolve("switch-reports"),Map.of())){
            long old=task.select("owner","慢对象","核验旧对象");
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                var future=executor.submit(()->task.verify("owner",old,FraudVerificationTask.Listener.NONE));
                assertTrue(slowEntered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                task.select("owner","新对象","核验新对象");releaseSlow.countDown();
                var failure=assertThrows(java.util.concurrent.ExecutionException.class,future::get);
                assertInstanceOf(SecurityException.class,failure.getCause());
                assertTrue(task.latest("owner").isEmpty());
            }
        }finally{releaseSlow.countDown();}
    }
    private static String digest(Path file)throws Exception{return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
}
