package io.github.bitlhk.finance.harness.providers.fraud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FraudMiningProviderTest {
    private HttpServer server;
    private FraudMiningProvider provider;
    private final AtomicInteger requests = new AtomicInteger();
    private boolean requireSession;
    private static final String HOST_SESSION = "synthetic-host-session-001";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/samples", exchange -> {
            requests.incrementAndGet();
            if (requireSession && !HOST_SESSION.equals(exchange.getRequestHeaders().getFirst("X-Session-Id"))) {
                writeJson(exchange, 403, "{\"code\":403,\"data\":null}");
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.contains("keyword=upstream-error")) {
                writeJson(exchange, 500, "{\"code\":500,\"message\":\"failed\",\"data\":null}");
                return;
            }
            if (query != null && query.contains("keyword=fraud-001")) {
                writeJson(exchange, 200, """
                        {"code":200,"message":"success","data":{"items":[
                          {"id":17,"cino":"fraud-001","label":1,"match_status":"matched",
                           "match_confidence":0.91,"prediction_result":"fraud","prediction_score":0.78,
                           "created_at":"2026-09-02T02:00:00"}],"total":1,"page":1,"page_size":20}}
                        """);
                return;
            }
            writeJson(exchange, 200,
                    "{\"code\":200,\"message\":\"success\",\"data\":{\"items\":[],\"total\":0,\"page\":1,\"page_size\":20}}");
        });
        server.start();
        provider = new FraudMiningProvider(new FraudMiningProvider.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void matchedIncidentProducesExplicitBlockDecisionWithoutLeakingRawFeatures() {
        CapabilityResultV1 result = provider.query(request("fraud-001"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("BLOCK", result.data().get("decision"));
        assertEquals("KNOWN_FRAUD_INCIDENT_MATCH", result.data().get("reasonCode"));
        assertEquals(true, result.data().get("knownRiskMatch"));
        assertEquals(List.of("BLOCK_TRANSFER", "REFER_FRAUD_REVIEW"), result.nextActions());
        assertFalse(result.data().toString().contains("feature"));
    }

    @Test
    void noMatchAllowsTheBankToContinueItsNormalPreflight() {
        CapabilityResultV1 result = provider.query(request("normal-001"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("ALLOW", result.data().get("decision"));
        assertEquals(false, result.data().get("knownRiskMatch"));
        assertEquals(List.of("CONTINUE_PREFLIGHT"), result.nextActions());
    }

    @Test
    void upstreamFailureIsNeverMappedToNormalOrAllow() {
        CapabilityResultV1 result = provider.query(request("upstream-error"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("FRAUD_SERVICE_UNAVAILABLE", result.error().sourceCode());
        assertTrue(result.error().retryable());
        assertFalse(result.data().containsKey("decision"));
    }

    @Test
    void authenticatedHostSessionIsSentWithoutEnteringPublicResult() {
        requireSession = true;
        provider = authenticated(HOST_SESSION, Instant.now().plusSeconds(60));
        CapabilityResultV1 result = provider.query(request("fraud-001"));
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("BLOCK", result.data().get("decision"));
        assertEquals(1, requests.get());
        assertFalse(result.toString().contains(HOST_SESSION));
        assertFalse(new FraudMiningProvider.HostSession(HOST_SESSION, Instant.now()).toString().contains(HOST_SESSION));
    }

    @Test
    void expiredHostSessionMakesNoHttpRequest() {
        provider = authenticated(HOST_SESSION, Instant.now().minusSeconds(1));
        CapabilityResultV1 result = provider.query(request("normal-001"));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("FRAUD_SESSION_EXPIRED", result.error().sourceCode());
        assertEquals(0, requests.get());
    }

    @Test
    void wrongOrMissingHostSessionNeverProducesAllow() {
        requireSession = true;
        assertEquals(CapabilityResultV1.Status.FAILED, provider.query(request("normal-001")).status());
        provider = authenticated("synthetic-wrong-session-001", Instant.now().plusSeconds(60));
        CapabilityResultV1 result = provider.query(request("normal-001"));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertFalse(result.data().containsKey("decision"));
        assertFalse(result.toString().contains("synthetic-wrong-session-001"));
    }

    @Test
    void authenticationCannotBeMissingOrInjectHeadersOrUsePlaintextRemote() {
        assertThrows(NullPointerException.class, () -> FraudMiningProvider.Config.authenticated(
                URI.create("https://example.test"), Duration.ofSeconds(2), null));
        for (String credential : List.of("", "x", "synthetic-session\r\nInjected:yes", "password=synthetic")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new FraudMiningProvider.HostSession(credential, Instant.now()));
        }
        var session = new FraudMiningProvider.HostSession(HOST_SESSION, Instant.now().plusSeconds(60));
        for (String endpoint : List.of("http://example.test", "https://user:pass@example.test", "https://example.test?key=x")) {
            assertThrows(IllegalArgumentException.class, () -> FraudMiningProvider.Config.authenticated(
                    URI.create(endpoint), Duration.ofSeconds(2), session));
        }
    }

    private FraudMiningProvider authenticated(String credential, Instant expiry) {
        return new FraudMiningProvider(FraudMiningProvider.Config.authenticated(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2),
                new FraudMiningProvider.HostSession(credential, expiry)));
    }

    private CapabilityRequestV1 request(String subjectRef) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                FraudMiningProvider.TRANSFER_RISK_ASSESS,
                CapabilityRequestV1.Operation.QUERY,
                new FinanceContextV1(
                        FinanceContextV1.SCHEMA_VERSION,
                        "request-fraud-001",
                        "trace-fraud-001",
                        "customer-demo-001",
                        FinanceContextV1.PrincipalType.CUSTOMER,
                        "MOBILE",
                        "TRANSFER",
                        "customer-demo-001",
                        "session-demo",
                        FinanceContextV1.AuthenticationLevel.STRONG,
                        java.util.Set.of("fraud:read")),
                Map.of("accountRef", subjectRef),
                null,
                null,
                Map.of(),
                null);
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
