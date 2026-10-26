package io.github.bitlhk.finance.harness.integrations.otel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.tracer.TraceAgentSpan;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class NativeObservationBridgeTest {
    private static final String DIGEST = "a".repeat(64);
    private static final ObjectMapper JSON = new ObjectMapper();

    private NativeObservationBridge.Request request(NativeObservationBridge bridge, String id) {
        return bridge.open(id, "instance", "boot", DIGEST, "full", true);
    }
    private List<Map<String, Object>> drain(NativeObservationBridge.Request request) {
        var rows = new ArrayList<Map<String, Object>>();
        Map<String, Object> row;
        while ((row = request.poll()) != null) rows.add(row);
        return rows;
    }
    private TraceAgentSpan span(String id, String parent) {
        return new TraceAgentSpan("framework-trace", id, parent);
    }

    @Test void actualSdkHandlerProducesMetadataOnlyStartAndEnd() throws Exception {
        var global = GlobalOpenTelemetry.get();
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "r1")) {
            var handler = bridge.sdkHandler();
            try (var scope = request.bind()) {
                var sdk = span("invocation-1", null);
                handler.onLlmStart(sdk, Map.of("prompt", "SENSITIVE_PROMPT_SENTINEL"), Map.of("class_name", "SENSITIVE_MODEL_LABEL"));
                handler.onLlmEnd(sdk, Map.of("content", "SENSITIVE_RESPONSE_SENTINEL"));
            }
            var rows = drain(request);
            assertEquals(2, rows.size());
            assertEquals("START", rows.getFirst().get("phase"));
            assertEquals("END", rows.getLast().get("phase"));
            assertEquals("RETURNED", rows.getLast().get("status"));
            assertEquals(rows.getFirst().get("sdkSpanId"), rows.getLast().get("sdkSpanId"));
            assertTrue(rows.getFirst().get("sdkTraceId").toString().matches("[0-9a-f]{32}"));
            assertFalse(JSON.writeValueAsString(rows).contains("SENSITIVE_"));
            assertFalse(rows.getLast().containsKey("usage")); // Missing is not zero.
            assertEquals("UNVERIFIED", rows.getLast().get("supportState"));
            assertSame(global, GlobalOpenTelemetry.get());
            var output = java.nio.file.Path.of("target/native-sdk-records.json");
            java.nio.file.Files.createDirectories(output.getParent());
            JSON.writeValue(output.toFile(), rows);
        }
    }

    @Test void explicitLinksAreCapturedOnStartNotGuessedAtEnd() {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "r1")) {
            var handler = bridge.sdkHandler(); var sdk = span("invoke1", null);
            try (var scope = request.bind(); var links = request.bindLinks("financial-1", "tool-1")) {
                handler.onPluginStart(sdk, Map.of(), Map.of("class_name", "tool"));
            }
            handler.onPluginEnd(sdk, Map.of());
            var rows = drain(request);
            assertEquals(2, rows.size());
            assertEquals(rows.getFirst().get("links"), rows.getLast().get("links"));
            assertEquals("financial-1", ((Map<?, ?>)rows.getLast().get("links")).get("financialSpanId"));
        }
    }

    @Test void foreignEndContextDoesNotRelabelOwner() {
        try (var bridge = new NativeObservationBridge();
             var first = request(bridge, "first"); var second = request(bridge, "second")) {
            var handler = bridge.sdkHandler(); var sdk = span("operation", null);
            try (var scope = first.bind()) { handler.onLlmStart(sdk, Map.of(), Map.of()); }
            try (var scope = second.bind()) { handler.onLlmEnd(sdk, Map.of()); }
            assertTrue(drain(first).stream().allMatch(r -> r.get("requestId").equals("first")));
            assertTrue(drain(second).isEmpty());
        }
    }

    @Test void noRequestContextDoesNotInventAssociation() {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "unused")) {
            var handler = bridge.sdkHandler(); var sdk = span("unbound", null);
            handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            assertTrue(drain(request).isEmpty());
            assertEquals(1, bridge.unboundSpans());
        }
    }

    @Test void offAndUnselectedRequestsEmitOnlyCoverage() {
        try (var bridge = new NativeObservationBridge()) {
            for (String mode : List.of("off", "sample")) {
                try (var request = bridge.open("r-" + mode, "instance", "boot", DIGEST, mode, false)) {
                    var handler = bridge.sdkHandler(); var sdk = span("not-selected", null);
                    try (var scope = request.bind()) {
                        handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
                    }
                    assertTrue(drain(request).isEmpty());
                    request.close();
                    var rows = drain(request);
                    assertEquals(1, rows.size());
                    assertEquals("COVERAGE", rows.getFirst().get("kind"));
                    assertEquals(false, rows.getFirst().get("sampled"));
                    assertFalse(rows.getFirst().containsKey("sdkSpanId"));
                }
            }
        }
    }

    @Test void selectedSamplingKeepsWholeRequestDecision() {
        try (var bridge = new NativeObservationBridge();
             var request = bridge.open("sample", "i", "b", DIGEST, "sample", true);
             var scope = request.bind()) {
            var handler = bridge.sdkHandler();
            for (int i = 0; i < 3; i++) {
                var sdk = span("model-" + i, null);
                handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            }
            var rows = drain(request);
            assertEquals(6, rows.size());
            assertTrue(rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("sampled"))));
        }
    }

    @Test void finishAndLateEndCannotReviveRun() {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "late")) {
            var handler = bridge.sdkHandler(); var sdk = span("late-span", null);
            try (var scope = request.bind()) { handler.onLlmStart(sdk, Map.of(), Map.of()); }
            request.close(); request.close();
            handler.onLlmEnd(sdk, Map.of("content", "SENSITIVE_LATE"));
            var rows = drain(request);
            assertEquals(1, rows.stream().filter(r -> "SDK_SPAN".equals(r.get("kind"))).count());
            assertTrue(rows.stream().filter(r -> "COVERAGE".equals(r.get("kind")))
                    .anyMatch(r -> ((Number)r.get("lateRecords")).longValue() > 0));
            assertThrows(IllegalStateException.class, request::bind);
        }
    }

    @Test void errorMessageAndStackNeverReachMetadata() throws Exception {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "error");
             var scope = request.bind()) {
            var handler = bridge.sdkHandler(); var sdk = span("failure", null);
            handler.onLlmStart(sdk, Map.of(), Map.of());
            handler.onLlmError(sdk, new IllegalStateException("SENSITIVE_EXCEPTION_BODY"));
            var rows = drain(request);
            assertEquals("ERROR", rows.getLast().get("status"));
            assertFalse(JSON.writeValueAsString(rows).contains("SENSITIVE_EXCEPTION_BODY"));
            assertFalse(rows.getLast().containsKey("businessStatus"));
        }
    }

    @Test void nestedNativeParentIsPreservedWithoutSummingDurations() {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "nested");
             var scope = request.bind()) {
            var handler = bridge.sdkHandler(); var root = span("root", null); var child = span("child", "root");
            handler.onChainStart(root, Map.of(), Map.of());
            handler.onLlmStart(child, Map.of(), Map.of());
            handler.onLlmEnd(child, Map.of()); handler.onChainEnd(root, Map.of());
            var rows = drain(request);
            assertEquals(4, rows.size());
            assertEquals(rows.getFirst().get("sdkSpanId"), rows.get(1).get("parentSdkSpanId"));
            assertFalse(rows.getLast().containsKey("totalDurationNanos"));
        }
    }

    @Test void explicitContextPropagationAcrossThreadWorks() throws Exception {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "async");
             var workers = Executors.newSingleThreadExecutor()) {
            Context captured;
            try (var scope = request.bind()) { captured = Context.current(); }
            workers.submit(captured.wrap(() -> {
                var handler = bridge.sdkHandler(); var sdk = span("worker", null);
                handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            })).get();
            assertEquals(2, drain(request).size());
        }
    }

    @Test void queuesAndRequestBytesRemainBoundedEvenWithoutPolling() throws Exception {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "budget");
             var scope = request.bind()) {
            var handler = bridge.sdkHandler();
            for (int i = 0; i < 1000; i++) {
                var sdk = span("many-" + i, null);
                handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            }
            request.close();
            var rows = drain(request);
            assertTrue(rows.size() <= NativeObservationBridge.MAX_QUEUE);
            assertEquals("COVERAGE", rows.getLast().get("kind"));
            assertTrue(((Number)rows.getLast().get("droppedRecords")).longValue() > 0);
            long bytes = 0;
            for (var row : rows) {
                int count = JSON.writeValueAsBytes(row).length;
                assertTrue(count <= NativeObservationBridge.MAX_RECORD_BYTES); bytes += count;
            }
            assertTrue(bytes <= NativeObservationBridge.MAX_REQUEST_BYTES);
        }
    }

    @Test void invalidHostIdentityAndSamplingAreRejectedBeforeCapture() {
        try (var bridge = new NativeObservationBridge()) {
            assertThrows(IllegalArgumentException.class, () -> bridge.open("../private", "i", "b", DIGEST, "full", true));
            assertThrows(IllegalArgumentException.class, () -> bridge.open("r", "i", "b", "bad", "full", true));
            assertThrows(IllegalArgumentException.class, () -> bridge.open("r", "i", "b", DIGEST, "off", true));
            assertThrows(IllegalArgumentException.class, () -> bridge.open("r", "i", "b", DIGEST, "full", false));
        }
    }

    @Test void queueResultsAreImmutable() {
        try (var bridge = new NativeObservationBridge(); var request = request(bridge, "immutable");
             var scope = request.bind()) {
            var handler = bridge.sdkHandler(); var sdk = span("x", null);
            handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            var row = request.poll();
            assertThrows(UnsupportedOperationException.class, () -> row.put("prompt", "bad"));
            assertThrows(UnsupportedOperationException.class, () -> ((Map)row.get("source")).put("headers", "bad"));
        }
    }

    @Test void shutdownClosesOnlyOwnedProviderAndForbidsNewRequests() {
        var bridge = new NativeObservationBridge(); var request = request(bridge, "stop");
        var handler = bridge.sdkHandler(); var sdk = span("running", null);
        try (var scope = request.bind()) { handler.onLlmStart(sdk, Map.of(), Map.of()); }
        bridge.close(); bridge.close();
        assertThrows(IllegalStateException.class, () -> request(bridge, "next"));
        assertThrows(IllegalStateException.class, request::bind);
        assertEquals("COVERAGE", drain(request).getLast().get("kind"));
        assertTrue(bridge.forceFlush().isSuccess()); // Queue availability, not disk durability.
    }

    @Test void explicitProcessorShutdownAlsoFinalizesUnselectedRequests() {
        var bridge = new NativeObservationBridge();
        var request = bridge.open("off", "i", "b", DIGEST, "off", false);
        assertTrue(bridge.shutdown().isSuccess());
        assertThrows(IllegalStateException.class, request::bind);
        assertEquals("COVERAGE", drain(request).getLast().get("kind"));
        bridge.close(); bridge.close();
    }

    @Test void unfinishedPreviousSdkScopeCannotBecomeNextRequestParent() {
        try (var bridge = new NativeObservationBridge();
             var first = request(bridge, "cancelled"); var second = request(bridge, "next")) {
            var oldHandler = bridge.sdkHandler(); var old = span("unfinished", null);
            try (var scope = first.bind()) { oldHandler.onLlmStart(old, Map.of(), Map.of()); }
            first.close();
            var oldRows = drain(first);
            var handler = bridge.sdkHandler(); var sdk = span("next-operation", null);
            try (var scope = second.bind()) {
                handler.onLlmStart(sdk, Map.of(), Map.of()); handler.onLlmEnd(sdk, Map.of());
            }
            var rows = drain(second);
            assertEquals(2, rows.size());
            assertFalse(rows.getFirst().containsKey("parentSdkSpanId"));
            assertNotEquals(oldRows.getFirst().get("sdkTraceId"), rows.getFirst().get("sdkTraceId"));
            // The late SDK event is not remapped to the next Host request.
            oldHandler.onLlmEnd(old, Map.of());
            assertTrue(drain(second).isEmpty());
        }
    }
}
