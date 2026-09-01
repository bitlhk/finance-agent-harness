package io.github.bitlhk.finance.harness.providers.employeeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeAgentWealthProviderTest {
    private static final String TOKEN = "one-time-runtime-token";

    private HttpServer server;
    private EmployeeAgentWealthProvider provider;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicReference<JsonNode> observedBody = new AtomicReference<>();
    private final AtomicReference<String> observedAuthorization = new AtomicReference<>();
    private final AtomicReference<String> observedAgentId = new AtomicReference<>();
    private final AtomicReference<String> observedAdoptId = new AtomicReference<>();
    private final AtomicReference<String> observedSessionId = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform-tools/mcp", this::handleMcp);
        server.start();
        provider = new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new EmployeeAgentWealthProvider.RuntimeIdentity(
                        "finance-harness-test", "jiuwen_lgj-wealth", "lgj-wealth"),
                context -> {
                    tokenRequests.incrementAndGet();
                    return TOKEN;
                },
                Duration.ofSeconds(5)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsCanonicalQueryToEaMcpAndReturnsCompactHostEvidence() {
        CapabilityResultV1 result = provider.query(request("C-005"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null ? result.content() : result.error().message());
        assertEquals("employee-agent-platform-mcp", result.sourceSystem());
        assertTrue(result.data().get("previsitContext") instanceof Map<?, ?> context
                && "ready".equals(context.get("status")));
        assertTrue(result.data().get("hostEvidence") instanceof Map<?, ?> evidence
                && "WM-GT-01".equals(evidence.get("taskId")));
        assertEquals(EmployeeAgentWealthProvider.HOST_TOOL, result.data().get("hostCapability"));
        assertEquals(1, hits.get());
        assertEquals(1, tokenRequests.get());
        assertEquals("Bearer " + TOKEN, observedAuthorization.get());
        assertEquals("jiuwen_lgj-wealth", observedAgentId.get());
        assertEquals("lgj-wealth", observedAdoptId.get());
        assertEquals("session-wealth-001", observedSessionId.get());

        JsonNode body = observedBody.get();
        assertEquals("tools/call", body.path("method").asText());
        assertEquals(EmployeeAgentWealthProvider.HOST_TOOL,
                body.path("params").path("name").asText());
        assertEquals("C-005", body.path("params").path("arguments").path("customer_id").asText());
        assertFalse(HarnessJson.mapper().valueToTree(result).toString().contains(TOKEN));
    }

    @Test
    void mapsValidDegradedContextAsUsableResult() {
        CapabilityResultV1 result = provider.query(request("DEGRADED"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(),
                result.error() == null ? result.content() : result.error().message());
        assertTrue(result.data().get("previsitContext") instanceof Map<?, ?> context
                && "degraded".equals(context.get("status")));
        assertEquals(java.util.List.of("补充客户持仓数据"), result.nextActions());
    }

    @Test
    void mapsMcpToolErrorToCanonicalFailure() {
        CapabilityResultV1 result = provider.query(request("BLOCKED"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertNotNull(result.error());
        assertEquals("EA_WEALTH_CONTEXT_BLOCKED", result.error().sourceCode(), result.error().message());
        assertFalse(result.error().retryable());
    }

    @Test
    void mapsRuntimeIdentityRejectionWithoutLeakingCredential() {
        CapabilityResultV1 result = provider.query(request("UNAUTHORIZED"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("EA_RUNTIME_IDENTITY_REJECTED", result.error().sourceCode());
        assertFalse(HarnessJson.mapper().valueToTree(result).toString().contains(TOKEN));
    }

    @Test
    void mapsJsonRpcErrorToCanonicalFailure() {
        CapabilityResultV1 result = provider.query(request("RPC_ERROR"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("EA_MCP_ERROR", result.error().sourceCode());
        assertEquals("EA tool unavailable", result.error().message());
    }

    @Test
    void rejectsNonEmployeePrincipalWithoutNetworkOrToken() {
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.CUSTOMER),
                Map.of("customerRef", "C-005"),
                null,
                "WM-GT-01",
                Map.of(),
                null);

        CapabilityResultV1 result = provider.query(request);

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("EMPLOYEE_PRINCIPAL_REQUIRED", result.error().sourceCode());
        assertEquals(0, hits.get());
        assertEquals(0, tokenRequests.get());
    }

    @Test
    void rejectsMissingCustomerAndUnsupportedCapabilityWithoutNetwork() {
        CapabilityRequestV1 missingCustomer = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.EMPLOYEE),
                Map.of(),
                null,
                "WM-GT-01",
                Map.of(),
                null);
        CapabilityRequestV1 unsupported = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                "wealth.unknown.query",
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.EMPLOYEE),
                Map.of("customerRef", "C-005"),
                null,
                "WM-GT-01",
                Map.of(),
                null);

        assertEquals("CUSTOMER_REF_REQUIRED", provider.query(missingCustomer).error().sourceCode());
        assertEquals("UNSUPPORTED_CAPABILITY", provider.query(unsupported).error().sourceCode());
        assertEquals(0, hits.get());
        assertEquals(0, tokenRequests.get());
    }

    private void handleMcp(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        observedAgentId.set(exchange.getRequestHeaders().getFirst("X-Linggan-Agent-Id"));
        observedAdoptId.set(exchange.getRequestHeaders().getFirst("X-Agent-Adopt-Id"));
        observedSessionId.set(exchange.getRequestHeaders().getFirst("X-Linggan-Session-Id"));
        JsonNode body = HarnessJson.mapper().readTree(exchange.getRequestBody());
        observedBody.set(body);
        String customer = body.path("params").path("arguments").path("customer_id").asText();
        if ("UNAUTHORIZED".equals(customer)) {
            writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
            return;
        }
        if ("RPC_ERROR".equals(customer)) {
            writeJson(exchange, 200, """
                    {"jsonrpc":"2.0","id":"request-wealth-001",
                     "error":{"code":-32000,"message":"EA tool unavailable"}}
                    """);
            return;
        }
        if ("BLOCKED".equals(customer)) {
            writeMcpResult(exchange, true, Map.of(
                    "status", "blocked",
                    "message", "当前岗位无权读取该客户资料。"), Map.of());
            return;
        }
        if ("DEGRADED".equals(customer)) {
            writeMcpResult(exchange, false, Map.of(
                    "status", "degraded",
                    "message", "可以先形成通用访前清单。",
                    "readiness", Map.of("remediation", java.util.List.of("补充客户持仓数据"))), Map.of());
            return;
        }
        writeMcpResult(exchange, false, Map.of(
                "schema", "ea.wealth-previsit-context.v1",
                "status", "ready",
                "message", "访前资料已准备。",
                "customer", Map.of("customerId", "C-005", "name", "陈明")), Map.of(
                "schema", "ea.context-receipt.v1",
                "receiptId", "crpt-001",
                "taskId", "WM-GT-01",
                "receiptFingerprint", "a".repeat(64),
                "createdAt", "2026-09-01T02:00:00Z",
                "readiness", Map.of("status", "READY", "decisionFingerprint", "b".repeat(64))));
    }

    private static void writeMcpResult(
            HttpExchange exchange,
            boolean isError,
            Map<String, Object> previsitContext,
            Map<String, Object> receipt) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("isError", isError);
        result.put("content", java.util.List.of(Map.of(
                "type", "text",
                "text", EmployeeAgentWealthProvider.CONTENT_PREFIX
                        + HarnessJson.mapper().writeValueAsString(previsitContext))));
        if (!receipt.isEmpty()) {
            result.put("_meta", Map.of("eaContextReceipt", receipt));
        }
        writeJson(exchange, 200, HarnessJson.mapper().writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-wealth-001",
                "result", result)));
    }

    private static CapabilityRequestV1 request(String customerRef) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.EMPLOYEE),
                Map.of("customerRef", customerRef),
                null,
                "WM-GT-01",
                Map.of(),
                null);
    }

    private static FinanceContextV1 context(FinanceContextV1.PrincipalType principalType) {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-wealth-001",
                "trace-wealth-001",
                "employee-001",
                principalType,
                "employee-agent",
                "WEALTH_PREVISIT",
                "C-005",
                "session-wealth-001",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:customer:read"));
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
