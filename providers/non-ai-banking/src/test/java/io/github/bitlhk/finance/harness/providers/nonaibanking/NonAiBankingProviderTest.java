package io.github.bitlhk.finance.harness.providers.nonaibanking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceiptV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceipts;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonAiBankingProviderTest {
    private HttpServer server;
    private NonAiBankingProvider provider;
    private final AtomicInteger sessionHits = new AtomicInteger();
    private final AtomicInteger accountHits = new AtomicInteger();
    private final AtomicInteger wealthAggregateHits = new AtomicInteger();
    private final AtomicInteger riskAssessmentHits = new AtomicInteger();
    private final AtomicReference<String> observedSession = new AtomicReference<>();
    private final AtomicReference<String> observedRequestId = new AtomicReference<>();
    private final AtomicReference<String> observedTraceId = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sessions", exchange -> {
            sessionHits.incrementAndGet();
            writeJson(exchange, 200, """
                    {"requestId":"req-login","traceId":"trace-login","status":"SUCCESS",
                     "data":{"sessionId":"session-demo","customerRef":"customer-demo-001"},
                     "asOf":"2026-09-01T01:00:00Z","sourceSystem":"iam-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/accounts", exchange -> {
            accountHits.incrementAndGet();
            observedSession.set(exchange.getRequestHeaders().getFirst("X-Session-Id"));
            observedRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
            observedTraceId.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            if (exchange.getRequestURI().getRawQuery() != null
                    && exchange.getRequestURI().getRawQuery().contains("page=99")) {
                writeJson(exchange, 403, """
                        {"requestId":"req-query","traceId":"trace-query","status":"FAILED",
                         "data":null,"asOf":"2026-09-01T01:00:01Z","sourceSystem":"non-ai-banking",
                         "error":{"failureClass":"NOT_AUTHORIZED","sourceCode":"ACCOUNT_DENIED",
                         "retryable":false,"fieldErrors":["account access denied"],
                         "nextActions":["CHECK_AUTHORIZATION"],"customerMessageId":"banking.error.ACCOUNT_DENIED"}}
                        """);
                return;
            }
            writeJson(exchange, 200, """
                    {"requestId":"req-query","traceId":"trace-query","status":"SUCCESS",
                     "data":{"accounts":[{"accountId":"acct-001","availableBalance":12580.50,
                     "currency":"CNY"}],"recentTransactions":{},"stale":false,"nextPageToken":null},
                     "asOf":"2026-09-01T01:00:01Z","sourceSystem":"account-core-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/wealth/aggregate", exchange -> {
            wealthAggregateHits.incrementAndGet();
            writeJson(exchange, 200, """
                    {"requestId":"req-wealth","traceId":"trace-wealth","status":"SUCCESS",
                     "data":{"overview":{"totalAssets":1000000.00,"netAssets":920000.00,"currency":"CNY"},
                     "holdings":[{"holdingId":"fund-001","productType":"FUND","marketValue":600000.00}],
                     "riskExposure":{"topHoldingRatio":0.60,"concentrationStatus":"HIGH"},
                     "events":[{"eventId":"event-001","eventType":"MATURITY","eventDate":"2026-09-10",
                     "title":"理财到期","amount":300000.00,"currency":"CNY"}],
                     "dataQuality":{"status":"READY","tradeEligible":true}},
                     "asOf":"2026-09-01T01:00:03Z","sourceSystem":"wealth-aggregate-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/finance/risk-assessment", exchange -> {
            riskAssessmentHits.incrementAndGet();
            writeJson(exchange, 200, """
                    {"requestId":"req-risk","traceId":"trace-risk","status":"SUCCESS",
                     "data":{"assessmentVersion":"risk-v2","riskLevel":"C3","status":"VALID",
                     "assessedOn":"2026-03-01","expiresOn":"2027-03-01",
                     "sourceSystem":"suitability-simulator-v2"},
                     "asOf":"2026-09-01T01:00:02Z","sourceSystem":"suitability-simulator","error":null}
                    """);
        });
        server.start();
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        provider = new NonAiBankingProvider(
                NonAiBankingProvider.Config.simulator(baseUri, "test-login", "test-secret"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void accountQueryCreatesSessionPropagatesTraceAndPreservesNullData() {
        CapabilityResultV1 result = provider.query(request(Map.of("page", 1, "pageSize", 20)));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("account-core-simulator", result.sourceSystem());
        assertTrue(result.data().get("accounts") instanceof List<?> accounts && !accounts.isEmpty());
        assertTrue(result.data().containsKey("nextPageToken"));
        assertEquals("session-demo", observedSession.get());
        assertEquals("request-contract-001", observedRequestId.get());
        assertEquals("trace-contract-001", observedTraceId.get());
        assertEquals(1, sessionHits.get());
        assertEquals(1, accountHits.get());

        CapabilityResultV1 second = provider.query(request(Map.of()));
        assertEquals(CapabilityResultV1.Status.COMPLETED, second.status());
        assertEquals(1, sessionHits.get(), "session should be reused");
        assertEquals(2, accountHits.get());
    }

    @Test
    void upstreamStructuredErrorMapsToCanonicalFailure() {
        CapabilityResultV1 result = provider.query(request(Map.of("page", 99)));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertNotNull(result.error());
        assertEquals("NOT_AUTHORIZED", result.error().failureClass());
        assertEquals("ACCOUNT_DENIED", result.error().sourceCode());
        assertEquals(List.of("account access denied"), result.error().fieldErrors());
        assertEquals(List.of("CHECK_AUTHORIZATION"), result.nextActions());
    }

    @Test
    void wealthCustomerContextCombinesAuthoritativeSnapshotsWithoutRecommendationLogic() {
        CapabilityResultV1 result = provider.query(wealthRequest());

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("non-ai-banking-wealth-context", result.sourceSystem());
        assertTrue(result.data().get("wealthAggregate") instanceof Map<?, ?> aggregate
                && aggregate.get("riskExposure") instanceof Map<?, ?>);
        assertTrue(result.data().get("riskAssessment") instanceof Map<?, ?> risk
                && "VALID".equals(risk.get("status")));
        assertTrue(result.data().get("sourceSnapshots") instanceof List<?> snapshots
                && snapshots.size() == 2);
        assertFalse(result.data().containsKey("recommendedProducts"));
        assertFalse(result.data().containsKey("targetAllocation"));
        assertEquals(1, sessionHits.get());
        assertEquals(1, wealthAggregateHits.get());
        assertEquals(1, riskAssessmentHits.get());
    }

    @Test
    void unsupportedCapabilityFailsWithoutCallingBank() {
        CapabilityRequestV1 unsupported = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                "unsupported.query",
                CapabilityRequestV1.Operation.QUERY,
                context(),
                Map.of(),
                null,
                null,
                Map.of(),
                null);

        CapabilityResultV1 result = provider.query(unsupported);

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("UNSUPPORTED_CAPABILITY", result.error().sourceCode());
        assertEquals(0, sessionHits.get());
        assertEquals(0, accountHits.get());
    }

    @Test
    void queryResultProducesMinimalEvidenceReceipt() {
        CapabilityRequestV1 request = request(Map.of());
        Instant startedAt = Instant.parse("2026-09-01T01:00:00Z");
        CapabilityResultV1 result = provider.query(request);
        Instant finishedAt = Instant.parse("2026-09-01T01:00:02Z");

        EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                request, result, NonAiBankingProvider.PROVIDER_ID, startedAt, finishedAt);

        assertEquals(NonAiBankingProvider.PROVIDER_ID, receipt.providerId());
        assertEquals(NonAiBankingProvider.ACCOUNT_BALANCE_QUERY, receipt.capabilityId());
        assertEquals("account-core-simulator", receipt.sourceSystem());
        assertFalse(receipt.receiptId().isBlank());
    }

    private static CapabilityRequestV1 request(Map<String, Object> parameters) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.ACCOUNT_BALANCE_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(),
                parameters,
                null,
                null,
                Map.of(),
                null);
    }

    private static CapabilityRequestV1 wealthRequest() {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                wealthContext(),
                Map.of(),
                null,
                "wealth-diagnosis-001",
                Map.of(),
                null);
    }

    private static FinanceContextV1 context() {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-contract-001",
                "trace-contract-001",
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "ACCOUNT_SERVICE",
                "customer-demo-001",
                "session-contract-001",
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("account:read"));
    }

    private static FinanceContextV1 wealthContext() {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-wealth-001",
                "trace-wealth-001",
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "WEALTH_DIAGNOSIS",
                "customer-demo-001",
                "session-wealth-001",
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("wealth:read"));
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
