package io.github.bitlhk.finance.harness.creditcard;
import java.util.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenefitsEnginesTest {
    @Test void oneEngineFailurePreservesSuccessfulRankingAndAllReceipts() throws Exception {
        var pool=BenefitsValidator.context(BenefitsValidatorTest.view(),"推荐",Set.of(),3);
        var raw=CreditContracts.JSON.createObjectNode();raw.putArray("items").addObject().put("benefit_id","benefit-2").put("reason","合成匹配");
        var receipts=new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
        var engines=new BenefitsEngines(Map.of("llm",input->raw,"onerec",input->{throw new IllegalStateException();}));
        var result=engines.run(pool,CreditStateStoreTest.turn("session-engines","推荐"),()->{},receipts::add);
        assertEquals("complete",result.path("llm").path("status").asText());assertEquals("failed",result.path("onerec").path("status").asText());
        assertEquals(2,receipts.stream().filter(x->!"running".equals(x.path("status").asText())).count());
    }
    @Test void deadlineRetainsOnlyTheRankingVerifiedBeforeItAndNeverWaitsForLateRanker() throws Exception {
        var original=CreditStateStoreTest.turn("session-deadline","推荐");
        var turn=new CreditContracts.Turn(original.operationKey(),original.sessionRef(),original.scene(),original.question(),original.binding(),java.time.Instant.now().plusMillis(600));
        var pool=BenefitsValidator.context(BenefitsValidatorTest.view(),"推荐",Set.of(),3);
        var raw=CreditContracts.JSON.createObjectNode();raw.putArray("items").addObject().put("benefit_id","benefit-2").put("reason","已验证");
        var retained=new java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.node.ObjectNode>();
        var started=new java.util.concurrent.CountDownLatch(1);
        var engines=new BenefitsEngines(Map.of("llm",input->{started.await();return raw;},"onerec",input->{started.countDown();Thread.sleep(10000);return raw;}));
        assertThrows(java.util.concurrent.TimeoutException.class,()->engines.run(pool,turn,turn::check,event->{},retained::set));
        assertNotNull(retained.get());assertTrue(retained.get().has("llm"));assertFalse(retained.get().has("onerec"));
    }
    @Test void workerRedirectNeverCarriesCredentialToTarget() throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rank",e->{e.getRequestBody().readAllBytes();e.getResponseHeaders().set("Location","/target");e.sendResponseHeaders(302,-1);e.close();});
        server.createContext("/target",e->{calls.incrementAndGet();e.sendResponseHeaders(200,-1);e.close();});server.start();
        try {
            var worker=BenefitsEngines.worker(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"synthetic-worker",CreditStateStoreTest.turn("session-worker","推荐"),()->{});
            assertThrows(IllegalStateException.class,()->worker.rank(CreditContracts.JSON.createObjectNode()));assertEquals(0,calls.get());
        }finally{server.stop(0);}
    }
}
