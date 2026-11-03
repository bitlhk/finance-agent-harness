package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import java.util.Objects;

/** SDK tool callbacks bind the explicit Host request on the actual worker thread. */
final class KnowledgeObservationScope {
    private record Turn(ObservationRun run) {}
    private volatile Turn active;
    private final ThreadLocal<Turn> worker = new ThreadLocal<>();

    synchronized void begin(ObservationRun run) {
        if (active != null) throw new IllegalStateException("KNOWLEDGE_REQUEST_ALREADY_ACTIVE");
        active = new Turn(Objects.requireNonNull(run));
    }
    void enterTool() {
        Turn turn = active;
        if (turn == null) throw new SecurityException("KNOWLEDGE_REQUEST_NOT_ACTIVE");
        worker.set(turn);
    }
    ObservationRun current() {
        Turn turn = worker.get();
        if (turn == null || turn != active) throw new SecurityException("KNOWLEDGE_REQUEST_NOT_ACTIVE");
        return turn.run();
    }
    void leaveTool() { worker.remove(); }
    synchronized void end() { active = null; worker.remove(); }
}
