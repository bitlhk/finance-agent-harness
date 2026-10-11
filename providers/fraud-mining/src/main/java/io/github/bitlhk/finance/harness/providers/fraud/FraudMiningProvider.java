package io.github.bitlhk.finance.harness.providers.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;

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
public final class FraudMiningProvider implements DescribedFinanceProvider {
    public static final String PROVIDER_ID = "fraud-mining-reference";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    public static final String TRANSFER_RISK_ASSESS = "fraud.transfer-risk.assess";
    public static final String RISK_EVIDENCE_QUERY = "fraud.risk-evidence.query";
    public static final String FRAUD_SOURCE_CINO = "FRAUD_SOURCE_CINO";

    private final Config config;
    private final HttpClient http;
    private final ObjectMapper json;

    public FraudMiningProvider(Config config) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        this.json = HarnessJson.mapper();
    }

    @Override
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION,
                PROVIDER_ID,
                PROVIDER_VERSION,
                List.of(
                        new ProviderDescriptorV1.CapabilitySupport(
                                TRANSFER_RISK_ASSESS, CapabilityRequestV1.Operation.QUERY),
                        new ProviderDescriptorV1.CapabilitySupport(
                                RISK_EVIDENCE_QUERY, CapabilityRequestV1.Operation.QUERY)));
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "transfer risk assessment requires operation QUERY");
        }
        if (RISK_EVIDENCE_QUERY.equals(request.capabilityId())) {
            return queryEvidence(request);
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

    private CapabilityResultV1 queryEvidence(CapabilityRequestV1 request) {
        if (!request.context().scopes().contains("fraud-risk:read")) {
            return failure(request, "AUTHORIZATION", "FRAUD_RISK_READ_REQUIRED", false,
                    "fraud-risk:read scope is required");
        }
        String subjectType = text(request.parameters().get("subjectType"));
        String subjectRef = text(request.parameters().get("subjectRef"));
        if (!FRAUD_SOURCE_CINO.equals(subjectType)) {
            return failure(request, "INVALID_INPUT", "FRAUD_SUBJECT_TYPE_UNSUPPORTED", false,
                    "subjectType must be FRAUD_SOURCE_CINO");
        }
        if (subjectRef == null) {
            return failure(request, "INVALID_INPUT", "FRAUD_SUBJECT_REQUIRED", false,
                    "subjectRef is required");
        }
        try {
            return evidence(request, subjectRef);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(request, "TEMPORARY_FAILURE", "FRAUD_SERVICE_INTERRUPTED", true,
                    "fraud evidence query was interrupted");
        } catch (Exception exception) {
            return failure(request, "TEMPORARY_FAILURE", "FRAUD_SERVICE_UNAVAILABLE", true,
                    "fraud evidence source did not return usable evidence");
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

    private CapabilityResultV1 evidence(CapabilityRequestV1 request, String subjectRef) throws Exception {
        List<Map<String, Object>> signals = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<String> completenessReasons = new ArrayList<>();
        Integer expectedTotal = null;
        int scanned = 0;
        int pagesRead = 0;
        boolean complete = false;
        Instant newestEvidence = Instant.EPOCH;
        long deadline = System.nanoTime() + config.timeout().toNanos();

        for (int page = 1; page <= config.maxPages(); page++) {
            if (scanned >= config.maxRecords() || System.nanoTime() >= deadline) {
                completenessReasons.add(scanned >= config.maxRecords() ? "RECORD_LIMIT_REACHED" : "TOTAL_TIME_LIMIT_REACHED");
                break;
            }
            JsonNode data;
            try {
                data = page(subjectRef, page, deadline);
            } catch (Exception failure) {
                if (pagesRead == 0) throw failure;
                completenessReasons.add("SOURCE_FAILED_AFTER_PARTIAL_EVIDENCE");
                break;
            }
            pagesRead++;
            int total = data.path("total").asInt(-1);
            if (total < 0) {
                completenessReasons.add("SOURCE_TOTAL_MISSING");
                break;
            }
            if (expectedTotal == null) expectedTotal = total;
            else if (expectedTotal != total) {
                completenessReasons.add("SOURCE_TOTAL_CHANGED");
                break;
            }
            JsonNode items = data.path("items");
            if (!items.isArray()) throw new IllegalStateException("fraud evidence items are missing");
            if (items.isEmpty() && scanned < total) {
                completenessReasons.add("SOURCE_PAGE_ENDED_EARLY");
                break;
            }
            for (JsonNode item : items) {
                scanned++;
                if (scanned > config.maxRecords()) {
                    completenessReasons.add("RECORD_LIMIT_REACHED");
                    break;
                }
                if (!subjectRef.equals(item.path("cino").asText())) continue;
                String sampleId = item.path("id").asText(null);
                if (sampleId == null || sampleId.isBlank()) completenessReasons.add("SOURCE_RECORD_ID_MISSING");
                else if (!seen.add(sampleId)) continue;
                Instant observedAt = instant(item.path("created_at").asText(null));
                if (observedAt == null) completenessReasons.add("SOURCE_EVIDENCE_TIME_MISSING");
                if (observedAt != null && observedAt.isAfter(newestEvidence)) newestEvidence = observedAt;
                Map<String, Object> signal = new LinkedHashMap<>();
                if (sampleId != null && !sampleId.isBlank()) signal.put("sampleId", sampleId);
                signal.put("recordLabel", item.path("label").isIntegralNumber() ? item.path("label").asInt() : "UNKNOWN");
                signal.put("matchStatus", item.path("match_status").asText("unknown"));
                signal.put("matchScore", item.path("match_confidence").isNumber() ? item.path("match_confidence").numberValue() : "UNKNOWN");
                signal.put("modelLabel", item.path("prediction_result").asText("unknown"));
                signal.put("modelScore", item.path("prediction_score").isNumber() ? item.path("prediction_score").numberValue() : "UNKNOWN");
                signal.put("scoreMeaning", "SOURCE_SIGNAL_NOT_CALIBRATED_FRAUD_PROBABILITY");
                signal.put("observedAt", observedAt == null ? "UNKNOWN" : observedAt.toString());
                signals.add(Map.copyOf(signal));
            }
            if (!completenessReasons.isEmpty()) break;
            if (scanned >= total) { complete = true; break; }
        }
        if (!complete && completenessReasons.isEmpty()) completenessReasons.add("PAGE_LIMIT_REACHED");

        boolean knownMatch = signals.stream().anyMatch(signal ->
                Integer.valueOf(1).equals(signal.get("recordLabel"))
                        && "matched".equalsIgnoreCase(String.valueOf(signal.get("matchStatus"))));
        String verificationStatus = knownMatch ? "KNOWN_RISK_MATCH"
                : complete ? "NO_KNOWN_MATCH_COMPLETE" : "VERIFICATION_INCOMPLETE";
        Map<String, Object> queryScope = new LinkedHashMap<>();
        queryScope.put("pagesRead", pagesRead);
        queryScope.put("recordsScanned", scanned);
        queryScope.put("reportedTotal", expectedTotal == null ? -1 : expectedTotal);
        queryScope.put("pageSize", config.pageSize());
        queryScope.put("maxPages", config.maxPages());
        queryScope.put("maxRecords", config.maxRecords());
        queryScope.put("complete", complete);
        queryScope.put("completenessReasons", List.copyOf(completenessReasons));
        queryScope.put("snapshotConsistency", "NOT_GUARANTEED_BY_SOURCE");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("subjectType", FRAUD_SOURCE_CINO);
        data.put("subjectRef", subjectRef);
        data.put("verificationStatus", verificationStatus);
        data.put("knownRiskMatch", knownMatch);
        data.put("queryComplete", complete);
        data.put("queryScope", Map.copyOf(queryScope));
        data.put("signals", List.copyOf(signals));
        data.put("sourceVersion", "UNKNOWN");
        data.put("sourceSystemMeaning", "KNOWN_FRAUD_RECORD_SOURCE");
        String content = switch (verificationStatus) {
            case "KNOWN_RISK_MATCH" -> complete
                    ? "命中来源中的已知涉诈记录；仅供人工核验，不代表对对象作出诈骗认定。"
                    : "命中来源中的已知涉诈记录，但查询未完整；已保留命中证据并等待人工核验。";
            case "NO_KNOWN_MATCH_COMPLETE" -> "在已记录的完整查询范围内未命中已知涉诈记录；不代表对象安全或允许交易。";
            default -> "风险来源查询未完整，不能得出未命中结论；请人工核验。";
        };
        Instant asOf = newestEvidence.equals(Instant.EPOCH) ? Instant.now() : newestEvidence;
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED, content, data, List.of(),
                List.of("MANUAL_EVIDENCE_REVIEW"), "fraud-mining", asOf, null, null);
    }

    private JsonNode page(String subjectRef, int page, long deadline) throws Exception {
        String path = "/api/v1/samples?page=" + page + "&page_size=" + config.pageSize() + "&keyword="
                + URLEncoder.encode(subjectRef, StandardCharsets.UTF_8);
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) throw new java.net.http.HttpTimeoutException("total query time exceeded");
        HttpRequest upstreamRequest = HttpRequest.newBuilder(config.baseUri().resolve(path))
                .timeout(Duration.ofNanos(remainingNanos)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(upstreamRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("fraud service returned HTTP " + response.statusCode());
        JsonNode root = json.readTree(response.body());
        if (root.path("code").asInt(500) != 200 || !root.path("data").isObject())
            throw new IllegalStateException("fraud service response does not match its contract");
        return root.path("data");
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

    public record Config(URI baseUri, Duration timeout, int pageSize, int maxPages, int maxRecords) {
        public Config(URI baseUri, Duration timeout) { this(baseUri, timeout, 100, 10, 1000); }
        public Config {
            Objects.requireNonNull(baseUri, "baseUri");
            Objects.requireNonNull(timeout, "timeout");
            if (!baseUri.isAbsolute()) {
                throw new IllegalArgumentException("baseUri must be absolute");
            }
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (pageSize < 1 || pageSize > 500 || maxPages < 1 || maxPages > 100 || maxRecords < 1)
                throw new IllegalArgumentException("bounded pagination limits are invalid");
            String normalized = baseUri.toString().endsWith("/")
                    ? baseUri.toString()
                    : baseUri + "/";
            baseUri = URI.create(normalized);
        }
    }
}
