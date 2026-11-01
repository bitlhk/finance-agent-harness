package io.github.bitlhk.finance.harness.providers.creditcard;

import java.util.Map;

/** Recommendation text is presentation; structured state controls the public status. */
public final class BenefitsQuery {
    private BenefitsQuery() { }
    public static boolean needsUser(Map<String,Object> result) {
        if (result.get("manager_preparation") instanceof Map<?,?> preparation
                && "needs-input".equals(preparation.get("status"))) return true;
        if (!(result.get("result") instanceof Map<?,?> payload)
                || !(payload.get("dialogue") instanceof Map<?,?> dialogue)) return false;
        return "clarify".equals(dialogue.get("action"));
    }
    public static void checkTerminal(Map<String,Object> result) {
        String status = String.valueOf(result.get("status"));
        if (!java.util.Set.of("complete", "partial", "failed", "cancelled", "no-candidates", "unavailable").contains(status))
            throw new IllegalStateException("CREDIT_RESULT_STATE_INVALID");
        if (Boolean.TRUE.equals(result.get("stale")))
            throw new SecurityException("CREDIT_RESULT_STALE");
        if (result.get("manager_preparation") instanceof Map<?,?> preparation
                && "needs-input".equals(preparation.get("status"))) {
            if (!(preparation.get("plan") instanceof Map<?,?> plan)
                    || !(plan.get("questions") instanceof java.util.List<?> questions)
                    || questions.isEmpty() || questions.stream().anyMatch(q -> !(q instanceof String s) || s.isBlank()))
                throw new IllegalStateException("CREDIT_CLARIFICATION_INVALID");
            return;
        }
        if (needsUser(result)) {
            Map<?,?> dialogue = (Map<?,?>)((Map<?,?>)result.get("result")).get("dialogue");
            if (!(dialogue.get("question") instanceof String question) || question.isBlank())
                throw new IllegalStateException("CREDIT_CLARIFICATION_INVALID");
        }
    }
}
