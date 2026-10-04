package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.model.transport.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;
import java.util.Map;
import reactor.core.publisher.Flux;

/** A supported SDK transport decorator. The official transport owns HTTP, not this adapter. */
public final class CheckedTransport implements HttpTransport {
    private final HttpTransport delegate;
    private final AgentScopeQuerySession.Invocation call;
    private final ModelConnection connection;
    private final String callId;
    CheckedTransport(HttpTransport delegate, AgentScopeQuerySession.Invocation call, ModelConnection connection, String callId) {
        this.delegate = delegate; this.call = call; this.connection = connection; this.callId = callId;
    }
    private void before(HttpRequest request, boolean stream) {
        call.requireAuthorized("model-http");
        try {
            if (stream || HarnessJson.mapper().readTree(request.getBody()).path("stream").asBoolean()
                    || !(connection.baseUri() + "/chat/completions").equals(request.getUrl()))
                throw new SecurityException("UNSUPPORTED_MODEL_WIRE_MODE");
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("MODEL_REQUEST_INVALID", malformed); }
    }
    @Override public HttpResponse execute(HttpRequest request) {
        before(request, false);
        try {
            Map<String, Object> body = HarnessJson.mapper().readValue(request.getBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            var projection = new java.util.LinkedHashMap<String, Object>();
            for (String key : java.util.List.of("model", "messages", "tools", "temperature", "top_p", "max_tokens", "stream", "thinking")) if (body.containsKey(key)) projection.put(key, body.get(key));
            call.capture(callId, "CLIENT_REQUEST_PREPARED", projection);
        } catch (java.io.IOException malformed) { throw new IllegalArgumentException("MODEL_REQUEST_INVALID", malformed); }
        before(request, false);
        try (var span = call.run.start(call.parent, "model-http", "client-transport-invocation", Map.of("harnessRequestId", call.context.requestId(), "attempt", "1"))) {
            try {
                var response = delegate.execute(request);
                // A returned response is evidence of a completed exchange, not a pre-send assertion.
                span.dispatched(); span.finish(Outcome.RETURNED, Map.of("httpStatus", Integer.toString(response.getStatusCode())));
                return response;
            } catch (RuntimeException failure) {
                span.finish(Outcome.ERROR, Map.of("exceptionType", failure.getClass().getSimpleName())); throw failure;
            }
        }
    }
    @Override public Flux<String> stream(HttpRequest request) {
        return Flux.defer(() -> { before(request, true); return Flux.error(new SecurityException("STREAM_DISABLED")); });
    }
    @Override public void close() { delegate.close(); }
}
