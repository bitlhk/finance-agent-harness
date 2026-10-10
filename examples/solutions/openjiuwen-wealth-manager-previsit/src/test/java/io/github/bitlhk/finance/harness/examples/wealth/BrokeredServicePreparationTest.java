package io.github.bitlhk.finance.harness.examples.wealth;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentPreparationBroker;
import java.net.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BrokeredServicePreparationTest {
    @TempDir Path reports;
    private HttpServer server;
    private final AtomicInteger calls=new AtomicInteger();
    private String revision="scope-1";
    private BrokeredServicePreparation task;
    private ServicePreparation.Explanation explanation=(goal,ids,context)->ids.stream().limit(2).toList();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String expires=Instant.now().plusSeconds(120).toString();
        server.createContext("/api/internal/harness/service-preparation/authority",exchange->{
            var input=HarnessJson.mapper().readTree(exchange.getRequestBody());
            var body=Map.ofEntries(Map.entry("ownerRef","owner"),Map.entry("sessionRef",input.path("sessionRef").asText()),
                Map.entry("principalRef","employee:1"),Map.entry("authorityRef",revision),Map.entry("expiresAt",expires),
                Map.entry("runtime",Map.of("runtimeId","finance-harness","agentId","agent-a","adoptId","lgj-test")),
                Map.entry("customers",List.of(Map.of("customerRef","CUST-000201","displayName","Synthetic A"))),
                Map.entry("capabilityIds",List.of(ServicePreparation.CAPABILITY)),Map.entry("scopes",List.of("wealth:previsit:read")),
                Map.entry("purpose","WEALTH_PREVISIT"),Map.entry("accessToken","fhp."+"a".repeat(43)));
            ServicePreparationDemo.reply(exchange,200,body);
        });
        server.createContext("/api/internal/platform-tools/mcp",exchange->{
            calls.incrementAndGet();var input=HarnessJson.mapper().readTree(exchange.getRequestBody());
            var context=Map.of("schema","ea.wealth-previsit-context.v1","status","ready",
                "customer",Map.of("customerId","CUST-000201","displayName","Synthetic A","customerStatus","active","maskedPhone","139****8201"),
                "profiles",List.of(Map.of("profileType","wealth","detail",Map.of(
                    "totalAssets",125000,
                    "riskAssessment",Map.of("riskLevel","R2"),
                    "recentEvents",List.of(Map.of("type","FOLLOW_UP_DUE"))))),
                "dataAsOf","2026-10-09T00:00:00Z","readiness",Map.of("status","READY"));
            ServicePreparationDemo.reply(exchange,200,Map.of("jsonrpc","2.0","id",input.path("id").asText(),
                "result",Map.of("content",List.of(Map.of("type","text","text","EA_WEALTH_PREVISIT_CONTEXT:"+HarnessJson.mapper().writeValueAsString(context))),
                    "_meta",Map.of("eaContextReceipt",Map.of("receiptId","receipt-1","receiptFingerprint","a".repeat(64),"createdAt","2026-10-09T00:00:00Z")))));
        });server.start();
        var broker=new EmployeeAgentPreparationBroker(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
            "owner","session",()->"synthetic-secret",Duration.ofSeconds(3));
        task=new BrokeredServicePreparation("owner",broker,(goal,ids,context)->explanation.selectFocus(goal,ids,context),reports,Map.of("mode","fixture"));
    }
    @AfterEach void stop() {if(task!=null)task.close();server.stop(0);}
    private long select() throws Exception {return task.select("owner",task.customers("owner").getFirst(),"prepare visit");}
    @Test void generateReviewReadPreservesFactsWithoutRequery() throws Exception {
        var report=task.prepare("owner",select());String id=report.path("reportId").asText();
        assertEquals("READY_FOR_REVIEW",report.path("status").asText());
        var saved=task.review("owner",id,report.path("reportRevision").asInt(),"engineering review");
        assertEquals(saved,task.read("owner",id));assertEquals(report.path("facts"),saved.path("facts"));
        assertThrows(SecurityException.class,()->task.read("another-owner",id));assertEquals(1,calls.get());
    }
    @Test void explanationFailureRetainsReviewableFacts() throws Exception {
        explanation=(goal,ids,context)->{throw new IllegalStateException("controlled model failure");};
        var report=task.prepare("owner",select());
        assertEquals("PARTIAL",report.path("status").asText());assertTrue(report.has("facts"));
        assertEquals("FAILED",report.path("explanationStatus").asText());
        var saved=task.review("owner",report.path("reportId").asText(),report.path("reportRevision").asInt(),"incomplete explanation noted");
        assertEquals("PARTIAL",saved.path("status").asText());assertEquals(1,calls.get());
    }
    @Test void authorityChangeDuringExplanationDiscardsTheReport() throws Exception {
        explanation=(goal,ids,context)->{revision="scope-2";return ids.stream().limit(2).toList();};
        long generation=select();
        assertThrows(SecurityException.class,()->task.prepare("owner",generation));
        try(var files=java.nio.file.Files.walk(reports)) {
            var record=HarnessJson.mapper().readTree(files.filter(java.nio.file.Files::isRegularFile)
                .filter(p->p.getFileName().toString().startsWith("v")).reduce((a,b)->b).orElseThrow().toFile());
            assertEquals("DISCARDED",record.path("status").asText());assertFalse(record.has("facts"));
        }
        assertEquals(1,calls.get());
    }
}
