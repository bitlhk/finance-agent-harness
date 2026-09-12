package io.github.bitlhk.finance.harness.core;

import io.github.bitlhk.finance.harness.core.observation.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationRunTest {
    @Test void recordsAttemptWithoutInventingDispatchOrSuccess() {
        var events = new ArrayList<ObservationEventV01>();
        var run = new ObservationRun("request", "boot", "instance", events::add);
        try (var span = run.start(null, "model", "attempt", Map.of())) {
            span.notSent(); span.finish(Outcome.DENIED, Map.of());
        }
        assertEquals(2, events.size());
        assertEquals(Dispatch.NOT_SENT, events.get(1).dispatch());
        assertEquals(Outcome.DENIED, events.get(1).outcome());
        assertEquals(2, events.get(1).sequence());
        assertNotNull(events.get(1).durationNanos());
    }
    @Test void sinkFailureAndUnknownMetadataDoNotEscape() {
        var run = new ObservationRun("request", "boot", "instance", event -> { throw new IllegalStateException(); });
        try (var span = run.start(null, "provider", "query", Map.of())) {
            span.dispatched(); span.finish(Outcome.COMPLETED, Map.of());
        }
        assertEquals(3, run.droppedEvents());
        var events = new ArrayList<ObservationEventV01>();
        var safe = new ObservationRun("request", "boot", "instance", events::add);
        try (var span = safe.start(null, "provider", "query", Map.of("body", "customer-secret"))) { }
        assertTrue(events.isEmpty());
        assertEquals(2, safe.droppedEvents());
    }
    @Test void closingUnfinishedSpanDoesNotClaimSuccess() {
        var events = new ArrayList<ObservationEventV01>();
        var run = new ObservationRun("request", "boot", "instance", events::add);
        try (var span = run.start(null, "tool", "call", Map.of("toolCallId", "call-1"))) { }
        assertEquals(Outcome.INCOMPLETE, events.get(1).outcome());
        assertEquals(Dispatch.NOT_OBSERVED, events.get(1).dispatch());
    }
}
