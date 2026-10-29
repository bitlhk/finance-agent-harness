package io.github.bitlhk.finance.harness.providers.kyp;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import io.github.bitlhk.finance.harness.mcp.BoundedHttpClient;
import java.time.Instant;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;

/** Transport metadata only; a successful HTTP exchange is not a suitability decision. */
final class KypHttpObservation implements BoundedHttpClient.Observation, AutoCloseable {
    private final ManagedProviderFactoryV01.Connection connection;
    private final Map<String,String> links;
    private ObservationRun run;
    private ObservationRun.Span parent;
    KypHttpObservation(ManagedProviderFactoryV01.Connection connection, CapabilityRequestV1 request, String source) {
        this.connection = connection;
        links = Map.of("providerId", KypProductFactory.ID, "capabilityId", request.capabilityId(),
                "harnessRequestId", request.context().requestId(), "harnessTraceId", request.context().traceId(), "sourceId", source);
        try {
            run = connection.observation().get();
            if (run != null) parent = run.start(null, "provider-mcp", "exchange", links);
        } catch (RuntimeException unavailable) { run = null; parent = null; }
    }
    public Attempt start(String method) {
        ObservationRun.Span span = null;
        if (parent != null) {
            var fields = new LinkedHashMap<>(links); fields.put("method", method); fields.put("route", "product-source");
            try { span = run.start(parent.id(), "provider-http", "attempt", fields); }
            catch (RuntimeException unavailable) { }
        }
        final var captured = span;
        return new Attempt() {
            Integer status;
            public synchronized void headers(int code) {
                status = code;
                if (captured != null) captured.dispatched();
                try { connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt(method, "product-source", code, Instant.now())); }
                catch (RuntimeException unavailable) { }
            }
            public synchronized void finish(Throwable failure) {
                if (captured == null) return;
                var fields = new LinkedHashMap<String,String>();
                if (status != null) fields.put("httpStatus", status.toString());
                if (failure != null) fields.put("exceptionType", failure.getClass().getSimpleName());
                captured.finish(failure == null ? Outcome.RETURNED : Outcome.ERROR, fields);
            }
        };
    }
    void returned() { if (parent != null) parent.finish(Outcome.RETURNED, Map.of()); }
    public void close() { if (parent != null) parent.close(); }
}
