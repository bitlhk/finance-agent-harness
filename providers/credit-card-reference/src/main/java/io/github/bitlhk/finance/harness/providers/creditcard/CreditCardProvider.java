package io.github.bitlhk.finance.harness.providers.creditcard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;

/** Checked QUERY transport; the Python runtime owns algorithms and recommendation state. */
public final class CreditCardProvider implements DescribedFinanceProvider, InvocationAccess {
    private final ManagedProviderFactoryV01.Connection connection;
    private final Supplier<BoundFinanceProvider.Grant> grants;
    private final Function<CapabilityRequestV1,CreditCardFactory.Binding> bindings;
    private final Predicate<Map<String,String>> audit;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    CreditCardProvider(ManagedProviderFactoryV01.Connection connection,
            Supplier<BoundFinanceProvider.Grant> grants,
            Function<CapabilityRequestV1,CreditCardFactory.Binding> bindings,
            Predicate<Map<String,String>> audit) {
        this.connection = connection; this.grants = grants; this.bindings = bindings; this.audit = audit;
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connection.timeout()).build();
    }
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, CreditCardFactory.ID, "0.1.0",
            List.of(new ProviderDescriptorV1.CapabilitySupport(CreditCardFactory.LIMIT, CapabilityRequestV1.Operation.QUERY),
                    new ProviderDescriptorV1.CapabilitySupport(CreditCardFactory.BENEFITS, CapabilityRequestV1.Operation.QUERY)));
    }
    public void checkAccess(CapabilityRequestV1 request) {
        if (request.operation() != CapabilityRequestV1.Operation.QUERY
                || !descriptor().supports(request.capabilityId(), request.operation())
                || request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !"CREDIT_CARD_SERVICE".equals(request.context().purpose())
                || !request.context().scopes().contains(CreditCardFactory.SCOPE))
            throw new SecurityException("CREDIT_CONTEXT_REJECTED");
        BoundFinanceProvider.checkContext(grants.get(), request.context());
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        checkAccess(request);
        Set<String> allowed = request.capabilityId().equals(CreditCardFactory.BENEFITS)
                ? Set.of("caseRef", "goal", "topK", "engines") : Set.of("caseRef", "goal");
        if (!allowed.containsAll(request.parameters().keySet())
                || !(request.parameters().get("caseRef") instanceof String caseRef) || caseRef.isBlank()
                || !(request.parameters().get("goal") instanceof String goal) || goal.isBlank() || goal.length() > 1000
                || request.idempotencyKey() == null)
            throw new IllegalArgumentException("CREDIT_QUERY_INVALID");
        var grant = grants.get();
        var session = connection.currentSession().get();
        var binding = bindings.apply(request);
        var source = binding.source();
        if (!Objects.equals(source.get("customer_id"), request.context().subjectRef())
                || !Objects.equals(source.get("principal_id"), request.context().principalId())
                || !Objects.equals(source.get("case_id"), caseRef))
            throw new SecurityException("CREDIT_SNAPSHOT_BINDING_REJECTED");
        Instant deadline = Collections.min(List.of(binding.deadline(), session.expiresAt(),
                grant.expiresAt(), Instant.now().plus(connection.timeout())));
        Runnable guard = () -> {
            checkAccess(request);
            if (!grant.equals(grants.get()) || !session.equals(connection.currentSession().get())
                    || !session.subjectRef().equals(request.context().subjectRef())
                    || !session.principalId().equals(request.context().principalId())
                    || !session.hostSessionRef().equals(request.context().sessionRef())
                    || !Instant.now().isBefore(deadline))
                throw new SecurityException("CREDIT_AUTHORITY_OR_DEADLINE_CHANGED");
        };
        guard.run();
        record(request, "STARTED");
        var body = new LinkedHashMap<String,Object>();
        body.put("capability_id", request.capabilityId()); body.put("binding", source);
        body.put("session_ref", request.context().sessionRef()); body.put("goal", goal);
        body.put("operation_key", request.idempotencyKey());
        if (request.parameters().containsKey("topK")) {
            Object value = request.parameters().get("topK");
            if (!(value instanceof Integer || value instanceof Long)
                    || ((Number)value).longValue() < 1 || ((Number)value).longValue() > 5)
                throw new IllegalArgumentException("CREDIT_TOP_K_INVALID");
            body.put("top_k", value);
        }
        if (request.parameters().containsKey("engines")) {
            Object value = request.parameters().get("engines");
            if (!(value instanceof List<?> values) || values.isEmpty() || values.size() > 2
                    || new HashSet<>(values).size() != values.size()
                    || !Set.of("llm", "onerec").containsAll(values))
                throw new IllegalArgumentException("CREDIT_ENGINES_INVALID");
            body.put("engines",value);
        }
        if (request.continuation().containsKey("parent_run_id"))
            body.put("parent_run_id", request.continuation().get("parent_run_id"));
        try {
            var result = post("/v1/query", body, session.credential(), deadline, guard);
            while (Set.of("queued", "running").contains(result.get("status"))) {
                if (!(result.get("run_id") instanceof String runId))
                    throw new IllegalStateException("CREDIT_RESULT_INVALID");
                guard.run();
                Thread.sleep(Math.min(100, Math.max(1, Duration.between(Instant.now(), deadline).toMillis())));
                body.put("run_id", runId);
                result = post("/v1/result", body, session.credential(), deadline, guard);
            }
            guard.run();
            if (!Objects.equals(result.get("customer_id"), request.context().subjectRef()))
                throw new SecurityException("CREDIT_RESULT_SUBJECT_MISMATCH");
            BenefitsQuery.checkTerminal(result);
            String status = String.valueOf(result.get("status"));
            boolean complete = status.equals("complete"), clarify = BenefitsQuery.needsUser(result);
            var envelopeStatus = clarify ? CapabilityResultV1.Status.NEED_USER
                    : complete ? CapabilityResultV1.Status.COMPLETED : CapabilityResultV1.Status.FAILED;
            record(request, envelopeStatus.name());
            guard.run();
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                    request.context().requestId(), request.context().traceId(), envelopeStatus,
                    "Read-only credit-card reference query: " + status, result,
                    List.of(), List.of(), CreditCardFactory.ID, Instant.parse(String.valueOf(source.get("observed_at"))),
                    null, envelopeStatus == CapabilityResultV1.Status.FAILED
                        ? new CapabilityResultV1.Failure("RUNTIME", "CREDIT_QUERY_" + status.toUpperCase(Locale.ROOT),
                            false, "Query did not complete; inspect structured result", List.of()) : null);
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt(); record(request, "INTERRUPTED");
            throw new IllegalStateException("CREDIT_QUERY_INTERRUPTED");
        } catch (RuntimeException exc) {
            record(request, "FAILED"); throw exc;
        }
    }
    private Map<String,Object> post(String path, Map<String,Object> body, String credential,
            Instant deadline, Runnable guard) {
        guard.run();
        long remaining = Math.min(180000, Duration.between(Instant.now(), deadline).toMillis());
        if (remaining < 1) throw new IllegalStateException("CREDIT_DEADLINE_EXCEEDED");
        body.put("remaining_ms", remaining);
        body.put("deadline_at", deadline.toString());
        try {
            var request = HttpRequest.newBuilder(connection.endpoint().resolve(path))
                    .timeout(Duration.ofMillis(remaining)).header("Authorization", "Bearer " + credential)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var pending = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException exc) {
                pending.cancel(true);
                throw new IllegalStateException("CREDIT_RUNTIME_UNAVAILABLE");
            } catch (InterruptedException exc) {
                pending.cancel(true);
                throw exc;
            }
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST", path,
                    response.statusCode(), Instant.now()));
            guard.run();
            if (response.statusCode() != 200)
                throw new IllegalStateException("CREDIT_RUNTIME_RESPONSE_REJECTED");
            return json.readValue(response.body(), new TypeReference<Map<String,Object>>() {});
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("CREDIT_QUERY_INTERRUPTED");
        } catch (java.io.IOException exc) {
            throw new IllegalStateException("CREDIT_RUNTIME_UNAVAILABLE");
        }
    }
    /** Cap bytes while receiving; the caller's absolute timeout also covers body delivery. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> values) {
            for (ByteBuffer value : values) {
                if (value.remaining() > 2 * 1024 * 1024 - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("CREDIT_BODY_TOO_LARGE"));
                    return;
                }
                byte[] part = new byte[value.remaining()]; value.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    private void record(CapabilityRequestV1 request, String phase) {
        if (!audit.test(Map.of("requestId", request.context().requestId(), "phase", phase,
                "providerId", CreditCardFactory.ID)))
            throw new IllegalStateException("CREDIT_AUDIT_UNAVAILABLE");
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        throw new SecurityException("CREDIT_READ_ONLY");
    }
}
