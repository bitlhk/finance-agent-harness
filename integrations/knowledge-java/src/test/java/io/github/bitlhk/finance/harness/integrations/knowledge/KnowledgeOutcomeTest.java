package io.github.bitlhk.finance.harness.integrations.knowledge;

import java.util.*;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler.SolutionLock;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeOutcomeTest {
    @Test void remountTimeDoesNotReplaceOriginalInstallationAndDriftIsDenied() {
        var original = new SolutionLock("finance-solution-lock.v1", "agent-a", "first", null,
            List.of(), List.of(), List.of(), null);
        var remount = new SolutionLock(original.schemaVersion(), "agent-a", "later", null,
            List.of(), List.of(), List.of(), null);
        assertDoesNotThrow(() -> KnowledgeScenario.requireSameInstallation(original, remount));
        assertEquals("first", original.resolvedAt());
        var other = new SolutionLock(original.schemaVersion(), "agent-b", "later", null,
            List.of(), List.of(), List.of(), null);
        assertThrows(SecurityException.class, () -> KnowledgeScenario.requireSameInstallation(original, other));
        var unknown = new SolutionLock("unknown", "agent-a", "later", null,
            List.of(), List.of(), List.of(), null);
        assertThrows(SecurityException.class, () -> KnowledgeScenario.requireSameInstallation(original, unknown));
    }
    private Map<String,Object> found() {
        return Map.of("data", Map.of("resultStatus", "COMPLETED", "result", Map.of(
            "decision", "EVIDENCE_FOUND", "evidence", List.of(Map.of("locator", "clause:1")))));
    }
    @Test void actualToolEvidenceControlsOutcomeNotModelText() {
        assertEquals("COMPLETED", KnowledgeScenario.toolOutcome(List.of(found())));
        assertEquals("PARTIAL", KnowledgeScenario.toolOutcome(List.of(Map.of("output", "Everything succeeded"))));
    }
    @Test void failedOrMixedToolsCannotReportCompleted() {
        var failed=Map.of("data", Map.of("resultStatus", "FAILED"));
        assertEquals("PARTIAL", KnowledgeScenario.toolOutcome(List.of(failed)));
        assertEquals("PARTIAL", KnowledgeScenario.toolOutcome(List.of(found(),failed)));
    }
    @Test void noEvidenceAndUnknownAreNotSuccessfulAnswers() {
        assertEquals("NO_EVIDENCE", KnowledgeScenario.toolOutcome(List.of()));
        assertEquals("NO_EVIDENCE", KnowledgeScenario.toolOutcome(List.of(Map.of("data", Map.of(
            "resultStatus", "COMPLETED", "result", Map.of("decision", "UNKNOWN", "evidence", List.of()))))));
    }
}
