package io.github.bitlhk.finance.harness.providers.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.time.Instant;
import java.util.*;

/** QUERY-only adapter. Knowledge uncertainty belongs in data.decision, not TASK status. */
public final class KnowledgeProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String ID = "knowledge-service", SEARCH = "knowledge.evidence.search";
    private final KnowledgeBinding binding;
    private final ManagedProviderFactoryV01.Connection connection;
    private final HttpClient http;
    KnowledgeProvider(KnowledgeBinding binding, ManagedProviderFactoryV01.Connection connection) {
        this.binding = binding; this.connection = connection;
        http = HttpClient.newBuilder().connectTimeout(connection.timeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, ID, "0.1.0-probe.2",
            List.of(new ProviderDescriptorV1.CapabilitySupport(SEARCH, CapabilityRequestV1.Operation.QUERY)));
    }
    public void checkAccess(CapabilityRequestV1 r) { session(r); }
    private ManagedProviderFactoryV01.SourceSession session(CapabilityRequestV1 r) {
        var s = connection.currentSession().get(); var c = r.context();
        if (s == null || !s.expiresAt().isAfter(Instant.now()) || !s.principalId().equals(c.principalId())
                || !s.subjectRef().equals(c.subjectRef()) || !s.hostSessionRef().equals(c.sessionRef())
                || !c.scopes().contains("knowledge:read"))
            throw new SecurityException("KNOWLEDGE_CONTEXT_REJECTED");
        return s;
    }
    public CapabilityResultV1 query(CapabilityRequestV1 r) {
        if (!descriptor().supports(r.capabilityId(), r.operation()) || r.idempotencyKey() != null
                || r.taskRef() != null || r.confirmation() != null || !r.continuation().isEmpty())
            return failed(r, "READ_ONLY_REQUIRED");
        var args = r.parameters();
        if (!args.keySet().equals(Set.of("question")) || !(args.get("question") instanceof String q)
                || q.isBlank() || q.length() > 1000) return failed(r, "INVALID_INPUT");
        var s = session(r); var c = r.context();
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", "knowledge-query.v0.1"); body.put("capabilityId", r.capabilityId());
        body.put("productId", binding.productId()); body.put("manifest", binding.manifest());
        body.put("businessDate", binding.businessDate().toString()); body.put("requestId", c.requestId());
        body.put("traceId", c.traceId()); body.put("principalId", s.principalId()); body.put("subjectRef", s.subjectRef());
        body.put("sessionRef", s.hostSessionRef()); body.put("purpose", c.purpose()); body.put("arguments", args);
        try {
            var request = HttpRequest.newBuilder(connection.endpoint()).timeout(connection.timeout())
                .header("Authorization", "Bearer " + s.credential()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(KnowledgeBinding.JSON.writeValueAsBytes(body))).build();
            var pending = http.sendAsync(request, info -> new BoundedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(connection.timeout().toMillis(), TimeUnit.MILLISECONDS); }
            finally { if (!pending.isDone()) pending.cancel(true); }
            byte[] bytes = response.body();
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST", "/query", response.statusCode(), Instant.now()));
            if (response.statusCode() != 200) return failed(r, upstreamFailure(response.statusCode(), bytes));
            if (bytes.length > 262144) return failed(r, "UPSTREAM_REJECTED");
            var result = KnowledgeBinding.JSON.readTree(bytes);
            if (!result.path("code").isIntegralNumber() || result.path("code").intValue() != 0)
                return failed(r, "KNOWLEDGE_BUSINESS_REJECTED");
            var receipt = result.path("receipt");
            if (!"knowledge-receipt.v0.1".equals(receipt.path("schemaVersion").asText())
                    || !binding.manifest().equals(receipt.path("manifest").asText())
                    || !binding.productId().equals(receipt.path("productId").asText())
                    || !binding.businessDate().toString().equals(receipt.path("businessDate").asText())
                    || !c.requestId().equals(receipt.path("requestId").asText())
                    || !c.traceId().equals(receipt.path("traceId").asText())
                    || !receipt.path("receiptId").asText().matches("kr-[a-zA-Z0-9-]{1,80}")
                    || !Set.of("UNKNOWN","EVIDENCE_FOUND").contains(result.path("decision").asText())
                    || !result.path("evidence").isArray()) return failed(r, "KNOWLEDGE_RECEIPT_MISMATCH");
            session(r); // A response arriving after revocation must not be disclosed.
            Map<String,Object> data = new LinkedHashMap<>();
            data.put("decision", result.path("decision").asText());
            data.put("evidence", KnowledgeBinding.JSON.convertValue(result.path("evidence"), List.class));
            data.put("knowledgeReceipt", KnowledgeBinding.JSON.convertValue(receipt, Map.class));
            data.put("bindingSha256", binding.sha256()); data.put("testOnly", true);
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, c.requestId(), c.traceId(),
                CapabilityResultV1.Status.COMPLETED, "合成知识查询完成，不代表真实制度判断或审批。", data,
                List.of(), List.of(), ID, Instant.now(), null, null);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return failed(r, "KNOWLEDGE_INTERRUPTED");
        } catch (Exception error) { return failed(r, "KNOWLEDGE_UNAVAILABLE"); }
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 r) { return failed(r, "READ_ONLY_REQUIRED"); }
    // Preserve bounded protocol diagnostics, never arbitrary upstream text or credentials.
    static String upstreamFailure(int status, byte[] bytes) {
        if (status != 403 || bytes.length > 4096) return "UPSTREAM_REJECTED";
        try {
            var body = KnowledgeBinding.JSON.readTree(bytes);
            if (!body.isObject() || body.size() != 2 || !body.path("code").isIntegralNumber()
                    || body.path("code").intValue() != status || !body.path("error").isTextual())
                return "UPSTREAM_REJECTED";
            String code = body.path("error").asText();
            return Set.of("KNOWLEDGE_AUTH_REQUIRED", "KNOWLEDGE_BINDING_MISMATCH",
                "KNOWLEDGE_CONTEXT_MISMATCH", "KNOWLEDGE_SESSION_CHANGED", "FORBIDDEN",
                "MANIFEST_MISMATCH", "KNOWLEDGE_NOT_APPLICABLE", "SOURCE_NOT_EFFECTIVE",
                "SOURCE_SUPERSEDED_OR_REVOKED", "BINDING_CHANGED", "RETRIEVAL_UNAVAILABLE",
                "RETRIEVAL_RESPONSE_INVALID", "RETRIEVAL_INDEX_MISMATCH", "RAGFLOW_AUTH_FAILED",
                "RAGFLOW_BUSINESS_ERROR", "RAGFLOW_UNAVAILABLE", "RAGFLOW_RESPONSE_LIMIT",
                "RAGFLOW_DATASET_MISMATCH", "RAGFLOW_RESPONSE_INVALID").contains(code)
                    ? code : "UPSTREAM_REJECTED";
        } catch (Exception ignored) { return "UPSTREAM_REJECTED"; }
    }
    /** Bound response memory and cancel the upstream stream before accepting oversized data. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (bytes.size() + chunk.remaining() > 262144) {
                    subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("RESPONSE_TOO_LARGE")); return;
                }
                byte[] block = new byte[chunk.remaining()]; chunk.get(block); bytes.writeBytes(block);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { body.completeExceptionally(failure); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
    private CapabilityResultV1 failed(CapabilityRequestV1 r, String code) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, r.context().requestId(), r.context().traceId(),
            CapabilityResultV1.Status.FAILED, "知识查询未完成。", Map.of(), List.of(), List.of(), ID, Instant.now(), null,
            new CapabilityResultV1.Failure("REJECTED", code, false, "知识查询未完成。", List.of()));
    }
}
