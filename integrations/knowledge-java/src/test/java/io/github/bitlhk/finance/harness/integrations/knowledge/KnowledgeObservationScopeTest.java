package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeObservationScopeTest {
    ObservationRun run(String id) { return new ObservationRun(id, "boot", "instance", event -> true); }

    @Test void propagatesOnlyInsideExplicitToolWorkerScope() throws Exception {
        var scope = new KnowledgeObservationScope();
        var original = run("first");
        scope.begin(original);
        assertThrows(SecurityException.class, scope::current);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(SecurityException.class, scope::current);
                scope.enterTool();
                assertSame(original, scope.current());
                scope.leaveTool();
                assertThrows(SecurityException.class, scope::current);
            }).get(5, TimeUnit.SECONDS);
        }
        scope.end();
        assertThrows(SecurityException.class, scope::enterTool);
    }

    @Test void lateWorkerCannotBorrowNextRequestsObservation() throws Exception {
        var scope = new KnowledgeObservationScope();
        scope.begin(run("old"));
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(scope::enterTool).get(5, TimeUnit.SECONDS);
            scope.end();
            scope.begin(run("new"));
            worker.submit(() -> assertThrows(SecurityException.class, scope::current)).get(5, TimeUnit.SECONDS);
            worker.submit(scope::leaveTool).get(5, TimeUnit.SECONDS);
        } finally { scope.end(); }
    }

    @Test void simultaneousHostRequestsAreRejected() {
        var scope = new KnowledgeObservationScope();
        scope.begin(run("first"));
        assertThrows(IllegalStateException.class, () -> scope.begin(run("second")));
        scope.end();
    }
}
