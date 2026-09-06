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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonAiBankingProviderTest {
    @Test
    void hostSessionIsRecheckedWithoutAutomaticLogin() {
        var grant = new AtomicReference<>(new NonAiBankingProvider.HostSession("host-bank-session", "customer-demo-001",
                "customer-demo-001", "session-contract-001", Instant.now().plusSeconds(60)));
        var observed = new java.util.ArrayList<NonAiBankingProvider.HttpObservation>();
        var bound = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), java.time.Duration.ofSeconds(5), grant::get, observed::add));
        assertEquals(CapabilityResultV1.Status.COMPLETED, bound.query(request(Map.of())).status());
        assertEquals("host-bank-session", observedSession.get());
        assertEquals(0, sessionHits.get());
        assertEquals(1, observed.size());
        grant.set(null);
        assertEquals(CapabilityResultV1.Status.FAILED, bound.query(request(Map.of())).status());
        assertEquals(1, accountHits.get());
        assertEquals(0, sessionHits.get());
    }

    @Test
    void hostSessionRejectsIdentityExpiryAndSessionChangesBeforeBusinessRead() {
        for (var session : List.of(
                new NonAiBankingProvider.HostSession("bank", "other-customer", "customer-demo-001", "session-contract-001", Instant.now().plusSeconds(60)),
                new NonAiBankingProvider.HostSession("bank", "customer-demo-001", "other-principal", "session-contract-001", Instant.now().plusSeconds(60)),
                new NonAiBankingProvider.HostSession("bank", "customer-demo-001", "customer-demo-001", "other-session", Instant.now().plusSeconds(60)),
                new NonAiBankingProvider.HostSession("bank", "customer-demo-001", "customer-demo-001", "session-contract-001", Instant.now().minusSeconds(1)))) {
            var bound = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), java.time.Duration.ofSeconds(5), () -> session));
            assertEquals("BANK_SESSION_SUBJECT_MISMATCH", bound.query(request(Map.of())).error().sourceCode());
        }
        assertEquals(0, accountHits.get()); assertEquals(0, sessionHits.get());
    }

    @Test
    void revokedSourceSessionNeverTriggersLoginRetry() throws Exception {
        server.removeContext("/api/v1/accounts");
        server.createContext("/api/v1/accounts", exchange -> {
            accountHits.incrementAndGet();
            writeJson(exchange, 401, "{\"status\":\"FAILED\",\"error\":{\"failureClass\":\"NOT_AUTHENTICATED\",\"sourceCode\":\"SESSION_EXPIRED\"}}");
        });
        var bound = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), java.time.Duration.ofSeconds(5),
                () -> new NonAiBankingProvider.HostSession("revoked-bank", "customer-demo-001", "customer-demo-001", "session-contract-001", Instant.now().plusSeconds(60))));
        assertEquals(CapabilityResultV1.Status.FAILED, bound.query(request(Map.of())).status());
        assertEquals(1, accountHits.get()); assertEquals(0, sessionHits.get());
    }
    private HttpServer server;
    private NonAiBankingProvider provider;
    private final AtomicInteger sessionHits = new AtomicInteger();
    private final AtomicInteger accountHits = new AtomicInteger();
    private final AtomicInteger wealthAggregateHits = new AtomicInteger();
    private final AtomicInteger riskAssessmentHits = new AtomicInteger();
    private final AtomicInteger taskCreateHits = new AtomicInteger();
    private final AtomicInteger taskConfirmHits = new AtomicInteger();
    private final AtomicInteger taskSubmitHits = new AtomicInteger();
    private final AtomicBoolean riskAssessmentUnavailable = new AtomicBoolean();
    private final AtomicReference<String> observedSession = new AtomicReference<>();
    private final AtomicReference<String> observedRequestId = new AtomicReference<>();
    private final AtomicReference<String> observedTraceId = new AtomicReference<>();
    private final AtomicReference<String> observedPreflightBody = new AtomicReference<>();
    private final AtomicReference<String> taskStatus = new AtomicReference<>("CREATED");

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
            if (exchange.getRequestURI().getPath().startsWith("/api/v1/accounts/")) {
                writeJson(exchange, 200, """
                        {"status":"SUCCESS","data":{"accountId":"acct-002","availableBalance":0,"currency":"CNY"}}
                        """);
                return;
            }
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
            if (riskAssessmentUnavailable.get()) {
                writeJson(exchange, 503, """
                        {"requestId":"req-risk","traceId":"trace-risk","status":"FAILED",
                         "data":null,"asOf":"2026-09-01T01:00:02Z","sourceSystem":"suitability-simulator",
                         "error":{"failureClass":"TEMPORARY_FAILURE","sourceCode":"RISK_SERVICE_UNAVAILABLE",
                         "retryable":true,"fieldErrors":["risk assessment unavailable"],
                         "nextActions":["REFRESH_RISK_ASSESSMENT"]}}
                        """);
                return;
            }
            writeJson(exchange, 200, """
                    {"requestId":"req-risk","traceId":"trace-risk","status":"SUCCESS",
                     "data":{"assessmentVersion":"risk-v2","riskLevel":"C3","status":"VALID",
                     "assessedOn":"2026-03-01","expiresOn":"2027-03-01",
                     "sourceSystem":"suitability-simulator-v2"},
                     "asOf":"2026-09-01T01:00:02Z","sourceSystem":"suitability-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/preferences/channel", exchange -> writeJson(exchange, 200, """
                {"requestId":"req-preference","traceId":"trace-preference","status":"SUCCESS",
                 "data":{"language":"zh-CN","largeTextEnabled":false,"screenReaderEnabled":false,
                 "accessibilityMode":false,"defaultAccountId":"acct-002","version":4},
                 "asOf":"2026-09-01T01:00:04Z","sourceSystem":"preference-simulator","error":null}
                """));
        server.createContext("/api/v1/capabilities/channel.preferences.update/preflight", exchange -> {
            observedPreflightBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            writeJson(exchange, 200, """
                    {"requestId":"req-preflight","traceId":"trace-preflight","status":"SUCCESS",
                     "data":{"preflightId":"pf-pref-001","capabilityId":"channel.preferences.update",
                     "capabilityVersion":"2.1.0","riskLevel":"R2","canSubmit":true,
                     "summary":"将开启大字模式。","requiredConfirmations":["核对渠道偏好","明确确认保存"]},
                     "asOf":"2026-09-01T01:00:05Z","sourceSystem":"policy-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/tasks", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/api/v1/tasks".equals(path)) {
                taskCreateHits.incrementAndGet();
                taskStatus.set("CREATED");
                writeTask(exchange, 202, "CREATED", false);
                return;
            }
            if (path.endsWith("/confirm")) {
                taskConfirmHits.incrementAndGet();
                taskStatus.set("CONFIRMED");
                writeTask(exchange, 200, "CONFIRMED", false);
                return;
            }
            if (path.endsWith("/submit")) {
                taskSubmitHits.incrementAndGet();
                taskStatus.set("COMPLETED");
                writeTask(exchange, 200, "COMPLETED", true);
                return;
            }
            writeTask(exchange, 200, taskStatus.get(), "COMPLETED".equals(taskStatus.get()));
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
    void sessionSubjectMismatchDoesNotReadAnyCustomerAccounts() {
        var original = request(Map.of());
        var c = original.context();
        var other = new FinanceContextV1(c.schemaVersion(), c.requestId(), c.traceId(), c.principalId(),
                c.principalType(), c.channel(), c.purpose(), "other-customer", c.sessionRef(), c.authenticationLevel(), c.scopes());
        var result = provider.query(new CapabilityRequestV1(original.schemaVersion(), original.capabilityId(),
                original.operation(), other, Map.of(), null, null, Map.of(), null));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertTrue(result.data().isEmpty());
        assertEquals(1, sessionHits.get());
        assertEquals("BANK_SESSION_SUBJECT_MISMATCH", result.error().sourceCode());
        assertEquals(0, accountHits.get());
        assertEquals(CapabilityResultV1.Status.COMPLETED, provider.query(original).status());
        assertEquals(1, accountHits.get());
        provider.query(new CapabilityRequestV1(original.schemaVersion(), original.capabilityId(),
                original.operation(), other, Map.of(), null, null, Map.of(), null));
        assertEquals(1, accountHits.get());
    }

    @Test
    void selectedAccountUsesDedicatedEndpointAndNeverFallsBackToAnotherAccount() {
        CapabilityResultV1 selected = provider.query(request(Map.of("accountId", "acct-002")));
        assertEquals(CapabilityResultV1.Status.COMPLETED, selected.status());
        var accounts = (List<?>) selected.data().get("accounts");
        assertEquals(1, accounts.size());
        assertEquals("acct-002", ((Map<?, ?>) accounts.getFirst()).get("accountId"));
        assertEquals(0, ((Map<?, ?>) accounts.getFirst()).get("availableBalance"));
        CapabilityResultV1 wrong = provider.query(request(Map.of("accountId", "acct-003")));
        assertEquals("ACCOUNT_ID_MISMATCH", wrong.error().sourceCode());
        int hits = accountHits.get();
        assertEquals("ACCOUNT_ID_INVALID", provider.query(request(Map.of("accountId", "../other"))).error().sourceCode());
        assertEquals(hits, accountHits.get());
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
        assertTrue(result.data().get("readiness") instanceof Map<?, ?> readiness
                && "READY".equals(readiness.get("status")));
        assertTrue(result.data().get("aggregation") instanceof Map<?, ?> aggregation
                && "PARALLEL".equals(aggregation.get("mode")));
        assertFalse(result.data().containsKey("recommendedProducts"));
        assertFalse(result.data().containsKey("targetAllocation"));
        assertEquals(1, sessionHits.get());
        assertEquals(1, wealthAggregateHits.get());
        assertEquals(1, riskAssessmentHits.get());
    }

    @Test
    void wealthContextDegradesToPartialWhenOptionalRiskAssessmentIsUnavailable() {
        riskAssessmentUnavailable.set(true);

        CapabilityResultV1 result = provider.query(wealthRequest());

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals(List.of("riskAssessment"), result.missingInputs());
        assertEquals(List.of("REFRESH_RISK_ASSESSMENT"), result.nextActions());
        assertTrue(result.data().get("readiness") instanceof Map<?, ?> readiness
                && "PARTIAL".equals(readiness.get("status")));
        assertTrue(result.data().get("riskAssessment") instanceof Map<?, ?> risk && risk.isEmpty());
        assertFalse(result.data().containsKey("recommendedProducts"));
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

    @Test
    void channelPreferenceTaskStopsAtConfirmationAndPreservesBankTaskState() {
        CapabilityResultV1 result = provider.executeTask(taskRequest(
                Map.of("largeTextEnabled", true),
                "idem-channel-pref-001",
                null,
                Map.of(),
                null));

        assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION, result.status());
        assertNotNull(result.task());
        assertEquals("task-pref-001", result.task().taskRef());
        assertTrue(result.task().confirmationRequired());
        assertEquals("idem-channel-pref-001", result.task().continuation().get("idempotencyKey"));
        assertTrue(observedPreflightBody.get().contains("\"largeTextEnabled\":true"));
        assertTrue(observedPreflightBody.get().contains("\"language\":\"zh-CN\""));
        assertTrue(observedPreflightBody.get().contains("\"version\":4"));
        assertEquals(1, taskCreateHits.get());
        assertEquals(0, taskConfirmHits.get());
        assertEquals(0, taskSubmitHits.get());
    }

    @Test
    void confirmedChannelPreferenceTaskSubmitsOnceAndRepeatedResumeIsIdempotent() {
        CapabilityResultV1 started = provider.executeTask(taskRequest(
                Map.of("largeTextEnabled", true),
                "idem-channel-pref-002",
                null,
                Map.of(),
                null));
        CapabilityRequestV1.Confirmation confirmation = new CapabilityRequestV1.Confirmation(
                true,
                "确认保存渠道偏好",
                Instant.parse("2026-09-01T01:00:06Z"));

        CapabilityResultV1 completed = provider.executeTask(taskRequest(
                Map.of(),
                "idem-channel-pref-002",
                started.task().taskRef(),
                started.task().continuation(),
                confirmation));
        CapabilityResultV1 replayed = provider.executeTask(taskRequest(
                Map.of(),
                "idem-channel-pref-002",
                started.task().taskRef(),
                started.task().continuation(),
                confirmation));

        assertEquals(CapabilityResultV1.Status.COMPLETED, completed.status());
        assertEquals(CapabilityResultV1.Status.COMPLETED, replayed.status());
        assertEquals("COMPLETED", completed.task().status());
        assertEquals(1, taskConfirmHits.get());
        assertEquals(1, taskSubmitHits.get());
    }

    @Test
    void channelPreferenceTaskRequiresTaskOperationAndIdempotency() {
        CapabilityRequestV1 wrongOperation = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.CHANNEL_PREFERENCES_UPDATE,
                CapabilityRequestV1.Operation.QUERY,
                channelContext(),
                Map.of("largeTextEnabled", true),
                "idem-wrong-operation",
                null,
                Map.of(),
                null);
        CapabilityResultV1 missingIdempotency = provider.executeTask(taskRequest(
                Map.of("largeTextEnabled", true), null, null, Map.of(), null));

        assertEquals("TASK_OPERATION_REQUIRED", provider.executeTask(wrongOperation).error().sourceCode());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", missingIdempotency.error().sourceCode());
        assertEquals(0, taskCreateHits.get());
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

    private static CapabilityRequestV1 taskRequest(
            Map<String, Object> parameters,
            String idempotencyKey,
            String taskRef,
            Map<String, Object> continuation,
            CapabilityRequestV1.Confirmation confirmation) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.CHANNEL_PREFERENCES_UPDATE,
                CapabilityRequestV1.Operation.TASK,
                channelContext(),
                parameters,
                idempotencyKey,
                taskRef,
                continuation,
                confirmation);
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

    private static FinanceContextV1 channelContext() {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-channel-001",
                "trace-channel-001",
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "CHANNEL_SETTINGS",
                "customer-demo-001",
                "session-channel-001",
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("channel:write"));
    }

    private static void writeTask(
            HttpExchange exchange,
            int httpStatus,
            String status,
            boolean includeResult) throws IOException {
        String result = includeResult
                ? ",\"result\":{\"language\":\"zh-CN\",\"largeTextEnabled\":true,\"version\":5}"
                : "";
        writeJson(exchange, httpStatus, """
                {"requestId":"req-task","traceId":"trace-task","status":"SUCCESS",
                 "data":{"taskId":"task-pref-001","capabilityId":"channel.preferences.update",
                 "status":"%s","operation":"SUBMIT"%s},
                 "asOf":"2026-09-01T01:00:07Z","sourceSystem":"task-store-simulator","error":null}
                """.formatted(status, result));
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
