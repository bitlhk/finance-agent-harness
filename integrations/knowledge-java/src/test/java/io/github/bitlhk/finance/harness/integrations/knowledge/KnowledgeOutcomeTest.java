package io.github.bitlhk.finance.harness.integrations.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeOutcomeTest {
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
