package io.github.bitlhk.finance.harness.core;
import io.github.bitlhk.finance.harness.core.observation.ToolAttemptEvidenceV1;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ToolAttemptEvidenceTest {
    @Test void missingCancelledAndLateAreNeverComplete() {
        assertEquals("PARTIAL", new ToolAttemptEvidenceV1("r").finish(true).get("status"));
        var ledger = new ToolAttemptEvidenceV1("r"); ledger.responseObserved();
        assertEquals("PARTIAL", ledger.finish(false).get("status"));
        var late = new ToolAttemptEvidenceV1("r2"); late.responseObserved(); late.finish(true);
        late.attempt("call", "tool", "MODEL_SELECTED");
        assertEquals("PARTIAL", late.finish(true).get("status"));
    }
}
