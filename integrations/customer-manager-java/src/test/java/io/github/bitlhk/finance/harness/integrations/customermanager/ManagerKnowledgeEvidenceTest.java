package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagerKnowledgeEvidenceTest {
    @Test void originalValidatedTextIsProjectedWithoutRegeneration() throws Exception {
        var report = HarnessJson.mapper().readTree("""
            {"customerAlias":"Synthetic customer", "explanationStatus":"READY",
             "modelSuggestion":{"communicationFocus":[{"text":"Original customer facts"}],
               "preparationAdvice":[{"text":"Original product and policy evidence"}],
               "pendingItems":["Not independently verified"],"reviewChecklist":["Human review"]}}
            """);
        String answer = ManagerKnowledgeEvidence.answer(report);
        assertTrue(answer.contains("Original customer facts"));
        assertTrue(answer.contains("Original product and policy evidence"));
        assertTrue(answer.contains("Not independently verified"));
        assertTrue(answer.contains("Human review"));
    }

    @Test void rejectedExplanationIsNotPresentedAsValidatedAdvice() throws Exception {
        var report = HarnessJson.mapper().readTree("""
            {"customerAlias":"Synthetic customer", "explanationStatus":"REJECTED",
             "modelSuggestion":{"preparationAdvice":[{"text":"UNTRUSTED_MODEL_TEXT"}]}}
            """);
        assertFalse(ManagerKnowledgeEvidence.answer(report).contains("UNTRUSTED_MODEL_TEXT"));
    }
}
