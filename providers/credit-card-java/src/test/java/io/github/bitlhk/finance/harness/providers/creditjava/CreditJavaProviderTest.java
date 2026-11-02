package io.github.bitlhk.finance.harness.providers.creditjava;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreditJavaProviderTest {
    static final ObjectMapper JSON = new ObjectMapper();
    @Test void installedJavaRuntimeUsesActualManagedProvider() throws Exception {
        String endpoint = System.getenv("CREDIT_JAVA_INSTALLED_PROVIDER_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(endpoint != null,
                "explicit installed Java Runtime and private transport binding required");
        var source = JSON.readValue(System.getenv("CREDIT_JAVA_INSTALLED_SOURCE_BINDING"),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        String principal = (String) source.get("principal_id"), customer = (String) source.get("customer_id");
        String hostSession = "session-jvm-" + UUID.randomUUID().toString().replace("-", "");
        Instant expiry = Instant.now().plusSeconds(180);
        var authority = new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of(customer), Set.of("CREDIT_CARD_SERVICE"), Set.of(CreditJavaFactory.SCOPE),
                hostSession, "synthetic-installed-authority", expiry);
        var audits = new ArrayList<Map<String,String>>();
        var attempts = new ArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var uri = URI.create(endpoint);
        var factory = new CreditJavaFactory(uri, () -> authority,
                ignored -> new CreditJavaFactory.Binding(source, expiry), row -> { audits.add(row); return true; });
        var session = new ManagedProviderFactoryV01.SourceSession(
                Objects.requireNonNull(System.getenv("CREDIT_JAVA_INSTALLED_PROVIDER_TOKEN")),
                customer, principal, hostSession, expiry);
        var provider = factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(),
                uri, Duration.ofSeconds(180), () -> session, () -> null, attempts::add), Map.of());
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"installed-request","installed-trace",
                principal,FinanceContextV1.PrincipalType.EMPLOYEE,"WEB","CREDIT_CARD_SERVICE",customer,
                hostSession,FinanceContextV1.AuthenticationLevel.STRONG,Set.of(CreditJavaFactory.SCOPE));
        boolean benefits="benefits".equals(System.getenv("CREDIT_JAVA_INSTALLED_SCENE"));
        var request = new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,benefits?CreditJavaFactory.BENEFITS:CreditJavaFactory.LIMIT,
                CapabilityRequestV1.Operation.QUERY,context,
                Map.of("caseRef",source.get("case_id"),"goal",benefits?"请推荐三项适用餐饮权益":"检查未来7天额度风险"),
                "operation-jvm-"+UUID.randomUUID().toString().replace("-",""),null,Map.of(),null);
        var result = provider.query(request);
        assertEquals(CapabilityResultV1.Status.COMPLETED,result.status());
        assertFalse(attempts.isEmpty());
        assertTrue(audits.size() >= 2);
        assertTrue(attempts.stream().allMatch(row -> row.status() == 200));
    }
    static FinanceContextV1 context() {
        return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-1", "trace-1", "manager-1",
            FinanceContextV1.PrincipalType.EMPLOYEE, "WEB", "CREDIT_CARD_SERVICE", "customer-1", "session-1",
            FinanceContextV1.AuthenticationLevel.STRONG, Set.of(CreditJavaFactory.SCOPE));
    }
    static BoundFinanceProvider.Grant grant() {
        return new BoundFinanceProvider.Grant("manager-1", FinanceContextV1.PrincipalType.EMPLOYEE,
            Set.of("customer-1"), Set.of("CREDIT_CARD_SERVICE"), Set.of(CreditJavaFactory.SCOPE),
            "session-1", "synthetic-authority", Instant.now().plusSeconds(60));
    }
    static Map<String,Object> binding() {
        return Map.of("tenant_id", "tenant-1", "principal_id", "manager-1", "customer_id", "customer-1",
            "case_id", "case-1", "observed_at", "2026-06-12T00:00:00Z",
            "visible_source_ids", List.of("ledger", "benefits"), "snapshot_digest", "a".repeat(64));
    }
    static CapabilityRequestV1 request(Map<String,Object> args) {
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CreditJavaFactory.LIMIT,
            CapabilityRequestV1.Operation.QUERY, context(), args, "operation-1", null, Map.of(), null);
    }
    static DescribedFinanceProvider provider(URI endpoint, AtomicReference<BoundFinanceProvider.Grant> grants) {
        var factory = new CreditJavaFactory(endpoint, grants::get,
            request -> new CreditJavaFactory.Binding(binding(), Instant.now().plusSeconds(3)), row -> true);
        var session = new ManagedProviderFactoryV01.SourceSession("synthetic-private-transport-token", "customer-1",
            "manager-1", "session-1", Instant.now().plusSeconds(60));
        return factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(), endpoint,
            Duration.ofSeconds(3), () -> session, () -> null, ignored -> {}), Map.of());
    }
    @Test void queryUsesOnlyHostIdentityAndChecksRevocationBeforeReturning() throws Exception {
        var calls = new AtomicInteger();
        var grants = new AtomicReference<>(grant());
        var revoke = new AtomicReference<>(false);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer synthetic-private-transport-token", exchange.getRequestHeaders().getFirst("Authorization"));
            var body = JSON.readTree(exchange.getRequestBody());
            assertEquals("manager-1", body.path("binding").path("principal_id").asText());
            assertEquals("session-1", body.path("session_ref").asText());
            assertTrue(body.path("remaining_ms").asInt() > 0);
            if (revoke.get()) grants.set(null);
            byte[] bytes = "{\"status\":\"complete\",\"customer_id\":\"customer-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), grants);
            var request = request(Map.of("caseRef", "case-1", "goal", "检查额度风险"));
            assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request).status());
            assertThrows(IllegalArgumentException.class, () -> provider.query(request(Map.of(
                "caseRef", "case-1", "goal", "query", "endpoint", "http://outside.invalid"))));
            assertEquals(1, calls.get());
            revoke.set(true);
            assertThrows(SecurityException.class, () -> provider.query(request));
            assertEquals(2, calls.get());
            assertThrows(SecurityException.class, () -> provider.query(request));
            assertEquals(2, calls.get());
            assertThrows(SecurityException.class, () -> provider.executeTask(request));
        } finally { server.stop(0); }
    }
    @Test void benefitsOptionsReachOnlyTheJavaBenefitsScene() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/query",e->{
            var body=JSON.readTree(e.getRequestBody());calls.incrementAndGet();
            assertEquals("benefits",body.path("scene").asText());
            assertEquals(2,body.path("recommendation").path("top_k").asInt());
            assertEquals(List.of("onerec","llm"),JSON.convertValue(body.path("recommendation").path("engines"),List.class));
            byte[] bytes="{\"status\":\"complete\",\"customer_id\":\"customer-1\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try{
            var provider=provider(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),new AtomicReference<>(grant()));
            var request=new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,CreditJavaFactory.BENEFITS,
                CapabilityRequestV1.Operation.QUERY,context(),Map.of("caseRef","case-1","goal","推荐权益","topK",2,"engines",List.of("onerec","llm")),"operation-1",null,Map.of(),null);
            assertEquals(CapabilityResultV1.Status.COMPLETED,provider.query(request).status());assertEquals(1,calls.get());
        }finally{server.stop(0);}
    }
    @Test void unconfiguredDiscoveryCannotGrantAccess() {
        assertThrows(SecurityException.class, () -> new CreditJavaFactory().create(null, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CreditJavaFactory(URI.create("http://remote.invalid:80"),
            CreditJavaProviderTest::grant, ignored -> null, ignored -> true));
    }
    @Test void redirectIsRejectedWithoutContactingItsTarget() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            exchange.getResponseHeaders().add("Location", "/credential-target");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.createContext("/credential-target", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close();
        });
        server.start();
        try {
            var provider = provider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), new AtomicReference<>(grant()));
            assertThrows(IllegalStateException.class, () -> provider.query(request(Map.of("caseRef", "case-1", "goal", "query"))));
            assertEquals(0, calls.get());
        } finally { server.stop(0); }
    }
    @Test void httpDrainAcceptsOnlyMinimalTimeoutWhileAuthorityStillHolds() throws Exception {
        var mode=new AtomicReference<>("timeout");var grants=new AtomicReference<>(grant());
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/query",e->{
            var body=JSON.readTree(e.getRequestBody());calls.incrementAndGet();
            long budget=body.path("remaining_ms").asLong();
            assertTrue(budget>0&&budget<=600);
            try{Thread.sleep(budget+80);}catch(InterruptedException x){Thread.currentThread().interrupt();}
            String selected=mode.get();if(selected.equals("revoked"))grants.set(null);
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("customer_id","customer-1");
            result.put("facts",List.of("must-not-cross-drain"));
            result.put("status",selected.equals("late-complete")?"complete":"timeout");
            result.put("result",selected.equals("leaking-timeout")?Map.of("code","DEADLINE_EXCEEDED","facts",List.of("unchecked")):Map.of("code","DEADLINE_EXCEEDED","execution",Map.of("snapshot","must-not-cross-drain")));
            byte[] bytes=JSON.writeValueAsBytes(result);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();
        });server.start();
        try{
            var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var factory=new CreditJavaFactory(endpoint,grants::get,
                ignored->new CreditJavaFactory.Binding(binding(),Instant.now().plusMillis(600)),row->true);
            var session=new ManagedProviderFactoryV01.SourceSession("synthetic-private-transport-token","customer-1","manager-1","session-1",Instant.now().plusSeconds(60));
            var provider=factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(),endpoint,Duration.ofSeconds(3),()->session,()->null,ignored->{}),Map.of());
            var request=request(Map.of("caseRef","case-1","goal","检查额度"));
            var drained=provider.query(request);
            assertEquals(CapabilityResultV1.Status.FAILED,drained.status());
            assertFalse(JSON.writeValueAsString(drained.data()).contains("must-not-cross-drain"));
            mode.set("late-complete");assertThrows(IllegalStateException.class,()->provider.query(request));
            mode.set("leaking-timeout");assertThrows(IllegalStateException.class,()->provider.query(request));
            mode.set("revoked");assertThrows(SecurityException.class,()->provider.query(request));
            assertEquals(4,calls.get());
            grants.set(grant());mode.set("timeout");
            var shortSession=new ManagedProviderFactoryV01.SourceSession("synthetic-private-transport-token","customer-1","manager-1","session-1",Instant.now().plusMillis(600));
            var expired=factory.create(new ManagedProviderFactoryV01.Connection(factory.connectionKind(),endpoint,Duration.ofSeconds(3),()->shortSession,()->null,ignored->{}),Map.of());
            assertThrows(SecurityException.class,()->expired.query(request));
            assertEquals(5,calls.get());
        }finally{server.stop(0);}
    }

}
