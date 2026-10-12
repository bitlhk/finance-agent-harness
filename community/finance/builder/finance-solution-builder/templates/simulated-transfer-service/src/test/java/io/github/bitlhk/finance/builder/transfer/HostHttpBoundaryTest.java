package io.github.bitlhk.finance.builder.transfer;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class HostHttpBoundaryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    HttpServer bankServer, hostServer;
    String origin;
    HttpClient http = HttpClient.newHttpClient();
    AtomicInteger bankCalls = new AtomicInteger();
    String csrf, cookie;
    @Before public void start() throws Exception {
        bankServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        bankServer.createContext("/", exchange -> {
            bankCalls.incrementAndGet();
            Object result = switch(exchange.getRequestURI().getPath()) {
                case "/health" -> Map.of("providerMode", "simulator", "service", "non-ai-banking");
                case "/api/v1/sessions/runtime" -> Map.of("instanceId", "test-bank");
                default -> Map.of("status", "SUCCESS", "data", Map.of("sessionId", "bank-a", "customerRef", "customer-a",
                        "identityStrength", "I2", "expiresAt", Instant.now().plusSeconds(300)));
            };
            try { TransferHost.reply(exchange, 200, result); }
            catch (Exception failure) { throw new RuntimeException(failure); }
            finally { exchange.close(); }
        });bankServer.start();
        URI bank = URI.create("http://127.0.0.1:"+bankServer.getAddress().getPort());
        Path root=temp.newFolder("candidate").toPath(),state=temp.newFolder("state").toPath(),policy=temp.getRoot().toPath().resolve("policy.json");
        HostFiles.save(root.resolve("asset.json"),Map.of("synthetic",true));
        HostFiles.save(root.resolve("service-lock.json"),Map.of("schemaVersion","simulated-transfer-candidate.v0.1","buildId","test",
                "files",Map.of("asset.json",HostFiles.digest(Files.readAllBytes(root.resolve("asset.json"))))));
        HostFiles.save(policy,Map.of("schemaVersion","simulated-transfer-access.v0.1","bankUrl",bank.toString(),"simulatorInstance","test-bank",
                "maxAmountCny","1.00","subjects",Map.of("customer-a",Map.of("enabled",true,"scopes",SimulatorAccess.SCOPES,"expiresAt",Instant.now().plusSeconds(300)))));
        hostServer=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        origin="http://127.0.0.1:"+hostServer.getAddress().getPort();
        var host=new TransferHost(root,state,new SimulatorAccess(bank,policy,"test-bank"),origin);
        hostServer.createContext("/",host::handle);hostServer.start();
    }
    @After public void stop(){if(hostServer!=null)hostServer.stop(0);if(bankServer!=null)bankServer.stop(0);}
    HttpResponse<String> post(String endpoint,String body,String requestOrigin,String token,String browserCookie) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(origin+"/api/"+endpoint))
                .header("Origin",requestOrigin).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if(token!=null)request.header("X-CSRF-Token",token);
        if(browserCookie!=null)request.header("Cookie",browserCookie);
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    void login() throws Exception {
        var response=post("login","{\"loginName\":\"synthetic\",\"loginSecret\":\"test-only\"}",origin,null,null);
        assertEquals(200,response.statusCode());
        csrf=HostFiles.JSON.readTree(response.body()).path("csrf").asText();
        cookie=response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));assertTrue(cookie.contains("SameSite=Strict"));cookie=cookie.split(";")[0];
    }
    @Test public void foreignOriginCannotEvenLogin() throws Exception {
        int before=bankCalls.get();
        assertEquals(403,post("login","{}","https://other.invalid",null,null).statusCode());
        assertEquals(before,bankCalls.get());
    }
    @Test public void cookieWithoutCsrfCannotReadOrConfirm() throws Exception {
        login();int before=bankCalls.get();
        assertEquals(403,post("choices","{}",origin,null,cookie).statusCode());
        assertEquals(403,post("confirm","{\"handle\":\"fake\",\"nonce\":\"fake\"}",origin,null,cookie).statusCode());
        assertEquals(before,bankCalls.get());
    }
    @Test public void modelStylePrincipalAndApprovalFieldsAreRejected() throws Exception {
        login();int before=bankCalls.get();
        assertEquals(400,post("choices","{\"principal\":\"customer-b\"}",origin,csrf,cookie).statusCode());
        assertEquals(400,post("confirm","{\"handle\":\"fake\",\"nonce\":\"fake\",\"amount\":2}",origin,csrf,cookie).statusCode());
        assertEquals(before,bankCalls.get());
    }
    @Test public void unknownCookieIsDeniedNotAHostFailure() throws Exception {
        int before=bankCalls.get();
        assertEquals(403,post("choices","{}",origin,"fake","fh_transfer="+UUID.randomUUID()).statusCode());
        assertEquals(before,bankCalls.get());
    }
}
