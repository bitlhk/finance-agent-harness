package io.github.bitlhk.finance.harness.cashflow;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;
import java.util.function.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.DeserializationFeature;

/** Opt-in summary boundary. The host supplies actual authority and acknowledged request storage. */
public final class CashflowQueryGuard implements DescribedFinanceProvider, InvocationAccess, InputCollectionProvider {
    public static final String CAPABILITY = "finance.transaction-summary.query";
    public record Authorization(BoundFinanceProvider.Grant grant, String policyDigest) {
        public Authorization {
            if (!policyDigest.matches("sha256:[a-f0-9]{64}|UNOBSERVED"))
                throw new IllegalArgumentException("POLICY_DIGEST_INVALID");
        }
    }
    private final DescribedFinanceProvider delegate;
    private final Supplier<Authorization> authority;
    private final Predicate<Map<String,Object>> audit;
    private final String bindingDigest;
    public CashflowQueryGuard(DescribedFinanceProvider delegate, Supplier<Authorization> authority,
                              Predicate<Map<String,Object>> audit, String bindingDigest) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authority = Objects.requireNonNull(authority); this.audit = Objects.requireNonNull(audit);
        if (!bindingDigest.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("GUARD_BINDING_REQUIRED");
        this.bindingDigest = bindingDigest;
    }
    public ProviderDescriptorV1 descriptor() { return delegate.descriptor(); }
    public List<Class<?>> artifactTypes() {
        var types = new ArrayList<>(delegate.artifactTypes()); types.add(getClass()); return List.copyOf(types);
    }
    public List<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel> observationLabels() {
        return delegate.observationLabels();
    }
    public Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result) {
        return delegate instanceof InputCollectionProvider input ? input.collectableInputs(request, result) : Set.of();
    }
    private void record(CapabilityRequestV1 request, String stage, String decision, String reason, String policy) {
        var event = new LinkedHashMap<String,Object>();
        event.put("recordType", "cashflow-authorization.v1"); event.put("requestId", request.context().requestId());
        event.put("traceId", request.context().traceId()); event.put("capabilityId", CAPABILITY);
        event.put("stage", stage); event.put("decision", decision); event.put("reason", reason);
        event.put("subjectDigest", hash(request.context().subjectRef()));
        event.put("sessionDigest", hash(request.context().sessionRef()));
        event.put("principalDigest", hash(request.context().principalId()));
        event.put("purpose", request.context().purpose()); event.put("operation", request.operation().name());
        Object period = request.parameters().get("period");
        event.put("period", period instanceof String text && text.matches("[0-9]{4}-(0[1-9]|1[0-2])") ? period : "INVALID_OR_MISSING");
        event.put("policyDigest", policy); event.put("bindingDigest", bindingDigest);
        if (!audit.test(Collections.unmodifiableMap(event))) throw new SecurityException("AUTHORIZATION_AUDIT_UNAVAILABLE");
    }
    private Authorization check(CapabilityRequestV1 request, String stage) {
        Authorization snapshot = null;
        try {
            snapshot = authority.get();
            if (snapshot == null) throw new SecurityException();
            BoundFinanceProvider.checkContext(snapshot.grant(), request.context());
            if (request.operation() != CapabilityRequestV1.Operation.QUERY
                    || !snapshot.grant().scopes().contains("cashflow:read")
                    || !request.context().scopes().contains("cashflow:read")
                    || !delegate.descriptor().supports(CAPABILITY, request.operation())
                    || !request.parameters().keySet().equals(Set.of("period"))) throw new SecurityException();
            if (!(request.parameters().get("period") instanceof String period)) throw new SecurityException();
            CashflowFactsV1.month(period);
            if (delegate instanceof InvocationAccess access) access.checkAccess(request);
        } catch (RuntimeException denied) {
            record(request, stage, "DENY", "AUTHORIZATION_OR_PARAMETERS_REJECTED", snapshot == null ? "UNOBSERVED" : snapshot.policyDigest());
            throw new SecurityException("CASHFLOW_GUARD_REJECTED");
        }
        record(request, stage, "ALLOW", "CURRENT_GRANT_VALID", snapshot.policyDigest());
        return snapshot;
    }
    public void checkAccess(CapabilityRequestV1 request) {
        if (CAPABILITY.equals(request.capabilityId())) check(request, "ENTRY");
        else if (delegate instanceof InvocationAccess access) access.checkAccess(request);
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (!CAPABILITY.equals(request.capabilityId())) return delegate.query(request);
        check(request, "ENTRY");
        CapabilityResultV1 result;
        try { result = delegate.query(request); }
        catch (RuntimeException failed) {
            record(request, "RETURN", "DENY", "SOURCE_QUERY_FAILED", "UNOBSERVED");
            throw new SecurityException("CASHFLOW_SOURCE_FAILED");
        }
        var returned = check(request, "RETURN");
        try {
            if (!result.requestId().equals(request.context().requestId()) || !result.traceId().equals(request.context().traceId()))
                throw new IllegalArgumentException("RESULT_REQUEST_MISMATCH");
            Map<String,Object> data = Map.of();
            if (result.status() == CapabilityResultV1.Status.COMPLETED) {
                var facts = HarnessJson.mapper().readerFor(CashflowFactsV1.class)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue((com.fasterxml.jackson.databind.JsonNode) HarnessJson.mapper().valueToTree(result.data().get("facts")));
                var typed = (CashflowFactsV1) facts;
                if (typed.section() != CashflowFactsV1.Section.SUMMARY
                        || !typed.source().subjectRef().equals(request.context().subjectRef())
                        || !typed.period().equals(request.parameters().get("period"))) throw new IllegalArgumentException("RESULT_SUBJECT_MISMATCH");
                data = Map.of("facts", HarnessJson.mapper().convertValue(typed, Map.class),
                        "description", HarnessJson.mapper().convertValue(CashflowConsumer.describe(typed), Map.class));
            } else if (result.status() != CapabilityResultV1.Status.FAILED && result.status() != CapabilityResultV1.Status.NEED_USER) {
                throw new IllegalArgumentException("RESULT_STATUS_INVALID");
            }
            record(request, "PROJECTION", "ALLOW", "CONTRACT_FIELDS_ONLY", returned.policyDigest());
            return new CapabilityResultV1(result.schemaVersion(), result.requestId(), result.traceId(), result.status(),
                    "Cashflow result within declared coverage; verify source gaps.", data, List.of(), List.of(),
                    result.sourceSystem(), result.asOf(), null, result.status() == CapabilityResultV1.Status.FAILED
                    ? new CapabilityResultV1.Failure("SOURCE_FAILURE", "CASHFLOW_QUERY_FAILED", false, "Source query failed", List.of()) : null);
        } catch (SecurityException denied) { throw denied; }
        catch (Exception invalid) {
            record(request, "PROJECTION", "DENY", "RESULT_CONTRACT_REJECTED", returned.policyDigest());
            throw new SecurityException("CASHFLOW_RESULT_REJECTED");
        }
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        if (CAPABILITY.equals(request.capabilityId())) { check(request, "ENTRY"); throw new SecurityException("QUERY_ONLY"); }
        return delegate.executeTask(request);
    }
    private static String hash(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
