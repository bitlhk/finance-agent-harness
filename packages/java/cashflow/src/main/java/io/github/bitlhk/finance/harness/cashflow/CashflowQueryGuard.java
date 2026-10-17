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
    public record Authorization(BoundFinanceProvider.Grant grant, String policyDigest, String reason) {
        public Authorization(BoundFinanceProvider.Grant grant, String policyDigest) {
            this(grant, policyDigest, grant == null ? "AUTHORIZATION_DENIED" : "CURRENT_GRANT_VALID");
        }
        public Authorization {
            if (!policyDigest.matches("sha256:[a-f0-9]{64}|UNOBSERVED"))
                throw new IllegalArgumentException("POLICY_DIGEST_INVALID");
            if (!Set.of("AUTHORIZATION_DENIED", "AUTHORIZATION_EXPIRED", "AUTHORITY_UNAVAILABLE", "CURRENT_GRANT_VALID").contains(reason))
                throw new IllegalArgumentException("AUTHORITY_REASON_INVALID");
        }
    }
    public static final class AuthorityFailure extends SecurityException {
        private final String code;
        public AuthorityFailure(String code) {
            super(Set.of("AUTHORIZATION_DENIED", "AUTHORIZATION_EXPIRED", "AUTHORITY_UNAVAILABLE", "AUTHORIZATION_AUDIT_UNAVAILABLE").contains(code)
                    ? code : "AUTHORIZATION_DENIED"); this.code = getMessage();
        }
        public String code() { return code; }
    }
    private final DescribedFinanceProvider delegate;
    private final Supplier<Authorization> authority;
    private final Predicate<Map<String,Object>> audit;
    private final String bindingDigest;
    private final Supplier<Map<String,String>> hostBinding;
    private static final class GuardFailure extends RuntimeException {
        final String code, stage;
        GuardFailure(String code, String stage) { super(code); this.code = code; this.stage = stage; }
    }
    public CashflowQueryGuard(DescribedFinanceProvider delegate, Supplier<Authorization> authority,
                              Predicate<Map<String,Object>> audit, String bindingDigest) {
        this(delegate, authority, audit, bindingDigest, Map::of);
    }
    public CashflowQueryGuard(DescribedFinanceProvider delegate, Supplier<Authorization> authority,
                              Predicate<Map<String,Object>> audit, String bindingDigest,
                              Supplier<Map<String,String>> hostBinding) {
        this.delegate = Objects.requireNonNull(delegate);
        this.authority = Objects.requireNonNull(authority); this.audit = Objects.requireNonNull(audit);
        if (!bindingDigest.matches("sha256:[a-f0-9]{64}")) throw new IllegalArgumentException("GUARD_BINDING_REQUIRED");
        this.bindingDigest = bindingDigest;
        this.hostBinding = Objects.requireNonNull(hostBinding);
    }
    public ProviderDescriptorV1 descriptor() { return delegate.descriptor(); }
    public List<Class<?>> artifactTypes() {
        var types = new ArrayList<>(delegate.artifactTypes()); types.add(getClass()); types.add(CashflowExecutionBinding.class); return List.copyOf(types);
    }
    public List<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel> observationLabels() {
        return delegate.observationLabels();
    }
    public Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result) {
        return delegate instanceof InputCollectionProvider input ? input.collectableInputs(request, result) : Set.of();
    }
    public void checkAccess(CapabilityRequestV1 request) {
        try { if (delegate instanceof InvocationAccess access) access.checkAccess(request); }
        catch (SecurityException denied) {
            if (CAPABILITY.equals(request.capabilityId())) {
                var attempt = new Attempt(request);
                try { attempt.record(request, "ENTRY", "DENY", denied instanceof AuthorityFailure typed ? typed.code() : "AUTHORIZATION_DENIED", "UNOBSERVED"); }
                catch (GuardFailure unavailable) { throw new AuthorityFailure("AUTHORIZATION_AUDIT_UNAVAILABLE"); }
            }
            throw denied;
        }
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (!CAPABILITY.equals(request.capabilityId())) return delegate.query(request);
        Attempt attempt;
        try { attempt = new Attempt(request); }
        catch (RuntimeException unavailable) { return failure(request, "AUTHORIZATION_AUDIT_UNAVAILABLE", "ENTRY"); }
        try { return attempt.guardedQuery(request); }
        catch (GuardFailure failure) {
            var result = failure(request, failure.code, failure.stage);
            var data = new LinkedHashMap<String,Object>(result.data());
            data.put("executionBindingId", attempt.binding.fields().get("executionBindingId"));
            data.put("providerDispatch", attempt.entered ? "PROVIDER_ENTERED_HTTP_UNOBSERVED" : "NOT_ENTERED");
            data.put("resultReleased", false);
            return new CapabilityResultV1(result.schemaVersion(), result.requestId(), result.traceId(), result.status(),
                    result.content(), data, result.missingInputs(), result.nextActions(), result.sourceSystem(), result.asOf(), null, result.error());
        }
    }
    private final class Attempt {
    final CashflowExecutionBinding binding;
    boolean entered;
    Authorization observed;
    String authorityCheckedAt = "UNOBSERVED";
    String resultDigest = "UNOBSERVED";
    Attempt(CapabilityRequestV1 request) { binding = new CashflowExecutionBinding(request, Map.copyOf(hostBinding.get()), bindingDigest); }
    private void record(CapabilityRequestV1 request, String stage, String decision, String reason, String policy) {
        var event = new LinkedHashMap<String,Object>(binding.fields());
        event.put("recordType", "cashflow-authorization.v2"); event.put("requestId", request.context().requestId());
        event.put("traceId", request.context().traceId()); event.put("capabilityId", CAPABILITY);
        event.put("stage", stage); event.put("decision", decision); event.put("reason", reason);
        event.put("subjectDigest", hash(request.context().subjectRef()));
        event.put("sessionDigest", hash(request.context().sessionRef()));
        event.put("principalDigest", hash(request.context().principalId()));
        event.put("purpose", request.context().purpose()); event.put("operation", request.operation().name());
        Object period = request.parameters().get("period");
        event.put("period", period instanceof String text && text.matches("[0-9]{4}-(0[1-9]|1[0-2])") ? period : "INVALID_OR_MISSING");
        event.put("policyDigest", policy); event.put("bindingDigest", bindingDigest);
        event.put("localPolicyDigest", policy);
        event.put("authorizationBasis", "LOCAL_SYNTHETIC_POLICY_AND_BANK_SESSION_IDENTITY");
        event.put("bankPolicyVersion", "UNKNOWN");
        event.put("checkedAt", authorityCheckedAt);
        event.put("recordedAt", java.time.Instant.now().toString());
        event.put("providerDispatch", entered ? "PROVIDER_ENTERED_HTTP_UNOBSERVED" : "NOT_ENTERED");
        event.put("resultDigest", resultDigest);
        event.put("releaseDecision", stage.equals("PROJECTION") && decision.equals("ALLOW") && reason.equals("CONTRACT_FIELDS_ONLY") ? "ALLOW" : "NOT_ALLOWED");
        if (observed != null && observed.grant() != null) {
            var grant = observed.grant();
            event.put("effectiveExpiresAt", grant.expiresAt().toString());
            event.put("authorityRef", grant.authorityRef());
            event.put("effectiveScopesDigest", CashflowExecutionBinding.digest(new TreeSet<>(grant.scopes())));
        }
        boolean accepted;
        try { accepted = audit.test(Collections.unmodifiableMap(event)); }
        catch (RuntimeException unavailable) { accepted = false; }
        if (!accepted) throw new GuardFailure("AUTHORIZATION_AUDIT_UNAVAILABLE", stage);
    }
    private Authorization check(CapabilityRequestV1 request, String stage) {
        Authorization snapshot = null;
        observed = null;
        authorityCheckedAt = "UNOBSERVED";
        try {
            snapshot = authority.get();
            authorityCheckedAt = java.time.Instant.now().toString();
            if (snapshot == null) throw new SecurityException();
            observed = snapshot;
            if (snapshot.grant() == null) throw new AuthorityFailure(snapshot.reason());
            if (!snapshot.grant().expiresAt().isAfter(java.time.Instant.now())) throw new AuthorityFailure("AUTHORIZATION_EXPIRED");
            BoundFinanceProvider.checkContext(snapshot.grant(), request.context());
            if (request.operation() != CapabilityRequestV1.Operation.QUERY
                    || !snapshot.grant().scopes().contains("cashflow:read")
                    || !request.context().scopes().contains("cashflow:read")
                    || !delegate.descriptor().supports(CAPABILITY, request.operation())) throw new SecurityException();
            for (String selector : Set.of("customerRef", "subjectRef", "customer_id"))
                if (request.parameters().containsKey(selector)
                        && !Objects.equals(request.context().subjectRef(), request.parameters().get(selector)))
                    throw new SecurityException();
            if (delegate instanceof InvocationAccess access) access.checkAccess(request);
        } catch (RuntimeException denied) {
            String reason = denied instanceof AuthorityFailure typed ? typed.code() : "AUTHORIZATION_DENIED";
            record(request, stage, "DENY", reason, snapshot == null ? "UNOBSERVED" : snapshot.policyDigest());
            if (reason.equals("AUTHORITY_UNAVAILABLE")) throw new GuardFailure(reason, stage);
            if (reason.equals("AUTHORIZATION_EXPIRED")) throw new AuthorityFailure(reason);
            throw new SecurityException("CASHFLOW_GUARD_REJECTED");
        }
        record(request, stage, "ALLOW", "CURRENT_GRANT_VALID", snapshot.policyDigest());
        return snapshot;
    }
    private CapabilityResultV1 guardedQuery(CapabilityRequestV1 request) {
        check(request, "ENTRY");
        if (!Set.of("period").containsAll(request.parameters().keySet()))
            return failure(request, "CASHFLOW_PARAMETERS_INVALID", "INPUT");
        Object period = request.parameters().get("period");
        if (period == null || "".equals(period)) {
            var returned = check(request, "RETURN");
            record(request, "PROJECTION", "ALLOW", "PERIOD_REQUIRED", returned.policyDigest());
            return needPeriod(request);
        }
        try {
            if (!(period instanceof String text)) throw new IllegalArgumentException();
            CashflowFactsV1.month(text);
        } catch (IllegalArgumentException invalid) { return failure(request, "CASHFLOW_PERIOD_INVALID", "INPUT"); }
        CapabilityResultV1 result;
        try { entered = true; result = delegate.query(request); }
        catch (RuntimeException failed) {
            record(request, "RETURN", "DENY", "SOURCE_QUERY_FAILED", "UNOBSERVED");
            throw new GuardFailure("CASHFLOW_SOURCE_FAILED", "SOURCE");
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
            if (result.status() == CapabilityResultV1.Status.NEED_USER) {
                if (!result.missingInputs().equals(List.of("period")))
                    throw new IllegalArgumentException("UNSUPPORTED_MISSING_INPUT");
                record(request, "PROJECTION", "ALLOW", "PERIOD_REQUIRED", returned.policyDigest());
                return needPeriod(request);
            }
            if (result.status() == CapabilityResultV1.Status.FAILED)
                return failure(request, publicSourceCode(result.error().sourceCode()), "SOURCE");
            resultDigest = CashflowExecutionBinding.digest(data);
            record(request, "PROJECTION", "ALLOW", "CONTRACT_FIELDS_ONLY", returned.policyDigest());
            return new CapabilityResultV1(result.schemaVersion(), result.requestId(), result.traceId(), result.status(),
                    "Cashflow result within declared coverage; verify source gaps.", data, List.of(), List.of(),
                    result.sourceSystem(), result.asOf(), null, null);
        } catch (GuardFailure failure) { throw failure;
        } catch (SecurityException denied) { throw denied; }
        catch (Exception invalid) {
            record(request, "PROJECTION", "DENY", "RESULT_CONTRACT_REJECTED", returned.policyDigest());
            throw new GuardFailure("CASHFLOW_RESULT_REJECTED", "PROJECTION");
        }
    }
    }
    private static String publicSourceCode(String code) {
        return Set.of("CASHFLOW_SOURCE_POLICY_BLOCKED", "CASHFLOW_SOURCE_INVALID",
                "CASHFLOW_PARAMETERS_INVALID", "CASHFLOW_PERIOD_INVALID").contains(code)
                ? code : "CASHFLOW_SOURCE_FAILED";
    }
    private static CapabilityResultV1 needPeriod(CapabilityRequestV1 request) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                CapabilityResultV1.Status.NEED_USER, "Provide an absolute period in YYYY-MM; no default month is used.",
                Map.of(), List.of("period"), List.of("PROVIDE_PERIOD"), "cashflow-query-guard", java.time.Instant.now(), null, null);
    }
    private static CapabilityResultV1 failure(CapabilityRequestV1 request, String code, String stage) {
        String category = switch (stage) {
            case "INPUT" -> "INVALID_INPUT";
            case "PROJECTION" -> "RESULT_CONTRACT_FAILURE";
            case "SOURCE" -> "SOURCE_FAILURE";
            default -> "AUDIT_UNAVAILABLE";
        };
        if (code.equals("AUTHORIZATION_AUDIT_UNAVAILABLE")) category = "AUDIT_UNAVAILABLE";
        if (code.equals("AUTHORITY_UNAVAILABLE")) category = "AUTHORITY_UNAVAILABLE";
        if (code.equals("AUTHORIZATION_EXPIRED")) category = "AUTHORIZATION_EXPIRED";
        List<String> next = switch (code) {
            case "CASHFLOW_PERIOD_INVALID", "CASHFLOW_PARAMETERS_INVALID" -> List.of("CHECK_PERIOD");
            case "CASHFLOW_SOURCE_POLICY_BLOCKED" -> List.of("VERIFY_SOURCE_POLICY");
            case "CASHFLOW_SOURCE_INVALID", "CASHFLOW_RESULT_REJECTED" -> List.of("VERIFY_SOURCE_CONTRACT");
            default -> List.of();
        };
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                CapabilityResultV1.Status.FAILED, "Cashflow query incomplete; inspect the safe failure code and stage.",
                Map.of("failureStage", stage), List.of(), next, "cashflow-query-guard", java.time.Instant.now(), null,
                new CapabilityResultV1.Failure(category, code, false, "Cashflow query incomplete", List.of()));
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        if (CAPABILITY.equals(request.capabilityId())) { new Attempt(request).check(request, "ENTRY"); throw new SecurityException("QUERY_ONLY"); }
        return delegate.executeTask(request);
    }
    private static String hash(String value) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
