package io.github.bitlhk.finance.harness.providers.fraud;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class FraudMiningFactoryV01Test {
    private HttpServer server;
    private final List<String> credentials = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<ManagedProviderFactoryV01.SourceSession> session = new AtomicReference<>();
    private boolean paginated;
    private Runnable afterRequest = () -> {};

    @BeforeEach void start() throws Exception {
        session.set(authority("synthetic-first", "principal", "customer", "host-session", Instant.now().plusSeconds(60)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/samples", exchange -> {
            credentials.add(exchange.getRequestHeaders().getFirst("X-Session-Id"));
            int page = exchange.getRequestURI().getQuery().contains("page=1&") ? 1 : 2;
            String body = "{\"code\":200,\"data\":{\"total\":" + (paginated ? 2 : 1)
                    + ",\"items\":[{\"id\":" + page + ",\"cino\":\"mapped-cino\",\"label\":1,"
                    + "\"match_status\":\"matched\",\"created_at\":\"2026-09-02T00:00:00Z\"}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            int responseStatus = status.get();
            afterRequest.run();
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void actualFactoryUsesRotatingHostAuthorityForBothCapabilities() {
        var provider = managed(session::get);
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request(false)).status());
        session.set(authority("synthetic-renewed", "principal", "customer", "host-session", Instant.now().plusSeconds(60)));
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request(true)).status());
        assertEquals(List.of("synthetic-first", "synthetic-renewed"), credentials);
        assertEquals(Set.of("fraud:read"), new FraudMiningFactoryV01().requirements().get(FraudMiningProvider.TRANSFER_RISK_ASSESS));
    }

    @Test void missingExpiredWrongPrincipalSubjectOrSessionMakesZeroCalls() {
        List<ManagedProviderFactoryV01.SourceSession> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(authority("synthetic-expired", "principal", "customer", "host-session", Instant.EPOCH));
        invalid.add(authority("synthetic-wrong", "other", "customer", "host-session", Instant.now().plusSeconds(60)));
        invalid.add(authority("synthetic-wrong", "principal", "other", "host-session", Instant.now().plusSeconds(60)));
        invalid.add(authority("synthetic-wrong", "principal", "customer", "other", Instant.now().plusSeconds(60)));
        invalid.add(authority("synthetic\r\ninjection", "principal", "customer", "host-session", Instant.now().plusSeconds(60)));
        for (var value : invalid) {
            session.set(value);
            for (boolean evidence : List.of(false, true)) assertDenied(managed(session::get).query(request(evidence)));
        }
        for (boolean evidence : List.of(false, true)) {
            assertDenied(managed(() -> { throw new IllegalStateException("synthetic-private"); }).query(request(evidence)));
        }
        assertTrue(credentials.isEmpty());
    }

    @Test void remoteDenialNeverRetriesAnonymously() {
        for (int denied : List.of(401, 403)) {
            status.set(denied);
            for (boolean evidence : List.of(false, true)) assertDenied(managed(session::get).query(request(evidence)));
        }
        assertEquals(4, credentials.size());
        assertFalse(credentials.contains(null));
    }

    @Test void everyEvidencePageRefreshesAuthorityAndRevocationDiscardsPartialSuccess() {
        paginated = true;
        var provider = new FraudMiningProvider(new FraudMiningProvider.Config(endpoint(), Duration.ofSeconds(2), 1, 3, 10), session::get);
        afterRequest = () -> session.set(authority("synthetic-renewed", "principal", "customer", "host-session", Instant.now().plusSeconds(60)));
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(request(true)).status());
        assertEquals(List.of("synthetic-first", "synthetic-renewed"), credentials);
        credentials.clear();
        afterRequest = () -> session.set(null);
        assertDenied(provider.query(request(true)));
        assertEquals(1, credentials.size());
    }

    @Test void remoteRevocationAfterFirstPageIsAuthorizationFailure() {
        paginated = true;
        afterRequest = () -> status.set(403);
        var provider = new FraudMiningProvider(new FraudMiningProvider.Config(endpoint(), Duration.ofSeconds(2), 1, 3, 10), session::get);
        assertDenied(provider.query(request(true)));
        assertEquals(2, credentials.size());
    }

    @Test void managedEndpointRejectsUnprotectedRemoteOrEmbeddedCredentials() {
        for (String uri : List.of("http://example.invalid", "https://user:pass@example.invalid", "https://example.invalid?token=x")) {
            assertThrows(IllegalArgumentException.class, () -> new FraudMiningProvider(
                    new FraudMiningProvider.Config(URI.create(uri), Duration.ofSeconds(2)), session::get));
        }
        assertEquals("SourceSession[redacted]", session.get().toString());
    }

    @Test void redirectDoesNotForwardTheSourceCredential() throws Exception {
        var redirectedCalls = new AtomicInteger();
        var target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/redirected", exchange -> {
            redirectedCalls.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        target.start();
        try {
            server.removeContext("/api/v1/samples");
            server.createContext("/api/v1/samples", exchange -> {
                exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + target.getAddress().getPort() + "/redirected");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            for (boolean evidence : List.of(false, true)) {
                assertEquals(CapabilityResultV1.Status.FAILED, managed(session::get).query(request(evidence)).status());
            }
            assertEquals(0, redirectedCalls.get());
        } finally { target.stop(0); }
    }

    private DescribedFinanceProvider managed(Supplier<ManagedProviderFactoryV01.SourceSession> supplier) {
        var connection = new ManagedProviderFactoryV01.Connection(FraudMiningFactoryV01.CONNECTION_KIND,
                endpoint(), Duration.ofSeconds(2), supplier, () -> null, attempt -> {});
        return new FraudMiningFactoryV01().create(connection, Map.of());
    }
    private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private static ManagedProviderFactoryV01.SourceSession authority(String credential, String principal, String subject, String host, Instant expiry) {
        return new ManagedProviderFactoryV01.SourceSession(credential, subject, principal, host, expiry);
    }
    private CapabilityRequestV1 request(boolean evidence) {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request", "trace", "principal",
                FinanceContextV1.PrincipalType.CUSTOMER, "MOBILE", "TRANSFER", "customer", "host-session",
                FinanceContextV1.AuthenticationLevel.STRONG, Set.of("fraud:read", "fraud-risk:read"));
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                evidence ? FraudMiningProvider.RISK_EVIDENCE_QUERY : FraudMiningProvider.TRANSFER_RISK_ASSESS,
                CapabilityRequestV1.Operation.QUERY, context,
                evidence ? Map.of("subjectType", "FRAUD_SOURCE_CINO", "subjectRef", "mapped-cino") : Map.of("accountRef", "mapped-cino"),
                null, null, Map.of(), null);
    }
    private void assertDenied(CapabilityResultV1 result) {
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("AUTHORIZATION", result.error().failureClass());
        assertFalse(result.error().retryable());
        assertTrue(result.data().isEmpty());
        assertFalse(result.toString().contains("synthetic-"));
    }
}
