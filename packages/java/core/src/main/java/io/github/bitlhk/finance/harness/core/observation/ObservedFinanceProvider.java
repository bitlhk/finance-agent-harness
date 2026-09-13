package io.github.bitlhk.finance.harness.core.observation;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** Optional observation decorator; preserves delegate access and input-collection protocols. */
public final class ObservedFinanceProvider implements InputCollectionProvider, InvocationAccess, DescribedFinanceProvider {
    private final FinanceProvider delegate;
    private final Supplier<ObservationRun> current;
    private final String providerId;
    public ObservedFinanceProvider(FinanceProvider delegate, String providerId, Supplier<ObservationRun> current) {
        this.delegate = java.util.Objects.requireNonNull(delegate);
        this.providerId = providerId; this.current = current;
    }
    @Override public void checkAccess(CapabilityRequestV1 request) {
        if (delegate instanceof InvocationAccess access) access.checkAccess(request);
    }
    @Override public ProviderDescriptorV1 descriptor() {
        if (!(delegate instanceof DescribedFinanceProvider described))
            throw new IllegalStateException("DESCRIBED_PROVIDER_REQUIRED");
        return described.descriptor();
    }
    @Override public java.util.List<ObservationDisplayV01.SourceLabel> observationLabels() {
        return delegate instanceof DescribedFinanceProvider described ? described.observationLabels() : java.util.List.of();
    }
    @Override public java.util.List<Class<?>> artifactTypes() {
        var types = new java.util.ArrayList<Class<?>>();
        types.add(ObservedFinanceProvider.class);
        if (delegate instanceof DescribedFinanceProvider described) types.addAll(described.artifactTypes());
        else types.add(delegate.getClass());
        return java.util.List.copyOf(types);
    }
    @Override public Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result) {
        return delegate instanceof InputCollectionProvider collection ? collection.collectableInputs(request, result) : Set.of();
    }
    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        return invoke(request, false);
    }
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        return invoke(request, true);
    }
    private CapabilityResultV1 invoke(CapabilityRequestV1 request, boolean task) {
        ObservationRun.Span span = null;
        ObservationRun run = null;
        try {
            run = current.get();
            if (run != null) span = run.start(null, "capability", task ? "task" : "query", Map.of(
                    "capabilityId", request.capabilityId(), "providerId", providerId,
                    "harnessRequestId", request.context().requestId(), "harnessTraceId", request.context().traceId()));
        } catch (RuntimeException ignored) { }
        try (var scope = span == null ? null : run.bindParent(span.id())) {
            var result = task ? delegate.executeTask(request) : delegate.query(request);
            if (span != null) span.finish(Outcome.RETURNED, Map.of("businessStatus", result.status().name()));
            return result;
        } catch (RuntimeException failure) {
            if (span != null) span.finish(failure instanceof SecurityException ? Outcome.DENIED : Outcome.ERROR,
                    Map.of("exceptionType", failure.getClass().getSimpleName()));
            throw failure;
        } finally { if (span != null) span.close(); }
    }
}
