package io.github.bitlhk.finance.harness.core.spi;

import io.github.bitlhk.finance.harness.core.*;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Thin host binding, not an authorization server. The host must obtain grants from its authority. */
public final class BoundFinanceProvider implements DescribedFinanceProvider, InvocationAccess, InputCollectionProvider {
    private final DescribedFinanceProvider delegate;
    private final Supplier<Grant> grants;
    private final Map<String, Set<String>> requirements;

    public BoundFinanceProvider(DescribedFinanceProvider delegate, Supplier<Grant> grants,
                                Map<String, Set<String>> requirements) {
        this.delegate = Objects.requireNonNull(delegate);
        this.grants = Objects.requireNonNull(grants);
        Map<String, Set<String>> copy = new java.util.LinkedHashMap<>();
        requirements.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        this.requirements = Map.copyOf(copy);
    }

    public ProviderDescriptorV1 descriptor() { return delegate.descriptor(); }
    @Override public java.util.List<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel>
            observationLabels() { return delegate.observationLabels(); }
    public java.util.List<Class<?>> artifactTypes() {
        java.util.List<Class<?>> types = new java.util.ArrayList<>(delegate.artifactTypes());
        types.add(getClass()); return types.stream().distinct().toList();
    }

    public void checkAccess(CapabilityRequestV1 request) {
        Grant grant = grants.get();
        FinanceContextV1 context = request.context();
        Set<String> required = requirements.get(request.capabilityId());
        if (grant == null || !grant.expiresAt().isAfter(Instant.now())
                || required == null || !delegate.descriptor().supports(request.capabilityId(), request.operation())
                || !grant.scopes().containsAll(required) || !context.scopes().containsAll(required)
                || !grant.principalId().equals(context.principalId()) || grant.principalType() != context.principalType()
                || !grant.subjects().contains(context.subjectRef()) || !grant.purposes().contains(context.purpose())
                || !grant.scopes().containsAll(context.scopes()) || !grant.sessionRef().equals(context.sessionRef())) {
            throw new SecurityException("HOST_GRANT_REJECTED");
        }
        for (String selector : Set.of("customerRef", "subjectRef", "customer_id")) {
            if (request.parameters().containsKey(selector)
                    && !Objects.equals(context.subjectRef(), request.parameters().get(selector))) {
                throw new SecurityException("SUBJECT_SELECTOR_MISMATCH");
            }
        }
        if (delegate instanceof InvocationAccess access) access.checkAccess(request);
    }

    public CapabilityResultV1 query(CapabilityRequestV1 request) { checkAccess(request); return delegate.query(request); }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { checkAccess(request); return delegate.executeTask(request); }
    public Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result) {
        return delegate instanceof InputCollectionProvider collector ? collector.collectableInputs(request, result) : Set.of();
    }

    public record Grant(String principalId, FinanceContextV1.PrincipalType principalType,
                        Set<String> subjects, Set<String> purposes, Set<String> scopes,
                        String sessionRef, String authorityRef, Instant expiresAt) {
        public Grant {
            if (principalId == null || principalId.isBlank() || principalType == null
                    || sessionRef == null || sessionRef.isBlank() || authorityRef == null || authorityRef.isBlank()
                    || expiresAt == null) throw new IllegalArgumentException("Complete host binding is required");
            subjects = Set.copyOf(subjects); purposes = Set.copyOf(purposes); scopes = Set.copyOf(scopes);
        }
    }
}
