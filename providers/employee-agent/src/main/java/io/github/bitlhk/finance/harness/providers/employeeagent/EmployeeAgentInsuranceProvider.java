package io.github.bitlhk.finance.harness.providers.employeeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reference adapter from insurance context query to the Employee Agent customer-context MCP. */
public final class EmployeeAgentInsuranceProvider implements DescribedFinanceProvider {
    public static final String PROVIDER_ID = "employee-agent-insurance-reference";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    public static final String INSURANCE_CUSTOMER_CONTEXT_QUERY =
            "insurance.customer-context.query";
    public static final String HOST_TOOL = "get_customer_profile";

    private static final String ENDPOINT = "/api/internal/customer-context/mcp";
    private static final String SOURCE_SYSTEM = "employee-agent-customer-context-mcp";
    private static final ObjectMapper JSON = HarnessJson.mapper();

    private final Config config;
    private final HttpClient http;

    public EmployeeAgentInsuranceProvider(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION,
                PROVIDER_ID,
                PROVIDER_VERSION,
                List.of(new ProviderDescriptorV1.CapabilitySupport(
                        INSURANCE_CUSTOMER_CONTEXT_QUERY,
                        CapabilityRequestV1.Operation.QUERY)));
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        CapabilityResultV1 localFailure = validate(request);
        if (localFailure != null) {
            return localFailure;
        }
        try {
            return callCustomerContext(request, requiredParameter(request.parameters(), "customerRef"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(request, "TEMPORARY_FAILURE", "REQUEST_INTERRUPTED", true,
                    "Employee Agent request was interrupted", List.of("RETRY"));
        } catch (Exception exception) {
            return failure(request, "TEMPORARY_FAILURE", "EA_TRANSPORT_FAILURE", true,
                    "Employee Agent request failed: " + safeMessage(exception), List.of("RETRY"));
        }
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        return failure(request, "PERMANENT_FAILURE", "TASK_NOT_IMPLEMENTED", false,
                "Employee Agent insurance reference provider only exposes read-only customer context",
                List.of("USE_QUERY"));
    }

    private CapabilityResultV1 validate(CapabilityRequestV1 request) {
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "query requires operation QUERY", List.of("USE_QUERY_OPERATION"));
        }
        if (!INSURANCE_CUSTOMER_CONTEXT_QUERY.equals(request.capabilityId())) {
            return failure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                    "unsupported query capability: " + request.capabilityId(),
                    List.of("CHECK_CAPABILITY_ID"));
        }
        if (request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE) {
            return failure(request, "NOT_AUTHORIZED", "EMPLOYEE_PRINCIPAL_REQUIRED", false,
                    "insurance customer context requires an employee principal",
                    List.of("USE_EMPLOYEE_PROFILE"));
        }
        if (optionalParameter(request.parameters(), "customerRef") == null) {
            return failure(request, "INVALID_INPUT", "CUSTOMER_REF_REQUIRED", false,
                    "customerRef is required", List.of("SELECT_AUTHORIZED_CUSTOMER"));
        }
        return null;
    }

    private CapabilityResultV1 callCustomerContext(
            CapabilityRequestV1 request,
            String customerRef) throws Exception {
        String runtimeToken = requiredText(config.runtimeTokenSupplier().tokenFor(request.context()),
                "runtime token");
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("customer_id", customerRef);
        if (request.taskRef() != null) {
            arguments.put("task_id", request.taskRef());
        }
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", request.context().requestId(),
                "method", "tools/call",
                "params", Map.of("name", HOST_TOOL, "arguments", arguments));
        HttpRequest.Builder builder = HttpRequest.newBuilder(config.baseUri().resolve(ENDPOINT))
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + runtimeToken)
                .header("X-Linggan-Runtime-Id", config.runtimeIdentity().runtimeId())
                .header("X-Linggan-Agent-Id", config.runtimeIdentity().agentId())
                .header("X-Agent-Adopt-Id", config.runtimeIdentity().adoptId())
                .header("X-Jiuwen-Channel-Id", config.runtimeIdentity().adoptId())
                .header("X-Request-Id", request.context().requestId())
                .header("X-Correlation-Id", request.context().traceId());
        if (request.context().sessionRef() != null) {
            builder.header("X-Linggan-Session-Id", request.context().sessionRef());
        }

        HttpResponse<String> response = http.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(
                                JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return parseResponse(request, response);
    }

    private static CapabilityResultV1 parseResponse(
            CapabilityRequestV1 request,
            HttpResponse<String> response) throws Exception {
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return failure(request, "NOT_AUTHENTICATED", "EA_RUNTIME_IDENTITY_REJECTED", false,
                    "Employee Agent rejected the runtime identity", List.of("REFRESH_RUNTIME_TOKEN"));
        }
        if (body.isBlank()) {
            return failure(request, "TEMPORARY_FAILURE", "EA_EMPTY_RESPONSE", true,
                    "Employee Agent returned an empty response", List.of("RETRY"));
        }
        JsonNode root = JSON.readTree(body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return failure(request, "TEMPORARY_FAILURE", "EA_HTTP_" + response.statusCode(),
                    response.statusCode() >= 500,
                    "Employee Agent returned HTTP " + response.statusCode(),
                    List.of("CHECK_EA_RUNTIME"));
        }
        JsonNode rpcError = root.get("error");
        if (rpcError != null && !rpcError.isNull()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_MCP_ERROR", false,
                    rpcError.path("message").asText("Employee Agent MCP call failed"),
                    List.of("CHECK_EA_TOOL_RESULT"));
        }

        JsonNode result = root.path("result");
        String text = result.path("content").isArray()
                ? textContent(result.path("content"))
                : "";
        if (text.isBlank()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_RESULT_MISSING", false,
                    "Employee Agent MCP result contains no text", List.of("CHECK_EA_TOOL_RESULT"));
        }
        JsonNode customerContext = JSON.readTree(text);
        if (!customerContext.isObject()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_CONTEXT_INVALID", false,
                    "Employee Agent customer context is not an object",
                    List.of("CHECK_EA_TOOL_RESULT"));
        }
        if (result.path("isError").asBoolean(false)) {
            return failure(request, "UPSTREAM_FAILURE", "EA_INSURANCE_CONTEXT_BLOCKED", false,
                    "Employee Agent could not provide insurance customer context",
                    remediation(customerContext));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerContext", JSON.convertValue(customerContext, Map.class));
        JsonNode receipt = result.path("_meta").path("eaContextReceipt");
        Map<String, Object> hostEvidence = compactReceipt(receipt);
        if (!hostEvidence.isEmpty()) {
            data.put("hostEvidence", hostEvidence);
        }
        data.put("hostCapability", HOST_TOOL);

        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                "保险客户上下文已准备。",
                data,
                stringList(customerContext.get("missingRequiredFields")),
                remediation(customerContext),
                SOURCE_SYSTEM,
                receiptInstant(receipt, customerContext),
                null,
                null);
    }

    private static String textContent(JsonNode content) {
        List<String> texts = new ArrayList<>();
        for (JsonNode item : content) {
            if ("text".equals(item.path("type").asText()) && !item.path("text").asText().isBlank()) {
                texts.add(item.path("text").asText());
            }
        }
        return String.join("\n", texts).trim();
    }

    private static Map<String, Object> compactReceipt(JsonNode receipt) {
        if (receipt == null || !receipt.isObject()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        copyText(receipt, compact, "schema");
        copyText(receipt, compact, "receiptId");
        copyText(receipt, compact, "taskId");
        copyText(receipt, compact, "receiptFingerprint");
        copyText(receipt, compact, "createdAt");
        JsonNode readiness = receipt.get("readiness");
        if (readiness != null && readiness.isObject()) {
            Map<String, Object> readinessSummary = new LinkedHashMap<>();
            copyText(readiness, readinessSummary, "status");
            copyText(readiness, readinessSummary, "decisionFingerprint");
            if (!readinessSummary.isEmpty()) {
                compact.put("readiness", readinessSummary);
            }
        }
        return compact;
    }

    private static void copyText(JsonNode source, Map<String, Object> target, String field) {
        String value = source.path(field).asText("").trim();
        if (!value.isEmpty()) {
            target.put(field, value);
        }
    }

    private static Instant receiptInstant(JsonNode receipt, JsonNode context) {
        String value = receipt == null ? "" : receipt.path("createdAt").asText("");
        if (value.isBlank()) {
            value = context.path("generatedAt").asText("");
        }
        try {
            return value.isBlank() ? Instant.now() : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    private static List<String> remediation(JsonNode customerContext) {
        List<String> values = stringList(customerContext.get("remediation"));
        if (!values.isEmpty()) {
            return values;
        }
        if ("BLOCKED".equals(customerContext.path("status").asText())) {
            return List.of("请确认当前保险岗位、业务用途和客户授权范围。");
        }
        if ("DEGRADED".equals(customerContext.path("status").asText())) {
            return List.of("补齐缺失的客户保障需求事实后再形成具体建议。");
        }
        return List.of();
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String requiredParameter(Map<String, Object> parameters, String name) {
        String value = optionalParameter(parameters, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String optionalParameter(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static CapabilityResultV1 failure(
            CapabilityRequestV1 request,
            String failureClass,
            String sourceCode,
            boolean retryable,
            String message,
            List<String> nextActions) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.FAILED,
                "当前无法获取保险客户上下文。",
                Map.of(),
                List.of(),
                nextActions,
                SOURCE_SYSTEM,
                Instant.now(),
                null,
                new CapabilityResultV1.Failure(
                        failureClass, sourceCode, retryable, message, List.of()));
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }

    public record Config(
            URI baseUri,
            EmployeeAgentRuntimeIdentity runtimeIdentity,
            EmployeeAgentRuntimeTokenSupplier runtimeTokenSupplier,
            Duration timeout) {
        public Config {
            baseUri = normalizeBaseUri(Objects.requireNonNull(baseUri, "baseUri"));
            Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
            Objects.requireNonNull(runtimeTokenSupplier, "runtimeTokenSupplier");
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }

        private static URI normalizeBaseUri(URI uri) {
            String value = uri.toString();
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return URI.create(value);
        }
    }
}
