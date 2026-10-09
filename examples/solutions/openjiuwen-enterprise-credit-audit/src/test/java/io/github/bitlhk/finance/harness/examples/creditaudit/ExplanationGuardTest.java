package io.github.bitlhk.finance.harness.examples.creditaudit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationGuardTest {
    @Test void permitsReviewDraftAndRejectsAuthoritativeDecision() {
        assertDoesNotThrow(() -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "勾稽差额为100元，需核对来源字段并人工复核。"));
        assertThrows(IllegalArgumentException.class,
                () -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed("建议批准授信。"));
        assertThrows(IllegalArgumentException.class,
                () -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(""));
        assertThrows(IllegalArgumentException.class,
                () -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed("{output=, result_type=answer}"));
    }
}
