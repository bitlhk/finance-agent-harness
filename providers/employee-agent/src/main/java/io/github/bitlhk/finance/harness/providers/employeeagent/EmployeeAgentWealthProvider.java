package io.github.bitlhk.finance.harness.providers.employeeagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.InvocationAccess;

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

/** Reference adapter from the canonical wealth capability to Employee Agent Platform MCP. */
public final class EmployeeAgentWealthProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String PROVIDER_ID = "employee-agent-reference";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    public static final String WEALTH_PREVISIT_CONTEXT_QUERY = "wealth.previsit-context.query";
    public static final String HOST_TOOL = "prepare_wealth_previsit_context";

    private static final String ENDPOINT = "/api/internal/platform-tools/mcp";
    static final String CONTENT_PREFIX = "EA_WEALTH_PREVISIT_CONTEXT:";
    private static final ObjectMapper JSON = HarnessJson.mapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    private final Config config;
    private final HttpClient http;

    public EmployeeAgentWealthProvider(Config config) {
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
                        WEALTH_PREVISIT_CONTEXT_QUERY, CapabilityRequestV1.Operation.QUERY)));
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        CapabilityResultV1 localFailure = validate(request);
        if (localFailure != null) {
            return localFailure;
        }

        try {
            checkAccess(request);
            return callPrevisitTool(request, requiredParameter(request.parameters(), "customerRef"));
        } catch (SecurityException denied) {
            return failure(request, "NOT_AUTHORIZED", "EA_IDENTITY_BINDING_REJECTED", false,
                    "Host identity does not match verified EA binding", List.of("VERIFY_HOST_BINDING"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(request, "TEMPORARY_FAILURE", "REQUEST_INTERRUPTED", true,
                    "Employee Agent request was interrupted", List.of("RETRY"));
        } catch (Exception exception) {
            return failure(request, "TEMPORARY_FAILURE", "EA_TRANSPORT_FAILURE", true,
                    "Employee Agent request could not be validated", List.of("RETRY"));
        }
    }

    @Override
    public void checkAccess(CapabilityRequestV1 request) {
        EmployeeAgentIdentityBinding binding = config.identityBinding().get();
        if (binding == null || !binding.expiresAt().isAfter(Instant.now())
                || !binding.runtime().equals(config.runtimeIdentity())
                || !binding.principalId().equals(request.context().principalId())
                || request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !Objects.equals(request.context().subjectRef(), request.parameters().get("customerRef"))) {
            throw new SecurityException("EA_IDENTITY_BINDING_REJECTED");
        }
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        return failure(request, "PERMANENT_FAILURE", "TASK_NOT_IMPLEMENTED", false,
                "Employee Agent reference provider only exposes read-only wealth context", List.of("USE_QUERY"));
    }

    private CapabilityResultV1 validate(CapabilityRequestV1 request) {
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "query requires operation QUERY", List.of("USE_QUERY_OPERATION"));
        }
        if (!WEALTH_PREVISIT_CONTEXT_QUERY.equals(request.capabilityId())) {
            return failure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                    "unsupported query capability: " + request.capabilityId(), List.of("CHECK_CAPABILITY_ID"));
        }
        if (request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE) {
            return failure(request, "NOT_AUTHORIZED", "EMPLOYEE_PRINCIPAL_REQUIRED", false,
                    "wealth previsit context requires an employee principal", List.of("USE_EMPLOYEE_PROFILE"));
        }
        if (optionalParameter(request.parameters(), "customerRef") == null) {
            return failure(request, "INVALID_INPUT", "CUSTOMER_REF_REQUIRED", false,
                    "customerRef is required", List.of("SELECT_AUTHORIZED_CUSTOMER"));
        }
        return null;
    }

    private CapabilityResultV1 callPrevisitTool(CapabilityRequestV1 request, String customerRef)
            throws Exception {
        String runtimeToken = requiredText(config.runtimeTokenSupplier().tokenFor(request.context()),
                "runtime token");
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "id", request.context().requestId(),
                "method", "tools/call",
                "params", Map.of(
                        "name", HOST_TOOL,
                        "arguments", Map.of("customer_id", customerRef)));
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
                    "Employee Agent returned HTTP " + response.statusCode(), List.of("CHECK_EA_RUNTIME"));
        }
        JsonNode rpcError = root.get("error");
        if (rpcError != null && !rpcError.isNull()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_MCP_ERROR", false,
                    "Employee Agent MCP call failed", List.of("CHECK_EA_TOOL_RESULT"));
        }

        JsonNode result = root.path("result");
        String text = result.path("content").isArray()
                ? textContent(result.path("content"))
                : "";
        if (text.isBlank()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_RESULT_MISSING", false,
                    "Employee Agent MCP result contains no text", List.of("CHECK_EA_TOOL_RESULT"));
        }
        if (!text.startsWith(CONTENT_PREFIX)) {
            return failure(request, "UPSTREAM_FAILURE", "EA_RESULT_CONTRACT_MISMATCH", false,
                    "Employee Agent returned an unexpected wealth result", List.of("CHECK_EA_TOOL_VERSION"));
        }

        JsonNode previsit = JSON.readTree(text.substring(CONTENT_PREFIX.length()));
        if (!previsit.isObject()) {
            return failure(request, "UPSTREAM_FAILURE", "EA_CONTEXT_INVALID", false,
                    "Employee Agent wealth context is not an object", List.of("CHECK_EA_TOOL_RESULT"));
        }
        if (result.path("isError").asBoolean(false)) {
            return failure(request, "UPSTREAM_FAILURE", "EA_WEALTH_CONTEXT_BLOCKED", false,
                    "Employee Agent could not provide authorized wealth context",
                    List.of("FOLLOW_EA_REMEDIATION"));
        }

        for (String returned : List.of(previsit.path("customer").path("customerId").asText(""),
                previsit.path("evidence").path("customerId").asText(""))) {
            if (!returned.isBlank() && !returned.equals(request.context().subjectRef())) {
                return failure(request, "NOT_AUTHORIZED", "EA_RESULT_SUBJECT_MISMATCH", false,
                        "Returned customer does not match the authorized subject", List.of("VERIFY_HOST_BINDING"));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("previsitContext", JSON.convertValue(previsit, Map.class));
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
                previsit.path("message").asText("财富客户访前上下文已准备。"),
                data,
                stringList(previsit.get("missingInputs")),
                remediation(previsit),
                "employee-agent-platform-mcp",
                receiptInstant(receipt),
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

    private static Instant receiptInstant(JsonNode receipt) {
        String value = receipt == null ? "" : receipt.path("createdAt").asText("");
        try {
            return value.isBlank() ? Instant.now() : Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }

    private static List<String> remediation(JsonNode previsit) {
        JsonNode readiness = previsit.path("readiness");
        List<String> values = stringList(readiness.get("remediation"));
        if (!values.isEmpty()) {
            return values;
        }
        return stringList(previsit.get("nextActions"));
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
                "当前无法获取财富客户访前上下文。",
                Map.of(),
                List.of(),
                nextActions,
                "employee-agent-platform-mcp",
                Instant.now(),
                null,
                new CapabilityResultV1.Failure(
                        failureClass,
                        sourceCode,
                        retryable,
                        message,
                        List.of()));
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
            Duration timeout,
            java.util.function.Supplier<EmployeeAgentIdentityBinding> identityBinding) {
        public Config(URI baseUri, EmployeeAgentRuntimeIdentity runtimeIdentity,
                      EmployeeAgentRuntimeTokenSupplier runtimeTokenSupplier, Duration timeout) {
            this(baseUri, runtimeIdentity, runtimeTokenSupplier, timeout, () -> null);
        }
        public Config {
            baseUri = normalizeBaseUri(Objects.requireNonNull(baseUri, "baseUri"));
            Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
            Objects.requireNonNull(runtimeTokenSupplier, "runtimeTokenSupplier");
            Objects.requireNonNull(identityBinding, "identityBinding");
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
