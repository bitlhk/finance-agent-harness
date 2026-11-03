package io.github.bitlhk.finance.harness.providers.marketrisk;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class MarketRiskCapitalProviderTest {
    static final String HASH="sha256:"+"a".repeat(64),REV="b".repeat(40);
    static final Instant NOW=Instant.parse("2026-11-03T05:00:00Z");
    HttpServer server;
    AtomicInteger requests=new AtomicInteger();
    AtomicReference<ManagedProviderFactoryV01.SourceSession> session=new AtomicReference<>();
    AtomicReference<String> response=new AtomicReference<>();
    AtomicInteger status=new AtomicInteger(200);
    AtomicInteger delay=new AtomicInteger();
    List<ManagedProviderFactoryV01.HttpAttempt> attempts=new ArrayList<>();
    Runnable received=()->{};
    @BeforeEach void start() throws Exception {
        session.set(authority("owner-a",NOW.plusSeconds(60)));
        response.set(fixture());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/market-risk-capital/query",exchange->{
            requests.incrementAndGet();
            var input=HarnessJson.mapper().readTree(exchange.getRequestBody().readAllBytes());
            assertEquals("task-a",input.path("taskRef").asText());
            assertEquals("snapshot-a",input.path("snapshotRef").asText());
            assertEquals("Bearer private-test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            received.run();
            try { Thread.sleep(delay.get()); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            try { exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes); }
            catch(java.io.IOException closed) { /* The timeout case cancels its own connection. */ }
            finally { exchange.close(); }
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    ManagedProviderFactoryV01.SourceSession authority(String owner,Instant expiry) {
        return new ManagedProviderFactoryV01.SourceSession("private-test-token","portfolio-a",owner,"session-a",expiry);
    }
    CapabilityRequestV1 request(Map<String,Object> input) {
        var context=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"request-a","trace-a","owner-a",
                FinanceContextV1.PrincipalType.EMPLOYEE,"fixture","MARKET_RISK_CAPITAL","portfolio-a","session-a",
                FinanceContextV1.AuthenticationLevel.STRONG,Set.of(MarketRiskCapitalProvider.SCOPE));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,MarketRiskCapitalProvider.CAPABILITY,
                CapabilityRequestV1.Operation.QUERY,context,input,null,null,Map.of(),null);
    }
    MarketRiskCapitalProvider provider(Duration timeout) {
        var binding=new MarketRiskCapitalFactoryV01.Binding("owner-a","portfolio-a","session-a","task-a","snapshot-a",
                HASH,HASH,REV,HASH,"2026.09-demo-v1","0.1.0");
        var connection=new ManagedProviderFactoryV01.Connection(MarketRiskCapitalFactoryV01.KIND,
                URI.create("http://127.0.0.1:"+server.getAddress().getPort()),timeout,session::get,()->null,attempts::add);
        return new MarketRiskCapitalProvider(connection,new MarketRiskCapitalFactoryV01.Config("market-risk-capital-bindings.v1",List.of(binding)),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    String fixture() {
        return """
          {"schemaVersion":"finance-original-market-risk-capital-result.v1","status":"COMPLETED","requestId":"request-a",
          "values":{"interest_rate":0,"fx":0,"equity":0,"commodity":0,"total":0},"param_version":"2026.09-demo-v1","engine_version":"0.1.0",
          "detail":[{"risk_category":"利率","capital":0,"note":"原明细"},{"risk_category":"汇率","capital":0,"note":"原明细"},
          {"risk_category":"股票","capital":0,"note":"原明细"},{"risk_category":"商品","capital":0,"note":"原明细"}],"ir_breakdown":{"zones":[]},
          "source":{"principalId":"owner-a","subjectRef":"portfolio-a","taskRef":"task-a","sessionRef":"session-a","snapshotRef":"snapshot-a",
          "snapshotDigest":"HASH","asOf":null,"kind":"CONTROLLED_FIXTURE"},"calculatedAt":"2026-11-03T05:00:00Z",
          "toolReleaseDigest":"HASH","upstreamRevision":"REV","parametersDigest":"HASH","originalFunction":"mktrisk_engine.standard.compute_standard_capital"}
          """.replace("HASH",HASH).replace("REV",REV);
    }
    @Test void validZeroAndMissingDateHaveExplicitMeaning() {
        var result=provider(Duration.ofSeconds(2)).query(request(Map.of()));
        assertEquals(CapabilityResultV1.Status.COMPLETED,result.status());
        var tree=HarnessJson.mapper().valueToTree(result.data());
        assertEquals(0,tree.path("capital").path("values").path("total").asDouble());
        assertTrue(tree.path("capital").path("source").get("asOf").isNull());
        assertEquals(List.of("SOURCE_AS_OF_MISSING"),result.missingInputs());
        assertEquals("CALCULATION_TIME_NOT_SOURCE_DATE",result.data().get("asOfSemantics"));
        assertEquals(1,requests.get());assertEquals(200,attempts.getFirst().status());
    }
    @Test void missingExpiredOrWrongOwnerFailsBeforeHttp() {
        var provider=provider(Duration.ofSeconds(2));
        for(var authority:Arrays.asList(null,authority("owner-a",NOW),authority("other",NOW.plusSeconds(60)))) {
            session.set(authority);
            var result=provider.query(request(Map.of()));assertEquals(CapabilityResultV1.Status.FAILED,result.status());
            assertTrue(result.data().isEmpty());assertEquals("ACCESS_DENIED",result.error().sourceCode());
        }
        assertEquals(0,requests.get());
    }
    @Test void modelCannotSelectIdentityOrOriginalInputFiles() {
        for(String selector:List.of("taskRef","subjectRef","snapshotRef","positions","configPath","module")) {
            var result=provider(Duration.ofSeconds(2)).query(request(Map.of(selector,"forged")));
            assertEquals("READ_ONLY_INPUT_REQUIRED",result.error().sourceCode());
        }
        assertEquals(0,requests.get());
    }
    @Test void revocationWhileHttpIsInFlightClosesDisclosure() {
        received=()->session.set(null);
        var result=provider(Duration.ofSeconds(2)).query(request(Map.of()));
        assertEquals("ACCESS_DENIED",result.error().sourceCode());assertTrue(result.data().isEmpty());
    }
    @Test void status503AndTimeoutAreNotCompletedFacts() {
        status.set(503);response.set("{\"private\":\"must never disclose\"}");
        var result=provider(Duration.ofSeconds(2)).query(request(Map.of()));
        assertEquals("ORIGINAL_TOOL_HTTP_503",result.error().sourceCode());assertTrue(result.data().isEmpty());
        status.set(200);delay.set(250);
        var timed=provider(Duration.ofMillis(30)).query(request(Map.of()));
        assertEquals(CapabilityResultV1.Status.FAILED,timed.status());assertTrue(timed.data().isEmpty());
        assertTrue(Set.of("ORIGINAL_TOOL_TIMEOUT","ORIGINAL_TOOL_UNAVAILABLE").contains(timed.error().sourceCode()));
    }
    @Test void taskMismatchDuplicateKeysAndNonFiniteResultsFail() {
        for(String bad:List.of(fixture().replace("\"task-a\"","\"task-b\""),
                fixture().replace("\"status\":\"COMPLETED\"","\"status\":\"COMPLETED\",\"status\":\"COMPLETED\""),
                fixture().replace("\"total\":0","\"total\":1e999"))) {
            response.set(bad);var result=provider(Duration.ofSeconds(2)).query(request(Map.of()));
            assertEquals(CapabilityResultV1.Status.FAILED,result.status());assertTrue(result.data().isEmpty());
            assertFalse(result.toString().contains("private-test-token"));
        }
    }
    @Test void independentFactoryIsDiscoverableWithoutReadingConfig() {
        assertTrue(ServiceLoader.load(ManagedProviderFactoryV01.class).stream().anyMatch(p->p.type()==MarketRiskCapitalFactoryV01.class));
    }
    @Test void factoryFreezesExactConfigurationAndUsesExistingSpiDigestFormat(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var binding=new MarketRiskCapitalFactoryV01.Binding("owner-a","portfolio-a","session-a","task-a","snapshot-a",HASH,HASH,REV,HASH,"2026.09-demo-v1","0.1.0");
        byte[] bytes=HarnessJson.mapper().writeValueAsBytes(new MarketRiskCapitalFactoryV01.Config("market-risk-capital-bindings.v1",List.of(binding)));
        var file=directory.resolve("bindings.json");java.nio.file.Files.write(file,bytes);
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var factory=new MarketRiskCapitalFactoryV01(file.toString(),"sha256:"+digest);
        assertEquals(Map.of("binding-file-sha256",digest),factory.configurationDigests());
        assertEquals(Map.of("binding-file-sha256",digest),new MarketRiskCapitalFactoryV01(file.toString(),digest).configurationDigests());
        java.nio.file.Files.writeString(file,"{}");
        assertEquals(Map.of("binding-file-sha256",digest),factory.configurationDigests());
        assertThrows(IllegalArgumentException.class,()->new MarketRiskCapitalFactoryV01(file.toString(),HASH).configurationDigests());
    }
}
