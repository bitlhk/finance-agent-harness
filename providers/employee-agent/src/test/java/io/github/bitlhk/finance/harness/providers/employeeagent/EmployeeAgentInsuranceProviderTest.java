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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeAgentInsuranceProviderTest {
    private HttpServer server;
    private EmployeeAgentInsuranceProvider provider;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<JsonNode> observedBody = new AtomicReference<>();
    private final AtomicReference<String> observedAuthorization = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/customer-context/mcp", this::handleMcp);
        server.start();
        provider = new EmployeeAgentInsuranceProvider(new EmployeeAgentInsuranceProvider.Config(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                new EmployeeAgentRuntimeIdentity(
                        "finance-harness-test", "jiuwen_lgj-insurance", "lgj-insurance"),
                context -> "insurance-runtime-token",
                Duration.ofSeconds(5)));
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsCanonicalInsuranceQueryToGovernedEaCustomerContext() {
        CapabilityResultV1 result = provider.query(request("C-INS-001"));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("employee-agent-customer-context-mcp", result.sourceSystem());
        assertTrue(result.data().get("customerContext") instanceof Map<?, ?> context
                && "READY".equals(context.get("status")));
        assertEquals(EmployeeAgentInsuranceProvider.HOST_TOOL, result.data().get("hostCapability"));
        assertTrue(result.data().get("hostEvidence") instanceof Map<?, ?> evidence
                && "IA-GT-01".equals(evidence.get("taskId")));
        assertEquals("Bearer insurance-runtime-token", observedAuthorization.get());
        assertEquals(1, hits.get());

        JsonNode body = observedBody.get();
        assertEquals(EmployeeAgentInsuranceProvider.HOST_TOOL,
                body.path("params").path("name").asText());
        assertEquals("C-INS-001",
                body.path("params").path("arguments").path("customer_id").asText());
        assertEquals("IA-GT-01",
                body.path("params").path("arguments").path("task_id").asText());
        assertFalse(HarnessJson.mapper().valueToTree(result).toString()
                .contains("insurance-runtime-token"));
    }

    @Test
    void mapsBlockedProjectionToStructuredFailure() {
        CapabilityResultV1 result = provider.query(request("BLOCKED"));

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("EA_INSURANCE_CONTEXT_BLOCKED", result.error().sourceCode());
        assertEquals(List.of("请选择授权客户"), result.nextActions());
    }

    @Test
    void rejectsCustomerPrincipalBeforeCallingEa() {
        CapabilityRequestV1 request = new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentInsuranceProvider.INSURANCE_CUSTOMER_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.CUSTOMER),
                Map.of("customerRef", "C-INS-001"),
                null,
                "IA-GT-01",
                Map.of(),
                null);

        CapabilityResultV1 result = provider.query(request);

        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("EMPLOYEE_PRINCIPAL_REQUIRED", result.error().sourceCode());
        assertEquals(0, hits.get());
    }

    private void handleMcp(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        observedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        JsonNode body = HarnessJson.mapper().readTree(exchange.getRequestBody());
        observedBody.set(body);
        String customerRef = body.path("params").path("arguments").path("customer_id").asText();
        boolean blocked = "BLOCKED".equals(customerRef);
        Map<String, Object> projection = blocked
                ? Map.of(
                        "schemaVersion", "ea.customer-profile-projection-result.v1",
                        "status", "BLOCKED",
                        "reasonCodes", List.of("SUBJECT_OUT_OF_SCOPE"),
                        "remediation", List.of("请选择授权客户"),
                        "fields", List.of())
                : Map.of(
                        "schemaVersion", "ea.customer-profile-projection-result.v1",
                        "status", "READY",
                        "subjectId", customerRef,
                        "purposeId", "insurance_advisory",
                        "generatedAt", "2026-09-03T03:00:00Z",
                        "missingRequiredFields", List.of(),
                        "fields", List.of(Map.of(
                                "path", "subject.displayName",
                                "value", "林女士")));
        Map<String, Object> receipt = Map.of(
                "schema", "ea.context-receipt.v1",
                "receiptId", "crpt-insurance-001",
                "taskId", "IA-GT-01",
                "receiptFingerprint", "a".repeat(64),
                "createdAt", "2026-09-03T03:00:00Z",
                "readiness", Map.of(
                        "status", blocked ? "BLOCKED" : "READY",
                        "decisionFingerprint", "b".repeat(64)));
        Map<String, Object> result = Map.of(
                "isError", blocked,
                "content", List.of(Map.of(
                        "type", "text",
                        "text", HarnessJson.mapper().writeValueAsString(projection))),
                "_meta", Map.of("eaContextReceipt", receipt));
        writeJson(exchange, 200, HarnessJson.mapper().writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", "request-insurance-001",
                "result", result)));
    }

    private static CapabilityRequestV1 request(String customerRef) {
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                EmployeeAgentInsuranceProvider.INSURANCE_CUSTOMER_CONTEXT_QUERY,
                CapabilityRequestV1.Operation.QUERY,
                context(FinanceContextV1.PrincipalType.EMPLOYEE),
                Map.of("customerRef", customerRef),
                null,
                "IA-GT-01",
                Map.of(),
                null);
    }

    private static FinanceContextV1 context(FinanceContextV1.PrincipalType principalType) {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-insurance-001",
                "trace-insurance-001",
                "employee-insurance-001",
                principalType,
                "employee-agent",
                "INSURANCE_ADVISORY",
                "C-INS-001",
                "session-insurance-001",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("insurance:customer:read"));
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
