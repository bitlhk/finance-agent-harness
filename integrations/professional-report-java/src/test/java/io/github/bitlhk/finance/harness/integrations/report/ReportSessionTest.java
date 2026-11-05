package io.github.bitlhk.finance.harness.integrations.report;

import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.report.ReportMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportSessionTest {
    @TempDir Path temp;
    static final ObjectMapper JSON=ReportMapper.json();
    JsonNode body(Map<String,Object> value){return JSON.valueToTree(value);}
    @Test void actualRunNotesAndRegenerationKeepOriginalAndHistorySeparate() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context)) {
            assertEquals("READY_NOT_EXECUTED",s.bootstrap().get("status"));
            var first=s.call("generate",body(Map.of("query","生成当前客户画像")));assertEquals("SUCCEEDED",first.get("status"));
            String id=first.get("reportId").toString();var original=JSON.valueToTree(first.get("report"));
            var noted=s.call("note",body(Map.of("reportId",id,"revision",1,"text","客户经理人工补充，待核实。")));
            assertEquals(2,JSON.valueToTree(noted.get("report")).path("revision").asInt());assertEquals("UNREVIEWED",JSON.valueToTree(noted.get("report")).path("reviewStatus").asText());
            var retained=s.call("result",body(Map.of("reportId",id,"revision",1)));assertEquals(original,JSON.valueToTree(retained.get("report")));assertTrue(original.path("notes").isEmpty());
            assertEquals(List.of(1,2),s.call("versions",body(Map.of("reportId",id))).get("items"));
            var second=s.call("generate",body(Map.of("query","重新生成画像","previousReportId",id,"previousRevision",2)));assertEquals("SUCCEEDED",second.get("status"));assertNotEquals(id,second.get("reportId"));
            var latest=s.call("result",body(Map.of("reportId",second.get("reportId"),"revision",1)));assertEquals("AVAILABLE",JSON.valueToTree(latest.get("lineage")).path("previousState").asText());
            assertEquals(2,((List<?>)s.call("history",body(Map.of())).get("items")).size());
            JsonNode report=JSON.valueToTree(second.get("report")),a=report.path("artifacts").get(0);
            var file=s.call("artifact",body(Map.of("reportId",second.get("reportId"),"revision",1,"artifactId",a.path("id").asText())));assertEquals(a,JSON.valueToTree(file.get("artifact")));assertFalse(file.get("contentBase64").toString().isBlank());
        }
    }
    @Test void failedNewGenerationDoesNotReturnPreviousSuccess() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context)) {
            var first=s.call("generate",body(Map.of("query","画像")));assertEquals("SUCCEEDED",first.get("status"));
            f.failSource.set(true);var failed=s.call("generate",body(Map.of("query","再试一次")));assertEquals("FAILED",failed.get("status"));assertFalse(failed.containsKey("report"));assertEquals("FAILED",s.status().get("status"));
            f.failSource.set(false);assertEquals(1,((List<?>)s.call("history",body(Map.of())).get("items")).size());
        }
    }
    @Test void inventedModelCitationCannotBecomePublishedReport() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context)) {
            f.badReference.set(true);assertEquals("FAILED",s.call("generate",body(Map.of("query","画像"))).get("status"));assertTrue(((List<?>)s.call("history",body(Map.of())).get("items")).isEmpty());
        }
    }
    @Test void concurrentCancelStopsSourceAndRejectsLatePublication() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context);var executor=Executors.newSingleThreadExecutor()) {
            f.running.set(true);var pending=executor.submit(()->s.call("generate",body(Map.of("query","画像"))));
            long deadline=System.nanoTime()+Duration.ofSeconds(3).toNanos();while(f.requests.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(5);assertFalse(f.requests.isEmpty());
            var cancelled=s.call("cancel",body(Map.of()));assertEquals("CANCELLED",cancelled.get("status"));assertEquals(true,cancelled.get("workerStopped"));
            assertEquals("CANCELLED",pending.get(10,TimeUnit.SECONDS).get("status"));assertTrue(f.stopped.get());assertTrue(((List<?>)s.call("history",body(Map.of())).get("items")).isEmpty());
        }
    }
    @Test void currentAuthorityProtectsStatusHistoryAndArtifactReads() throws Exception {
        try(var f=new ReportNativeMethodTest.Fixture(temp);var s=new ReportSession(f.context)) {
            var first=s.call("generate",body(Map.of("query","画像")));assertEquals("SUCCEEDED",first.get("status"));f.revoke();
            assertThrows(SecurityException.class,s::status);assertThrows(SecurityException.class,()->s.call("history",body(Map.of())));assertThrows(SecurityException.class,()->s.call("result",body(Map.of("reportId",first.get("reportId"),"revision",1))));
        }
    }
    @Test void finiteActionsAndLockedMethodRejectTamperingBeforeRuntime() throws Exception {
        for(var value:List.of(Map.<String,Object>of("query","x","customerRef","other"),Map.<String,Object>of("query","x","endpoint","http://elsewhere"),Map.<String,Object>of("query","x","previousRevision",1)))assertThrows(IllegalArgumentException.class,()->ReportActions.validate("generate",body(value)));
        assertThrows(IllegalArgumentException.class,()->ReportActions.validate("artifact",body(Map.of("reportId","../x","revision",1,"artifactId","a"))));
        try(var f=new ReportNativeMethodTest.Fixture(temp)) {Files.writeString(f.methodFile,"tampered method");assertThrows(SecurityException.class,()->new ReportSession(f.context));assertEquals(0,f.calls.get());}
    }
}
