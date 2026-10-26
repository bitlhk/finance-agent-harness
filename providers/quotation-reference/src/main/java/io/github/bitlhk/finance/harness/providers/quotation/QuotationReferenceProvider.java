package io.github.bitlhk.finance.harness.providers.quotation;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.quotation.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** QUERY-only immutable synthetic source. Host grants and public disclosure are separate boundaries. */
public final class QuotationReferenceProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String ID = "quotation-synthetic-reference", CAPABILITY = "quotation.offer.query";
    public static final String SCOPE = "quotation:read";
    private final QuotationReferenceConfig config;
    private final Clock clock;
    private final Supplier<ManagedProviderFactoryV01.SourceSession> sessions;
    private final AtomicInteger reads = new AtomicInteger();
    QuotationReferenceProvider(QuotationReferenceConfig config, Clock clock,
            Supplier<ManagedProviderFactoryV01.SourceSession> sessions) {
        this.config = Objects.requireNonNull(config); this.clock = Objects.requireNonNull(clock);
        this.sessions = Objects.requireNonNull(sessions);
    }
    @Override public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, ID, "0.1.0-SNAPSHOT",
            List.of(new ProviderDescriptorV1.CapabilitySupport(CAPABILITY, CapabilityRequestV1.Operation.QUERY)));
    }
    @Override public void checkAccess(CapabilityRequestV1 request) {
        var context = request.context(); var session = sessions.get();
        if (session == null || !session.expiresAt().isAfter(clock.instant())
                || !session.subjectRef().equals(context.subjectRef())
                || !session.principalId().equals(context.principalId())
                || !session.hostSessionRef().equals(context.sessionRef())
                || !context.scopes().contains(SCOPE)) throw new SecurityException("QUOTATION_ACCESS_DENIED");
    }
    @Override public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (!CAPABILITY.equals(request.capabilityId()) || request.operation() != CapabilityRequestV1.Operation.QUERY
                || request.idempotencyKey() != null || request.taskRef() != null
                || !request.continuation().isEmpty() || request.confirmation() != null)
            return failed(request, "READ_ONLY_INPUT_REQUIRED");
        var args = request.parameters();
        if (!args.keySet().equals(Set.of("productRef", "currency"))
                || !(args.get("productRef") instanceof String product)
                || !product.matches("[A-Za-z0-9._:-]{1,128}") || !"CNY".equals(args.get("currency")))
            return failed(request, "INVALID_QUERY_INPUT");
        try { checkAccess(request); } catch (SecurityException denied) { return failed(request, "ACCESS_DENIED"); }
        if (!config.permits(request.context().subjectRef(), product)) return failed(request, "RESOURCE_UNAVAILABLE");
        var found = config.find(request.context().subjectRef(), product, "CNY");
        if (found.isEmpty()) return failed(request, "RESOURCE_UNAVAILABLE");
        reads.incrementAndGet();
        QuoteFactsV01 facts = found.get();
        var decision = QuoteDisclosureV01.validateForDisclosure(facts, config.binding(facts), clock.instant());
        if (facts.state() == QuoteFactsV01.State.AVAILABLE && !decision.allowed()) {
            String reason = decision.reason();
            var state = "QUOTE_EXPIRED".equals(reason) ? QuoteFactsV01.State.EXPIRED
                : "NOT_YET_VALID".equals(reason) || "SOURCE_TIME_INVALID".equals(reason)
                    ? QuoteFactsV01.State.UNAVAILABLE : QuoteFactsV01.State.CONFLICT;
            String gap = state == QuoteFactsV01.State.EXPIRED ? "QUOTE_EXPIRED"
                : state == QuoteFactsV01.State.UNAVAILABLE ? ("NOT_YET_VALID".equals(reason) ? reason : "SOURCE_UNAVAILABLE")
                : "FACT_CONFLICT";
            facts = new QuoteFactsV01(facts.schemaVersion(), facts.subjectRef(), facts.productRef(), facts.currency(),
                state, null, List.of(gap), facts.source());
        }
        try { checkAccess(request); } catch (SecurityException denied) { return failed(request, "ACCESS_DENIED"); }
        Map<String,Object> data = QuotationJson.mapper().convertValue(facts, new TypeReference<>() { });
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
            request.context().traceId(), CapabilityResultV1.Status.COMPLETED,
            "已完成合成报价查询；不代表锁价或交易。", freeze(data), List.of(), List.of(), ID, clock.instant(), null, null);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> freeze(Map<String,Object> map) {
        var result = new LinkedHashMap<String,Object>();
        map.forEach((key,value) -> result.put(key, freezeValue(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?,?> map) {
            var result = new LinkedHashMap<String,Object>();
            map.forEach((key,item) -> result.put((String)key, freezeValue(item)));
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) return list.stream().map(QuotationReferenceProvider::freezeValue).toList();
        return value;
    }
    public int readAttempts() { return reads.get(); }
    public int businessWrites() { return 0; }
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { return failed(request, "READ_ONLY_PROVIDER"); }
    private CapabilityResultV1 failed(CapabilityRequestV1 request, String code) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(),
            request.context().traceId(), CapabilityResultV1.Status.FAILED, "无法取得可用报价。", Map.of(),
            List.of(), List.of(), ID, clock.instant(), null,
            new CapabilityResultV1.Failure("REJECTED", code, false, "无法取得可用报价。", List.of()));
    }
}
