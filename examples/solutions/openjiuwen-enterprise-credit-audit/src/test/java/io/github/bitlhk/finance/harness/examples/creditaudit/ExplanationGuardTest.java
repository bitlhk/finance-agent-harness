package io.github.bitlhk.finance.harness.examples.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    @Test void rejectsClaimsThatFrozenComparableDimensionsWereNotProvided() {
        CapabilityResultV1 result = new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                "request-a", "trace-a", CapabilityResultV1.Status.COMPLETED, "完成", Map.of(
                "verificationBasis", Map.of("mode", "CROSS_MATERIAL", "outcome", "COMPARABLE",
                        "dimensions", List.of(Map.of("name", "period", "label", "期间",
                                "outcome", "MATCH", "values", List.of())))),
                List.of(), List.of(), "synthetic", Instant.now(), null, null);

        assertDoesNotThrow(() -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "申请与合同的期间已经核对一致。", List.of(result)));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "期间字段未在结果中给出，故期间可比性无法确认。", List.of(result)));
        assertTrue(error.getMessage().startsWith("MODEL_EXPLANATION_CONTRADICTS_CONFIRMED_BASIS"));
    }
}
