package io.github.bitlhk.finance.harness.core;

import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

class ObservedFinanceProviderTest {
    private static <T> T fixture(String file, Class<T> type) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(root.resolve("conformance/fixtures"))) root = root.getParent();
        return HarnessJson.mapper().readValue(root.resolve("conformance/fixtures/v1/valid/" + file).toFile(), type);
    }
    @Test void returnedBusinessFailureIsNotExecutionSuccessAndSinkFailureIsHarmless() throws Exception {
        var request = fixture("capability-request.query.json", CapabilityRequestV1.class);
        var result = fixture("capability-result.failed.json", CapabilityResultV1.class);
        var events = new ArrayList<ObservationEventV01>();
        var run = new ObservationRun("request", "boot", "instance", events::add);
        FinanceProvider source = new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 value) { assertSame(request, value); return result; }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 value) { throw new AssertionError(); }
        };
        var wrapper = new ObservedFinanceProvider(source, "controlled-provider", () -> run);
        assertSame(result, wrapper.query(request));
        assertEquals(Outcome.RETURNED, events.getLast().outcome());
        assertEquals("FAILED", events.getLast().links().get("businessStatus"));
        var unavailable = new ObservationRun("request", "boot", "instance", event -> { throw new IllegalStateException(); });
        assertSame(result, new ObservedFinanceProvider(source, "controlled-provider", () -> unavailable).query(request));
        assertEquals(2, unavailable.droppedEvents());
    }
    @Test void originalExceptionAndAuthorizationBoundaryArePreserved() throws Exception {
        var request = fixture("capability-request.query.json", CapabilityRequestV1.class);
        var events = new ArrayList<ObservationEventV01>();
        var failure = new IllegalStateException("private-business-body");
        class Source implements FinanceProvider, InvocationAccess {
            int checks;
            public void checkAccess(CapabilityRequestV1 value) { checks++; throw new SecurityException("denied"); }
            public CapabilityResultV1 query(CapabilityRequestV1 value) { throw failure; }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 value) { throw new AssertionError(); }
        }
        var source = new Source();
        var wrapper = new ObservedFinanceProvider(source, "controlled-provider",
                () -> new ObservationRun("request", "boot", "instance", events::add));
        assertThrows(SecurityException.class, () -> wrapper.checkAccess(request));
        assertEquals(1, source.checks);
        assertTrue(events.isEmpty());
        assertSame(failure, assertThrows(IllegalStateException.class, () -> wrapper.query(request)));
        assertEquals(Outcome.ERROR, events.getLast().outcome());
        assertFalse(events.toString().contains("private-business-body"));
    }
}
