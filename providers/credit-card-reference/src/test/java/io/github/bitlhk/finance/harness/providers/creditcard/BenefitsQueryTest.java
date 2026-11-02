package io.github.bitlhk.finance.harness.providers.creditcard;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BenefitsQueryTest {
    @Test void clarificationMustContainAnActualQuestion() {
        Map<String,Object> value = Map.of("status", "complete", "result", Map.of("dialogue",
            Map.of("action", "clarify", "question", "这次需要出行还是餐饮权益？")));
        assertTrue(BenefitsQuery.needsUser(value));
        assertDoesNotThrow(() -> BenefitsQuery.checkTerminal(value));
        assertThrows(IllegalStateException.class, () -> BenefitsQuery.checkTerminal(Map.of(
            "status", "complete", "result", Map.of("dialogue", Map.of("action", "clarify")))));
    }
    @Test void staleUnknownAndNonterminalResponsesCannotBecomeCompleted() {
        assertThrows(SecurityException.class, () -> BenefitsQuery.checkTerminal(Map.of("status", "complete", "stale", true)));
        for (String status : new String[]{"queued", "running", "unknown", ""})
            assertThrows(IllegalStateException.class, () -> BenefitsQuery.checkTerminal(Map.of("status", status)));
        for (String status : new String[]{"partial", "failed", "no-candidates", "unavailable", "cancelled"}) {
            assertDoesNotThrow(() -> BenefitsQuery.checkTerminal(Map.of("status", status)));
            assertFalse(BenefitsQuery.needsUser(Map.of("status", status)));
        }
    }
}
