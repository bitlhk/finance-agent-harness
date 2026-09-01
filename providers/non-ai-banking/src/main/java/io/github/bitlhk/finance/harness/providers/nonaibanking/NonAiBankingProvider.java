package io.github.bitlhk.finance.harness.providers.nonaibanking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;

import java.net.URI;
import java.net.URLEncoder;
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
import java.util.concurrent.atomic.AtomicReference;

/** Reference Provider Adapter for the non-ai-banking simulator. */
public final class NonAiBankingProvider implements FinanceProvider {
    public static final String PROVIDER_ID = "non-ai-banking-reference";
    public static final String ACCOUNT_BALANCE_QUERY = "account.balance.query";
    public static final String WEALTH_CUSTOMER_CONTEXT_QUERY = "wealth.customer-context.query";

    private final Client client;

    public NonAiBankingProvider(Config config) {
        this.client = new Client(Objects.requireNonNull(config, "config"));
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return localFailure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "query requires operation QUERY", List.of("USE_QUERY_OPERATION"));
        }
        try {
            return switch (request.capabilityId()) {
                case ACCOUNT_BALANCE_QUERY -> accountBalance(request);
                case WEALTH_CUSTOMER_CONTEXT_QUERY -> wealthCustomerContext(request);
                default -> localFailure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                        "unsupported query capability: " + request.capabilityId(),
                        List.of("CHECK_CAPABILITY_ID"));
            };
        } catch (ProviderFailure failure) {
            return upstreamFailure(request, failure);
        }
    }

    private CapabilityResultV1 accountBalance(CapabilityRequestV1 request) {
        Map<String, String> query = new LinkedHashMap<>();
        copyQueryParameter(request.parameters(), query, "page");
        copyQueryParameter(request.parameters(), query, "pageSize");
        Response response = client.get("/api/v1/accounts", query, request.context());
        return completed(request, "账户余额查询已完成。", response.data(),
                response.sourceSystem(), response.asOf());
    }

    private CapabilityResultV1 wealthCustomerContext(CapabilityRequestV1 request) {
        Response aggregate = client.get("/api/v1/wealth/aggregate", Map.of(), request.context());
        Response riskAssessment = client.get("/api/v1/finance/risk-assessment", Map.of(), request.context());
        Instant oldestSourceAsOf = aggregate.asOf().isBefore(riskAssessment.asOf())
                ? aggregate.asOf()
                : riskAssessment.asOf();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("wealthAggregate", aggregate.data());
        data.put("riskAssessment", riskAssessment.data());
        data.put("sourceSnapshots", List.of(
                sourceSnapshot("WEALTH_AGGREGATE", aggregate),
                sourceSnapshot("RISK_ASSESSMENT", riskAssessment)));
        data.put("assembledAt", Instant.now().toString());
        return completed(request, "财富客户上下文查询已完成。", data,
                "non-ai-banking-wealth-context", oldestSourceAsOf);
    }

    private static Map<String, Object> sourceSnapshot(String section, Response response) {
        return Map.of(
                "section", section,
                "sourceSystem", response.sourceSystem(),
                "asOf", response.asOf().toString());
    }

    private static CapabilityResultV1 completed(
            CapabilityRequestV1 request,
            String content,
            Map<String, Object> data,
            String sourceSystem,
            Instant asOf) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                content,
                data,
                List.of(),
                List.of(),
                sourceSystem,
                asOf,
                null,
                null);
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        return localFailure(request, "PERMANENT_FAILURE", "TASK_NOT_IMPLEMENTED", false,
                "task execution is not implemented in this provider version", List.of("USE_QUERY"));
    }

    private static void copyQueryParameter(
            Map<String, Object> parameters,
            Map<String, String> query,
            String name) {
        Object value = parameters.get(name);
        if (value != null && !String.valueOf(value).isBlank()) {
            query.put(name, String.valueOf(value));
        }
    }

    private static CapabilityResultV1 upstreamFailure(CapabilityRequestV1 request, ProviderFailure failure) {
        FinanceContextV1 context = request.context();
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                context.requestId(),
                context.traceId(),
                CapabilityResultV1.Status.FAILED,
                "暂时无法完成查询，请按提示处理后重试。",
                Map.of(),
                List.of(),
                failure.nextActions(),
                fallback(failure.sourceSystem(), "non-ai-banking"),
                failure.asOf() == null ? Instant.now() : failure.asOf(),
                null,
                new CapabilityResultV1.Failure(
                        failure.failureClass(),
                        failure.sourceCode(),
                        failure.retryable(),
                        failure.getMessage(),
                        failure.fieldErrors()));
    }

    private static CapabilityResultV1 localFailure(
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
                "当前 Provider 无法处理该请求。",
                Map.of(),
                List.of(),
                nextActions,
                "non-ai-banking",
                Instant.now(),
                null,
                new CapabilityResultV1.Failure(
                        failureClass,
                        sourceCode,
                        retryable,
                        message,
                        List.of()));
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static final class Config {
        private final URI baseUri;
        private final String loginName;
        private final String loginSecret;
        private final String deviceId;
        private final String channel;
        private final String appVersion;
        private final Duration timeout;

        public Config(
                URI baseUri,
                String loginName,
                String loginSecret,
                String deviceId,
                String channel,
                String appVersion,
                Duration timeout) {
            this.baseUri = normalizeBaseUri(Objects.requireNonNull(baseUri, "baseUri"));
            this.loginName = requiredText(loginName, "loginName");
            this.loginSecret = requiredText(loginSecret, "loginSecret");
            this.deviceId = requiredText(deviceId, "deviceId");
            this.channel = requiredText(channel, "channel");
            this.appVersion = requiredText(appVersion, "appVersion");
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
        }

        public static Config simulator(URI baseUri, String loginName, String loginSecret) {
            return new Config(baseUri, loginName, loginSecret,
                    "finance-harness", "M", "finance-harness-v1", Duration.ofSeconds(20));
        }

        private static URI normalizeBaseUri(URI uri) {
            String value = uri.toString();
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return URI.create(value);
        }

        private static String requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }

    private static final class Client {
        private static final ObjectMapper JSON = HarnessJson.mapper();
        private static final String SESSION_HEADER = "X-Session-Id";

        private final Config config;
        private final HttpClient http;
        private final AtomicReference<String> sessionId = new AtomicReference<>();

        private Client(Config config) {
            this.config = config;
            this.http = HttpClient.newBuilder().connectTimeout(config.timeout).build();
        }

        private Response get(String path, Map<String, String> query, FinanceContextV1 context) {
            try {
                ensureSession(context);
                HttpResponse<String> response = sendGet(path, query, context, sessionId.get());
                if (response.statusCode() == 401) {
                    sessionId.set(null);
                    ensureSession(context);
                    response = sendGet(path, query, context, sessionId.get());
                }
                return parse(response, context);
            } catch (ProviderFailure failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw transportFailure(context, "REQUEST_INTERRUPTED", "upstream request interrupted");
            } catch (Exception exception) {
                throw transportFailure(context, "UPSTREAM_TRANSPORT",
                        "upstream request failed: " + safeMessage(exception));
            }
        }

        private void ensureSession(FinanceContextV1 context) throws Exception {
            if (sessionId.get() != null && !sessionId.get().isBlank()) {
                return;
            }
            Map<String, Object> body = Map.of(
                    "loginName", config.loginName,
                    "loginSecret", config.loginSecret,
                    "deviceId", config.deviceId,
                    "channel", config.channel);
            HttpRequest request = requestBuilder("/api/v1/sessions", context)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            Response response = parse(http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), context);
            Object value = response.data().get("sessionId");
            if (value == null || String.valueOf(value).isBlank()) {
                throw new ProviderFailure(
                        "NOT_AUTHENTICATED", "SESSION_MISSING", false,
                        "login response missing sessionId", List.of(), List.of("RELOGIN"),
                        response.requestId(), response.traceId(), response.sourceSystem(), response.asOf());
            }
            sessionId.set(String.valueOf(value));
        }

        private HttpResponse<String> sendGet(
                String path,
                Map<String, String> query,
                FinanceContextV1 context,
                String session) throws Exception {
            return http.send(requestBuilder(path + encodeQuery(query), context)
                            .header(SESSION_HEADER, session)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest.Builder requestBuilder(String path, FinanceContextV1 context) {
            return HttpRequest.newBuilder(config.baseUri.resolve(path))
                    .timeout(config.timeout)
                    .header("Accept", "application/json")
                    .header("X-Request-Id", context.requestId())
                    .header("X-Trace-Id", context.traceId())
                    .header("X-Channel", config.channel)
                    .header("X-App-Version", config.appVersion);
        }

        private static Response parse(HttpResponse<String> response, FinanceContextV1 context) throws Exception {
            String body = response.body() == null ? "" : response.body();
            if (body.isBlank()) {
                throw new ProviderFailure(
                        "TEMPORARY_FAILURE", "EMPTY_BODY", true,
                        "upstream returned an empty body", List.of(), List.of("RETRY"),
                        context.requestId(), context.traceId(), "non-ai-banking", Instant.now());
            }
            JsonNode root = JSON.readTree(body);
            String requestId = text(root, "requestId", context.requestId());
            String traceId = text(root, "traceId", context.traceId());
            String sourceSystem = text(root, "sourceSystem", "non-ai-banking");
            Instant asOf = instant(root, "asOf");
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                throw new ProviderFailure(
                        text(error, "failureClass", defaultFailureClass(response.statusCode())),
                        text(error, "sourceCode", "UPSTREAM_" + response.statusCode()),
                        error.path("retryable").asBoolean(response.statusCode() >= 500),
                        firstMessage(error),
                        stringList(error.get("fieldErrors")),
                        stringList(error.get("nextActions")),
                        requestId,
                        traceId,
                        sourceSystem,
                        asOf);
            }
            String status = text(root, "status", "");
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || "FAILED".equalsIgnoreCase(status)) {
                throw new ProviderFailure(
                        defaultFailureClass(response.statusCode()),
                        "HTTP_" + response.statusCode(),
                        response.statusCode() >= 500,
                        "upstream http status=" + response.statusCode(),
                        List.of(),
                        List.of("RETRY"),
                        requestId,
                        traceId,
                        sourceSystem,
                        asOf);
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                throw new ProviderFailure(
                        "TEMPORARY_FAILURE", "INVALID_RESPONSE", false,
                        "upstream response data must be an object", List.of(), List.of("CHECK_PROVIDER"),
                        requestId, traceId, sourceSystem, asOf);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = JSON.convertValue(data, LinkedHashMap.class);
            return new Response(requestId, traceId, values, sourceSystem, asOf);
        }

        private static ProviderFailure transportFailure(
                FinanceContextV1 context,
                String sourceCode,
                String message) {
            return new ProviderFailure(
                    "TEMPORARY_FAILURE", sourceCode, true, message,
                    List.of(), List.of("RETRY"), context.requestId(), context.traceId(),
                    "non-ai-banking", Instant.now());
        }

        private static String defaultFailureClass(int statusCode) {
            return switch (statusCode) {
                case 400 -> "INVALID_INPUT";
                case 401 -> "NOT_AUTHENTICATED";
                case 403 -> "NOT_AUTHORIZED";
                case 409 -> "CONFLICT";
                case 422 -> "NOT_ELIGIBLE";
                default -> statusCode >= 500 ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
            };
        }

        private static String firstMessage(JsonNode error) {
            List<String> fieldErrors = stringList(error.get("fieldErrors"));
            return fieldErrors.isEmpty()
                    ? text(error, "sourceCode", "upstream request failed")
                    : String.join("; ", fieldErrors);
        }

        private static String safeMessage(Exception exception) {
            return exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        }

        private static String text(JsonNode node, String field, String fallback) {
            JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() || value.asText().isBlank()
                    ? fallback
                    : value.asText();
        }

        private static Instant instant(JsonNode node, String field) {
            String value = text(node, field, "");
            if (value.isBlank()) {
                return Instant.now();
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                return Instant.now();
            }
        }

        private static List<String> stringList(JsonNode node) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
            return List.copyOf(values);
        }

        private static String encodeQuery(Map<String, String> query) {
            if (query == null || query.isEmpty()) {
                return "";
            }
            StringBuilder value = new StringBuilder("?");
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (value.length() > 1) {
                    value.append('&');
                }
                value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            return value.toString();
        }
    }

    private record Response(
            String requestId,
            String traceId,
            Map<String, Object> data,
            String sourceSystem,
            Instant asOf) {
    }

    private static final class ProviderFailure extends RuntimeException {
        private final String failureClass;
        private final String sourceCode;
        private final boolean retryable;
        private final List<String> fieldErrors;
        private final List<String> nextActions;
        private final String requestId;
        private final String traceId;
        private final String sourceSystem;
        private final Instant asOf;

        private ProviderFailure(
                String failureClass,
                String sourceCode,
                boolean retryable,
                String message,
                List<String> fieldErrors,
                List<String> nextActions,
                String requestId,
                String traceId,
                String sourceSystem,
                Instant asOf) {
            super(message);
            this.failureClass = failureClass;
            this.sourceCode = sourceCode;
            this.retryable = retryable;
            this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            this.nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
            this.requestId = requestId;
            this.traceId = traceId;
            this.sourceSystem = sourceSystem;
            this.asOf = asOf;
        }

        private String failureClass() {
            return failureClass;
        }

        private String sourceCode() {
            return sourceCode;
        }

        private boolean retryable() {
            return retryable;
        }

        private List<String> fieldErrors() {
            return fieldErrors;
        }

        private List<String> nextActions() {
            return nextActions;
        }

        private String requestId() {
            return requestId;
        }

        private String traceId() {
            return traceId;
        }

        private String sourceSystem() {
            return sourceSystem;
        }

        private Instant asOf() {
            return asOf;
        }
    }
}
