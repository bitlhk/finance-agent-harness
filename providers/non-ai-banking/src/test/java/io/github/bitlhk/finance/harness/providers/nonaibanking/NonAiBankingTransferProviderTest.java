package io.github.bitlhk.finance.harness.providers.nonaibanking;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NonAiBankingTransferProviderTest {
    private HttpServer server;
    private NonAiBankingProvider provider;
    private final AtomicInteger stepUpHits = new AtomicInteger();
    private final AtomicInteger createHits = new AtomicInteger();
    private final AtomicInteger confirmHits = new AtomicInteger();
    private final AtomicInteger submitHits = new AtomicInteger();
    private final AtomicInteger reconcileHits = new AtomicInteger();
    private final AtomicReference<String> taskStatus = new AtomicReference<>("CREATED");
    private final AtomicBoolean unknownOnSubmit = new AtomicBoolean();
    private final AtomicBoolean disconnectOnSubmit = new AtomicBoolean();
    private final AtomicReference<String> submitResponse = new AtomicReference<>();
    private final AtomicReference<String> creationResponse = new AtomicReference<>();
    private final AtomicReference<String> submittedState = new AtomicReference<>("COMPLETED");
    private final AtomicBoolean observationUnavailable = new AtomicBoolean();
    private String capabilityId = NonAiBankingProvider.TRANSFER_INTERNAL_CREATE;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sessions", exchange -> writeJson(exchange, 200, """
                {"requestId":"req-login","traceId":"trace-login","status":"SUCCESS",
                 "data":{"sessionId":"session-transfer","customerRef":"customer-demo-001"},
                 "asOf":"2026-09-03T01:00:00Z","sourceSystem":"iam-simulator","error":null}
                """));
        server.createContext("/api/v1/sessions/step-up", exchange -> {
            stepUpHits.incrementAndGet();
            writeJson(exchange, 200, """
                    {"requestId":"req-step-up","traceId":"trace-step-up","status":"SUCCESS",
                     "data":{"sessionId":"session-transfer","identityStrength":"I2"},
                     "asOf":"2026-09-03T01:00:01Z","sourceSystem":"iam-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/capabilities/transfer.internal.create/preflight", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("bene-risk")) {
                writeJson(exchange, 422, """
                        {"requestId":"req-risk","traceId":"trace-risk","status":"FAILED","data":null,
                         "asOf":"2026-09-03T01:00:02Z","sourceSystem":"non-ai-banking",
                         "error":{"failureClass":"NOT_ELIGIBLE",
                         "sourceCode":"TRANSFER_FRAUD_RISK_BLOCKED","retryable":false,
                         "fieldErrors":["收款账户命中涉诈风险，转账已阻断，请通过银行人工渠道核实。"],
                         "nextActions":["CONTACT_HUMAN"]}}
                        """);
                return;
            }
            writeJson(exchange, 200, """
                    {"requestId":"req-preflight","traceId":"trace-preflight","status":"SUCCESS",
                     "data":{"preflightId":"pf-transfer-001","capabilityId":"transfer.internal.create",
                     "capabilityVersion":"1.0.0","riskLevel":"R3","canSubmit":true,
                     "summary":"将发起一笔行内转账模拟。",
                     "requiredConfirmations":["核对金额","明确确认提交"]},
                     "asOf":"2026-09-03T01:00:02Z","sourceSystem":"policy-simulator","error":null}
                    """);
        });
        server.createContext("/api/v1/tasks", this::handleTask);
        server.start();
        URI baseUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        provider = new NonAiBankingProvider(new NonAiBankingProvider.Config(
                baseUri,
                "test-login",
                "test-secret",
                "transfer-test",
                "M",
                "finance-harness-test",
                Duration.ofSeconds(5),
                "000000"));
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void fraudRiskBlocksDuringPreflightWithoutCreatingTask() {
        CapabilityResultV1 result = provider.executeTask(request(
                Map.of("fromAccountId", "acct-001", "beneficiaryId", "bene-risk", "amount", 88.5),
                "idem-risk-001", null, Map.of(), null));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertNotNull(result.error());
        assertEquals("TRANSFER_FRAUD_RISK_BLOCKED", result.error().sourceCode());
        assertTrue(result.content().contains("涉诈风险"));
        assertEquals(1, stepUpHits.get());
        assertEquals(0, createHits.get());
    }

    @Test
    void confirmedTransferSubmitsOnceThroughCanonicalTaskContract() {
        CapabilityResultV1 started = provider.executeTask(request(
                Map.of("fromAccountId", "acct-001", "beneficiaryId", "bene-002", "amount", 0.01),
                "idem-transfer-001", null, Map.of(), null));
        assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION, started.status());
        assertNotNull(started.task());

        CapabilityRequestV1 confirmed = request(
                Map.of(),
                "idem-transfer-001",
                started.task().taskRef(),
                started.task().continuation(),
                new CapabilityRequestV1.Confirmation(true, "确认提交", Instant.now()));
        CapabilityResultV1 completed = provider.executeTask(confirmed);
        CapabilityResultV1 replayed = provider.executeTask(confirmed);

        assertEquals(CapabilityResultV1.Status.COMPLETED, completed.status());
        assertEquals(CapabilityResultV1.Status.COMPLETED, replayed.status());
        assertEquals(1, createHits.get());
        assertEquals(1, confirmHits.get());
        assertEquals(1, submitHits.get());
        assertEquals(1, stepUpHits.get());
    }

    @Test
    void unknownTransferIsReconciledWithoutSecondSubmit() {
        CapabilityResultV1 started = provider.executeTask(request(
                Map.of("fromAccountId", "acct-001", "beneficiaryId", "bene-002", "amount", 0.02),
                "idem-transfer-unknown", null, Map.of(), null));
        unknownOnSubmit.set(true);
        CapabilityRequestV1 confirmed = request(
                Map.of(),
                "idem-transfer-unknown",
                started.task().taskRef(),
                started.task().continuation(),
                new CapabilityRequestV1.Confirmation(true, "确认提交", Instant.now()));

        CapabilityResultV1 unknown = provider.executeTask(confirmed);
        CapabilityResultV1 reconciled = provider.executeTask(request(
                Map.of(),
                "idem-transfer-unknown",
                started.task().taskRef(),
                started.task().continuation(),
                null));

        assertEquals(CapabilityResultV1.Status.UNKNOWN, unknown.status());
        assertEquals(CapabilityResultV1.Status.COMPLETED, reconciled.status());
        assertEquals(1, submitHits.get());
        assertEquals(1, reconcileHits.get());
    }

    @Test
    void submitTransportFailureBecomesUnknownAndIsReconciledWithoutReplay() {
        CapabilityResultV1 started = provider.executeTask(request(
                Map.of("fromAccountId", "acct-001", "beneficiaryId", "bene-002", "amount", 0.03),
                "idem-transfer-disconnect", null, Map.of(), null));
        disconnectOnSubmit.set(true);
        CapabilityRequestV1 confirmed = request(
                Map.of(),
                "idem-transfer-disconnect",
                started.task().taskRef(),
                started.task().continuation(),
                new CapabilityRequestV1.Confirmation(true, "确认提交", Instant.now()));

        CapabilityResultV1 unknown = provider.executeTask(confirmed);
        disconnectOnSubmit.set(false);
        CapabilityResultV1 reconciled = provider.executeTask(request(
                Map.of(),
                "idem-transfer-disconnect",
                started.task().taskRef(),
                unknown.task().continuation(),
                null));

        assertEquals(CapabilityResultV1.Status.UNKNOWN, unknown.status());
        assertEquals("RESULT_UNKNOWN", unknown.error().failureClass());
        assertEquals(true, unknown.task().continuation().get("submitOutcomeUnknown"));
        assertEquals(CapabilityResultV1.Status.COMPLETED, reconciled.status());
        assertEquals(1, submitHits.get());
        assertEquals(1, reconcileHits.get());
    }

    private void handleTask(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/v1/tasks".equals(path)) {
            createHits.incrementAndGet();
            if (creationResponse.get() != null) { writeJson(exchange, 200, creationResponse.get()); return; }
            taskStatus.set("CREATED");
            writeTask(exchange, 202, "CREATED", false);
            return;
        }
        if (path.endsWith("/confirm")) {
            confirmHits.incrementAndGet();
            taskStatus.set("CONFIRMED");
            writeTask(exchange, 200, "CONFIRMED", false);
            return;
        }
        if (path.endsWith("/submit")) {
            submitHits.incrementAndGet();
            if (submitResponse.get() != null) {
                taskStatus.set("CONFIRMED");
                writeJson(exchange, 200, submitResponse.get());
                return;
            }
            if (disconnectOnSubmit.get()) {
                taskStatus.set("CONFIRMED");
                exchange.close();
                return;
            }
            String status = unknownOnSubmit.get() ? "UNKNOWN" : submittedState.get();
            taskStatus.set(status);
            writeTask(exchange, 200, status, "COMPLETED".equals(status));
            return;
        }
        if (path.endsWith("/reconcile")) {
            reconcileHits.incrementAndGet();
            taskStatus.set("COMPLETED");
            writeTask(exchange, 200, "COMPLETED", true);
            return;
        }
        if (observationUnavailable.get()) {
            writeJson(exchange, 503, "{\"status\":\"FAILED\",\"data\":null}");
            return;
        }
        writeTask(exchange, 200, taskStatus.get(), "COMPLETED".equals(taskStatus.get()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"status\":\"SUCCESS\",\"data\":[]}", "{\"status\":\"SUCCESS\",\"data\":{}}"})
    void creationAcknowledgementLossDoesNotInventTaskOrInputCollectionProof(String broken) {
        creationResponse.set(broken);
        var request = request(Map.of("fromAccountId", "acct-001", "beneficiaryId", "bene-002", "amount", "0.01"),
                "original-create-key", null, Map.of(), null);
        var result = provider.executeTask(request);
        assertEquals(CapabilityResultV1.Status.NEED_USER, result.status());
        assertEquals("TASK_CREATION_UNOBSERVED", result.error().sourceCode());
        org.junit.jupiter.api.Assertions.assertNull(result.task());
        assertTrue(provider.collectableInputs(request, result).isEmpty());
        assertEquals(1, createHits.get()); assertEquals(0, confirmHits.get()); assertEquals(0, submitHits.get());
    }

    @Test void missingFieldsAreCollectedBeforeAnyBankTaskOrAuthentication() {
        var request = request(Map.of("fromAccountId", "acct-001"), "key", null, Map.of(), null);
        var result = provider.executeTask(request);
        assertEquals(CapabilityResultV1.Status.NEED_USER, result.status());
        assertEquals(Set.of("beneficiaryId", "amount"), provider.collectableInputs(request, result));
        assertEquals(0, createHits.get()); assertEquals(0, stepUpHits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer.internal.create", "channel.preferences.update"})
    void malformedSubmitResponsesStayUnknownAndRetainReconciliation(String capability) {
        capabilityId = capability;
        for (String bad : List.of("not json", "{\"status\":\"SUCCESS\",\"data\":[]}",
                "{\"status\":\"SUCCESS\",\"data\":null}",
                "{\"status\":\"SUCCESS\",\"data\":{\"status\":\"COMPLETED\",\"taskId\":\"wrong\"}}")) {
            taskStatus.set("CONFIRMED");
            submitResponse.set(bad);
            CapabilityResultV1 unknown = provider.executeTask(resumeRequest(true, Map.of()));
            assertEquals(CapabilityResultV1.Status.UNKNOWN, unknown.status());
            assertEquals("task-transfer-001", unknown.task().taskRef());
            assertEquals(true, unknown.task().continuation().get("submitOutcomeUnknown"));
            int submissions = submitHits.get();
            observationUnavailable.set(true);
            CapabilityResultV1 failedObservation = provider.executeTask(resumeRequest(false, unknown.task().continuation()));
            assertEquals(CapabilityResultV1.Status.UNKNOWN, failedObservation.status());
            assertEquals(unknown.task().continuation(), failedObservation.task().continuation());
            observationUnavailable.set(false);
            submitResponse.set(null);
            CapabilityResultV1 recovered = provider.executeTask(resumeRequest(false, failedObservation.task().continuation()));
            assertEquals(CapabilityResultV1.Status.COMPLETED, recovered.status());
            assertEquals(submissions, submitHits.get());
            assertEquals(0, createHits.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer.internal.create", "channel.preferences.update"})
    void allBankTerminalAndPendingStatesAreMappedExplicitly(String capability) {
        capabilityId = capability;
        for (String state : List.of("FAILED_FINAL", "FAILED", "CANCELLED", "PROCESSING", "UNKNOWN", "UNRECOGNIZED")) {
            taskStatus.set("CONFIRMED");
            submittedState.set(state);
            CapabilityResultV1 result = provider.executeTask(resumeRequest(true, Map.of()));
            CapabilityResultV1.Status expected = Set.of("FAILED_FINAL", "FAILED", "CANCELLED").contains(state)
                    ? CapabilityResultV1.Status.FAILED : "PROCESSING".equals(state)
                    ? CapabilityResultV1.Status.PROCESSING : CapabilityResultV1.Status.UNKNOWN;
            assertEquals(expected, result.status(), state);
            assertEquals("task-transfer-001", result.task().taskRef());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"transfer.internal.create", "channel.preferences.update"})
    void progressChecksNeverConfirmOrSubmitEvenWhenBankAlreadyConfirmed(String capability) {
        capabilityId = capability;
        for (String state : List.of("CREATED", "CONFIRMED")) {
            taskStatus.set(state);
            assertEquals(CapabilityResultV1.Status.NEED_CONFIRMATION,
                    provider.executeTask(resumeRequest(false, Map.of())).status());
        }
        assertEquals(0, submitHits.get());
        assertEquals(0, confirmHits.get());
    }

    private CapabilityRequestV1 resumeRequest(boolean confirmed, Map<String, Object> continuation) {
        CapabilityRequestV1 base = request(Map.of(), "idem-original-task", "task-transfer-001", continuation,
                confirmed ? new CapabilityRequestV1.Confirmation(true, "Host approval", Instant.now()) : null);
        return new CapabilityRequestV1(base.schemaVersion(), capabilityId, base.operation(), base.context(),
                base.parameters(), base.idempotencyKey(), base.taskRef(), base.continuation(), base.confirmation());
    }

    private static CapabilityRequestV1 request(
            Map<String, Object> parameters,
            String idempotencyKey,
            String taskRef,
            Map<String, Object> continuation,
            CapabilityRequestV1.Confirmation confirmation) {
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-transfer-001",
                "trace-transfer-001",
                "customer-demo-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "mobile-banking",
                "TRANSFER",
                "customer-demo-001",
                "session-transfer-001",
                FinanceContextV1.AuthenticationLevel.BASIC,
                Set.of("transfer:write"));
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                NonAiBankingProvider.TRANSFER_INTERNAL_CREATE,
                CapabilityRequestV1.Operation.TASK,
                context,
                parameters,
                idempotencyKey,
                taskRef,
                continuation,
                confirmation);
    }

    private void writeTask(
            HttpExchange exchange,
            int statusCode,
            String status,
            boolean includeResult) throws IOException {
        String result = includeResult
                ? ",\"result\":{\"transferId\":\"transfer-001\",\"receiptId\":\"receipt-001\"}"
                : "";
        writeJson(exchange, statusCode, """
                {"requestId":"req-task","traceId":"trace-task","status":"SUCCESS",
                 "data":{"taskId":"task-transfer-001","capabilityId":"%s",
                 "status":"%s","operation":"SUBMIT"%s},
                 "asOf":"2026-09-03T01:00:03Z","sourceSystem":"task-store-simulator","error":null}
                """.formatted(capabilityId, status, result));
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
