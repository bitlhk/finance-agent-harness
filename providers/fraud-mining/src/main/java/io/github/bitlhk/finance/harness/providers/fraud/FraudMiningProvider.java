package io.github.bitlhk.finance.harness.providers.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
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

/** Maps the fraud-mining service into a runtime-neutral transfer-risk capability. */
public final class FraudMiningProvider implements FinanceProvider {
    public static final String PROVIDER_ID = "fraud-mining-reference";
    public static final String TRANSFER_RISK_ASSESS = "fraud.transfer-risk.assess";

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper json;

    public FraudMiningProvider(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        this.json = HarnessJson.mapper();
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "transfer risk assessment requires operation QUERY");
        }
        if (!TRANSFER_RISK_ASSESS.equals(request.capabilityId())) {
            return failure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                    "unsupported query capability: " + request.capabilityId());
        }

        String subjectRef = text(request.parameters().get("accountRef"));
        if (subjectRef == null) {
            subjectRef = text(request.parameters().get("customerRef"));
        }
        if (subjectRef == null) {
            return failure(request, "INVALID_INPUT", "RISK_SUBJECT_REQUIRED", false,
                    "accountRef or customerRef is required");
        }

        try {
            return assess(request, subjectRef);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(request, "TEMPORARY_FAILURE", "FRAUD_SERVICE_INTERRUPTED", true,
                    "fraud service request was interrupted");
        } catch (Exception exception) {
            return failure(request, "TEMPORARY_FAILURE", "FRAUD_SERVICE_UNAVAILABLE", true,
                    "fraud service did not return a usable assessment");
        }
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        return failure(request, "INVALID_INPUT", "READ_ONLY_PROVIDER", false,
                "fraud-mining provider does not execute tasks");
    }

    private CapabilityResultV1 assess(CapabilityRequestV1 request, String subjectRef) throws Exception {
        String path = "/api/v1/samples?page=1&page_size=20&keyword="
                + URLEncoder.encode(subjectRef, StandardCharsets.UTF_8);
        HttpRequest upstreamRequest = HttpRequest.newBuilder(config.baseUri().resolve(path))
                .timeout(config.timeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = http.send(upstreamRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("fraud service returned HTTP " + response.statusCode());
        }

        JsonNode root = json.readTree(response.body());
        if (root.path("code").asInt(500) != 200 || !root.path("data").path("items").isArray()) {
            throw new IllegalStateException("fraud service response does not match its contract");
        }

        List<Map<String, Object>> signals = new ArrayList<>();
        boolean knownRiskMatch = false;
        double maxConfidence = 0;
        double maxModelScore = 0;
        Instant newestEvidence = Instant.EPOCH;
        for (JsonNode item : root.path("data").path("items")) {
            if (!subjectRef.equals(item.path("cino").asText())) {
                continue;
            }
            boolean matched = item.path("label").asInt(0) == 1
                    && "matched".equalsIgnoreCase(item.path("match_status").asText());
            knownRiskMatch |= matched;
            double confidence = item.path("match_confidence").asDouble(0);
            double modelScore = item.path("prediction_score").asDouble(0);
            maxConfidence = Math.max(maxConfidence, confidence);
            maxModelScore = Math.max(maxModelScore, modelScore);
            Instant observedAt = instant(item.path("created_at").asText(null));
            if (observedAt != null && observedAt.isAfter(newestEvidence)) {
                newestEvidence = observedAt;
            }
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("sampleId", item.path("id").asLong());
            signal.put("matchStatus", item.path("match_status").asText("unknown"));
            signal.put("matchConfidence", confidence);
            signal.put("modelLabel", item.path("prediction_result").asText("unknown"));
            signal.put("modelScore", modelScore);
            signal.put("observedAt", item.path("created_at").asText(null));
            signals.add(Map.copyOf(signal));
        }

        String decision = knownRiskMatch ? "BLOCK" : "ALLOW";
        String reasonCode = knownRiskMatch ? "KNOWN_FRAUD_INCIDENT_MATCH" : "NO_KNOWN_FRAUD_MATCH";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectRef", subjectRef);
        data.put("decision", decision);
        data.put("reasonCode", reasonCode);
        data.put("knownRiskMatch", knownRiskMatch);
        data.put("maxMatchConfidence", maxConfidence);
        data.put("maxModelScore", maxModelScore);
        data.put("signals", List.copyOf(signals));
        data.put("decisionSource", "fraud-mining-incident-registry");

        Instant asOf = newestEvidence.equals(Instant.EPOCH) ? Instant.now() : newestEvidence;
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                knownRiskMatch ? "命中已知涉诈风险记录，建议阻断转账。" : "未命中已知涉诈风险记录。",
                data,
                List.of(),
                knownRiskMatch ? List.of("BLOCK_TRANSFER", "REFER_FRAUD_REVIEW") : List.of("CONTINUE_PREFLIGHT"),
                "fraud-mining",
                asOf,
                null,
                null);
    }

    private static CapabilityResultV1 failure(
            CapabilityRequestV1 request,
            String failureClass,
            String sourceCode,
            boolean retryable,
            String message) {
        String requestId = request == null || request.context() == null ? "unknown-request" : request.context().requestId();
        String traceId = request == null || request.context() == null ? "unknown-trace" : request.context().traceId();
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                requestId,
                traceId,
                CapabilityResultV1.Status.FAILED,
                "反诈风险服务暂时无法完成评估。",
                Map.of(),
                List.of(),
                List.of(retryable ? "RETRY_OR_MANUAL_REVIEW" : "CHECK_REQUEST"),
                "fraud-mining",
                Instant.now(),
                null,
                new CapabilityResultV1.Failure(failureClass, sourceCode, retryable, message, List.of()));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.endsWith("Z") ? value : value + "Z");
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    public record Config(URI baseUri, Duration timeout) {
        public Config {
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(timeout, "timeout");
            if (!baseUri.isAbsolute()) {
                throw new IllegalArgumentException("baseUri must be absolute");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            String normalized = baseUri.toString().endsWith("/")
                    ? baseUri.toString()
                    : baseUri + "/";
            baseUri = URI.create(normalized);
        }
    }
}
