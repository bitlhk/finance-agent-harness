package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.mcp.BoundedHttpClient;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import java.time.Instant;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** Captures parent on the Provider thread; SDK HTTP workers never infer request identity. */
final class McpHttpObservation implements AutoCloseable, BoundedHttpClient.Observation {
    private final ManagedProviderFactoryV01.Connection connection;
    private final Map<String, String> links;
    private ObservationRun run;
    private ObservationRun.Span parent;
    McpHttpObservation(ManagedProviderFactoryV01.Connection connection, CapabilityRequestV1 request) {
        this.connection = connection;
        this.links = Map.of("providerId", CuratedMcpProvider.ID, "capabilityId", request.capabilityId(),
                "harnessRequestId", request.context().requestId(), "harnessTraceId", request.context().traceId());
        try {
            run = connection.observation().get();
            if (run != null) parent = run.start(null, "provider-mcp", "exchange", links);
        } catch (RuntimeException ignored) { run = null; parent = null; }
    }
    public Attempt start(String method) {
        ObservationRun.Span span = null;
        try {
            var fields = new LinkedHashMap<>(links);
            fields.put("method", method); fields.put("route", "/mcp");
            if (run != null && parent != null) span = run.start(parent.id(), "provider-http", "attempt", fields);
        } catch (RuntimeException ignored) { }
        return new Attempt(span, method);
    }
    void finish(boolean returned) {
        if (parent != null) parent.finish(returned ? Outcome.RETURNED : Outcome.ERROR, Map.of());
    }
    @Override public void close() { if (parent != null) parent.close(); }

    final class Attempt implements BoundedHttpClient.Observation.Attempt {
        private final ObservationRun.Span span;
        private final String method;
        private boolean ended;
        private Integer status;
        Attempt(ObservationRun.Span span, String method) { this.span = span; this.method = method; }
        public synchronized void headers(int code) {
            if (ended) return;
            status = code;
            // A response proves a dispatched request. sendAsync alone does not prove transmission.
            if (span != null) span.dispatched();
            try { connection.attempts().accept(new ManagedProviderFactoryV01.HttpAttempt(method, "/mcp", code, Instant.now())); }
            catch (RuntimeException ignored) { }
        }
        public synchronized void finish(Throwable failure) {
            if (ended) return;
            ended = true;
            var extra = new LinkedHashMap<String, String>();
            if (status != null) extra.put("httpStatus", status.toString());
            if (failure != null) extra.put("exceptionType", failure.getClass().getSimpleName());
            if (span != null) span.finish(failure == null ? Outcome.RETURNED : Outcome.ERROR, extra);
        }
    }
}
