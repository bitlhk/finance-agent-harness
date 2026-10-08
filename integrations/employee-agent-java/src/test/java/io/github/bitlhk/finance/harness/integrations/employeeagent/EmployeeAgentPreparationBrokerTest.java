package io.github.bitlhk.finance.harness.integrations.employeeagent;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EmployeeAgentPreparationBrokerTest {
    private HttpServer server;
    private EmployeeAgentPreparationBroker broker;
    private Map<String,Object> response;
    private int status;

    @BeforeEach void start() throws Exception {
        status=200;
        response=new LinkedHashMap<>(Map.ofEntries(
            Map.entry("ownerRef","owner-a"),Map.entry("sessionRef","session-a"),
            Map.entry("principalRef","employee:1"),Map.entry("authorityRef","revision-a"),
            Map.entry("runtime",Map.of("runtimeId","finance-harness","agentId","agent-a","adoptId","lgj-test")),
            Map.entry("expiresAt",Instant.now().plusSeconds(120).toString()),
            Map.entry("customers",List.of(Map.of("customerRef","CUST-000201","displayName","Synthetic"))),
            Map.entry("capabilityIds",List.of("wealth.previsit-context.query")),
            Map.entry("scopes",List.of("wealth:previsit:read")),Map.entry("purpose","WEALTH_PREVISIT"),
            Map.entry("accessToken","fhp."+"a".repeat(43))));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/internal/harness/service-preparation/authority", exchange->{
            byte[] body=HarnessJson.mapper().writeValueAsBytes(response);
            exchange.sendResponseHeaders(status,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        broker=new EmployeeAgentPreparationBroker(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),
            "owner-a","session-a",()->"synthetic-integration-secret",Duration.ofSeconds(2));
    }
    @AfterEach void stop() { broker.close();server.stop(0); }
    @Test void acceptsOnlyServerBoundEmployeeScope() throws Exception {
        broker.refresh();
        assertEquals(Set.of("CUST-000201"),broker.authority().grant().subjects());
        assertEquals("employee:1",broker.authority().grant().principalId());
        assertEquals(1,broker.customers().size());
        try(var session=broker.openSession()) { assertNotNull(session); }
    }
    @Test void wrongOwnerIsRejected() {
        response.put("ownerRef","owner-b");
        assertThrows(SecurityException.class,broker::refresh);
        assertThrows(SecurityException.class,broker::authority);
    }
    @Test void broaderCapabilityIsRejected() {
        response.put("capabilityIds",List.of("wealth.previsit-context.query","transfer.submit"));
        assertThrows(SecurityException.class,broker::refresh);
    }
    @Test void failedRefreshInvalidatesOldAuthority() throws Exception {
        broker.refresh();status=403;
        assertThrows(SecurityException.class,broker::refresh);
        assertThrows(SecurityException.class,broker::authority);
    }
    @Test void closeCannotBeRefreshed() throws Exception {
        broker.refresh();broker.close();
        assertThrows(SecurityException.class,broker::refresh);
        assertThrows(SecurityException.class,broker::customers);
    }
    @Test void expiredLeaseIsRejected() {
        response.put("expiresAt",Instant.now().minusSeconds(1).toString());
        assertThrows(SecurityException.class,broker::refresh);
    }
    @Test void remotePlainHttpIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new EmployeeAgentPreparationBroker(
            URI.create("http://example.com"),"owner-a","session-a",()->"secret",Duration.ofSeconds(2)));
    }
}
