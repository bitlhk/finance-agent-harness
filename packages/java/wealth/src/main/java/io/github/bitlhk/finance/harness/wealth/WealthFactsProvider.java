package io.github.bitlhk.finance.harness.wealth;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.time.Clock;
import java.util.*;

/** Opt-in query adapter. The original capabilities and response contracts are unchanged. */
public final class WealthFactsProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String CAPABILITY = "wealth.facts.describe";
    public static final String PROVIDER = "wealth-facts-adapter";
    public static final String SCOPE = "wealth:facts:read";
    private final BoundFinanceProvider source;
    private final String sourceCapability;
    private final Set<String> sourceScopes;
    private final WealthFactsMapper.Format format;
    private final WealthSourceSemantics semantics;
    private final WealthFactsV1.SourceKind sourceKind;
    private final Clock clock;
    private final Clock businessClock;

    public WealthFactsProvider(BoundFinanceProvider source, String sourceCapability, Set<String> sourceScopes,
                              WealthFactsMapper.Format format, WealthSourceSemantics semantics,
                              WealthFactsV1.SourceKind sourceKind, Clock clock) {
        this(source, sourceCapability, sourceScopes, format, semantics, sourceKind, clock, clock);
    }
    public WealthFactsProvider(BoundFinanceProvider source, String sourceCapability, Set<String> sourceScopes,
                              WealthFactsMapper.Format format, WealthSourceSemantics semantics,
                              WealthFactsV1.SourceKind sourceKind, Clock businessClock, Clock observationClock) {
        this.source = Objects.requireNonNull(source); this.sourceCapability = Objects.requireNonNull(sourceCapability);
        this.sourceScopes = Set.copyOf(sourceScopes); this.format = Objects.requireNonNull(format);
        this.semantics = Objects.requireNonNull(semantics); this.sourceKind = Objects.requireNonNull(sourceKind);
        this.clock = Objects.requireNonNull(observationClock);
        this.businessClock = Objects.requireNonNull(businessClock);
        if (source.descriptor().capabilities().stream().noneMatch(c -> c.capabilityId().equals(sourceCapability)
                && c.operation() == CapabilityRequestV1.Operation.QUERY)) throw new IllegalArgumentException("Source query not declared");
    }

    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, PROVIDER, "0.1.0",
                List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
    }
    public List<Class<?>> artifactTypes() {
        List<Class<?>> types = new ArrayList<>(source.artifactTypes());
        types.add(getClass()); return types.stream().distinct().toList();
    }
    public void checkAccess(CapabilityRequestV1 request) {
        if (!CAPABILITY.equals(request.capabilityId()) || request.operation() != CapabilityRequestV1.Operation.QUERY
                || !request.context().scopes().contains(SCOPE)) throw new SecurityException("Wealth read permission required");
        if (!request.parameters().isEmpty()) throw new SecurityException("Subject and policy are host-owned; no model selectors");
        source.checkAccess(sourceRequest(request));
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        try {
            checkAccess(request);
            CapabilityResultV1 upstream = source.query(sourceRequest(request));
            if (upstream.status() != CapabilityResultV1.Status.COMPLETED) {
                return failure(request, "SOURCE_QUERY_FAILED", upstream.error() == null ? "UPSTREAM_FAILURE" : upstream.error().failureClass());
            }
            WealthFactsV1 facts = WealthFactsMapper.map(format, upstream, request.context().subjectRef(), semantics, sourceKind, businessClock, clock);
            WealthFactsConsumer.Description description = WealthFactsConsumer.describe(facts);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("facts", HarnessJson.mapper().convertValue(facts, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            data.put("description", HarnessJson.mapper().convertValue(description, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            data.put("sourceReceipt", Map.of("requestId", upstream.requestId(), "traceId", upstream.traceId(),
                    "status", upstream.status().name(), "asOf", upstream.asOf().toString(), "sourceSystem", upstream.sourceSystem()));
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                    CapabilityResultV1.Status.COMPLETED, "Wealth facts described; suitability not evaluated.", data,
                    facts.gaps().stream().map(WealthFactsV1.Gap::code).distinct().toList(), List.of(), PROVIDER, clock.instant(), null, null);
        } catch (SecurityException denied) { return failure(request, "AUTHORIZATION_DENIED", "NOT_AUTHORIZED"); }
        catch (RuntimeException invalid) { return failure(request, "WEALTH_SOURCE_INVALID", "UPSTREAM_FAILURE"); }
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { return failure(request, "READ_ONLY_CAPABILITY", "INVALID_INPUT"); }
    private CapabilityRequestV1 sourceRequest(CapabilityRequestV1 request) {
        var c = request.context();
        var context = new FinanceContextV1(c.schemaVersion(), c.requestId(), c.traceId(), c.principalId(), c.principalType(),
                c.channel(), c.purpose(), c.subjectRef(), c.sessionRef(), c.authenticationLevel(), sourceScopes);
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, sourceCapability, CapabilityRequestV1.Operation.QUERY,
                context, format == WealthFactsMapper.Format.EA ? Map.of("customerRef", c.subjectRef()) : Map.of(), null, null, Map.of(), null);
    }
    private CapabilityResultV1 failure(CapabilityRequestV1 request, String code, String category) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                CapabilityResultV1.Status.FAILED, "Authorized wealth description is unavailable; consult the source owner.", Map.of(),
                List.of(), List.of("VERIFY_SOURCE_AND_AUTHORIZATION"), PROVIDER, clock.instant(), null,
                new CapabilityResultV1.Failure(category, code, false, "No source data exposed", List.of()));
    }
}
