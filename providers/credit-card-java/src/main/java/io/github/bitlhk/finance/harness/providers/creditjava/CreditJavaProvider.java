package io.github.bitlhk.finance.harness.providers.creditjava;

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

/** Checked QUERY transport; the Java runtime owns Agent orchestration and conversation state. */
public final class CreditJavaProvider implements DescribedFinanceProvider, InvocationAccess {
    private final ManagedProviderFactoryV01.Connection connection;
    private final Supplier<BoundFinanceProvider.Grant> grants;
    private final Function<CapabilityRequestV1,CreditJavaFactory.Binding> bindings;
    private final Predicate<Map<String,String>> audit;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    CreditJavaProvider(ManagedProviderFactoryV01.Connection connection,
            Supplier<BoundFinanceProvider.Grant> grants,
            Function<CapabilityRequestV1,CreditJavaFactory.Binding> bindings,
            Predicate<Map<String,String>> audit) {
        this.connection = connection; this.grants = grants; this.bindings = bindings; this.audit = audit;
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connection.timeout()).build();
    }
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, CreditJavaFactory.ID, "0.1.0",
            List.of(new ProviderDescriptorV1.CapabilitySupport(CreditJavaFactory.LIMIT, CapabilityRequestV1.Operation.QUERY),
                new ProviderDescriptorV1.CapabilitySupport(CreditJavaFactory.BENEFITS, CapabilityRequestV1.Operation.QUERY)));
    }
    public void checkAccess(CapabilityRequestV1 request) {
        if (request.operation() != CapabilityRequestV1.Operation.QUERY
                || !descriptor().supports(request.capabilityId(), request.operation())
                || request.context().principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !"CREDIT_CARD_SERVICE".equals(request.context().purpose())
                || !request.context().scopes().contains(CreditJavaFactory.SCOPE))
            throw new SecurityException("CREDIT_CONTEXT_REJECTED");
        BoundFinanceProvider.checkContext(grants.get(), request.context());
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        checkAccess(request);
        Set<String> allowed = request.capabilityId().equals(CreditJavaFactory.BENEFITS)
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
        Runnable authority = () -> {
            checkAccess(request);
            if(!Instant.now().isBefore(session.expiresAt())||!Instant.now().isBefore(grant.expiresAt()))
                throw new SecurityException("CREDIT_AUTHORITY_EXPIRED");
            if (!grant.equals(grants.get()) || !session.equals(connection.currentSession().get())
                    || !session.subjectRef().equals(request.context().subjectRef())
                    || !session.principalId().equals(request.context().principalId())
                    || !session.hostSessionRef().equals(request.context().sessionRef()))
                throw new SecurityException("CREDIT_AUTHORITY_OR_DEADLINE_CHANGED");
        };
        Runnable guard=()->{authority.run();if(!Instant.now().isBefore(deadline))throw new SecurityException("CREDIT_DEADLINE_EXCEEDED");};
        guard.run();
        record(request, "STARTED");
        var body = new LinkedHashMap<String,Object>();
        body.put("schema_version", "credit-java-request.v1");
        boolean benefits=request.capabilityId().equals(CreditJavaFactory.BENEFITS);
        body.put("scene", benefits?"benefits":"limit-risk"); body.put("binding", source);
        if(benefits){
            Object k=request.parameters().getOrDefault("topK",3), names=request.parameters().getOrDefault("engines",List.of("llm"));
            if(!(k instanceof Integer count)||count<1||count>5||!(names instanceof List<?> engines)||engines.isEmpty()
                    ||engines.size()>2||new HashSet<>(engines).size()!=engines.size()||!Set.of("llm","onerec").containsAll(engines))
                throw new IllegalArgumentException("CREDIT_RECOMMENDATION_OPTIONS_INVALID");
            body.put("recommendation",Map.of("top_k",count,"engines",engines));
        }
        body.put("session_ref", request.context().sessionRef()); body.put("question", goal);
        body.put("operation_key", request.idempotencyKey());
        try {
            var result = post("/v1/query", body, session.credential(), deadline, guard, authority);
            while (Set.of("queued", "running").contains(result.get("status"))) {
                guard.run();
                Thread.sleep(Math.min(100, Math.max(1, Duration.between(Instant.now(), deadline).toMillis())));
                result = post("/v1/query", body, session.credential(), deadline, guard, authority);
            }
            authority.run();
            boolean deadlineResult="timeout".equals(result.get("status"));
            if(!deadlineResult)guard.run();
            if (!Objects.equals(result.get("customer_id"), request.context().subjectRef()))
                throw new SecurityException("CREDIT_RESULT_SUBJECT_MISMATCH");
            if (!Set.of("complete", "needs-input", "failed", "insufficient", "timeout", "cancelled").contains(result.get("status")))
                throw new IllegalStateException("JAVA_RESULT_NOT_TERMINAL");
            String status = String.valueOf(result.get("status"));
            boolean complete = status.equals("complete"), clarify = status.equals("needs-input");
            var envelopeStatus = clarify ? CapabilityResultV1.Status.NEED_USER
                    : complete ? CapabilityResultV1.Status.COMPLETED : CapabilityResultV1.Status.FAILED;
            record(request, envelopeStatus.name());
            authority.run();if(!deadlineResult)guard.run();
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                    request.context().requestId(), request.context().traceId(), envelopeStatus,
                    "Read-only credit-card reference query: " + status, result,
                    List.of(), List.of(), CreditJavaFactory.ID, Instant.parse(String.valueOf(source.get("observed_at"))),
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
            Instant deadline, Runnable guard, Runnable authority) {
        guard.run();
        long remaining = Math.min(180000, Duration.between(Instant.now(), deadline).toMillis());
        if (remaining < 1) throw new IllegalStateException("CREDIT_DEADLINE_EXCEEDED");
        body.put("remaining_ms", remaining);
        body.put("deadline_at", deadline.toString());
        try {
            var request = HttpRequest.newBuilder(connection.endpoint().resolve(path))
                    .timeout(Duration.ofMillis(remaining+1000)).header("Authorization", "Bearer " + credential)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var pending = http.sendAsync(request, ignored -> new LimitedBody());
            HttpResponse<byte[]> response;
            try {
                response = pending.get(remaining+1000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException exc) {
                pending.cancel(true);
                throw new IllegalStateException("CREDIT_RUNTIME_UNAVAILABLE");
            } catch (InterruptedException exc) {
                pending.cancel(true);
                throw exc;
            }
            connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt("POST", path,
                    response.statusCode(), Instant.now()));
            authority.run();
            if (response.statusCode() != 200)
                throw new IllegalStateException("CREDIT_RUNTIME_RESPONSE_REJECTED");
            var parsed=json.readValue(response.body(),new TypeReference<Map<String,Object>>() {});
            if(!Instant.now().isBefore(deadline)){
                if(!"timeout".equals(parsed.get("status")))throw new IllegalStateException("CREDIT_LATE_RESULT_REJECTED");
                Object detail=parsed.get("result");
                if(!(detail instanceof Map<?,?> map)||!"DEADLINE_EXCEEDED".equals(map.get("code"))
                        ||map.keySet().stream().anyMatch(k->!Set.of("code","execution").contains(k)))
                    throw new IllegalStateException("CREDIT_TIMEOUT_RESULT_REJECTED");
                // Only the terminal code crosses the drain boundary, never arbitrary Runtime payloads.
                parsed=Map.of("status","timeout","customer_id",String.valueOf(parsed.get("customer_id")),
                    "result",Map.of("code","DEADLINE_EXCEEDED"));
            }
            return parsed;
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
                "providerId", CreditJavaFactory.ID)))
            throw new IllegalStateException("CREDIT_AUDIT_UNAVAILABLE");
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        throw new SecurityException("CREDIT_READ_ONLY");
    }
}
