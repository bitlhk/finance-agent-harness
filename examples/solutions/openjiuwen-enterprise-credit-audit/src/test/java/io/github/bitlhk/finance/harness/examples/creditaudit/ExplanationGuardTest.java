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

    @Test void distinguishesAuthenticityNotVerifiedFromExplicitFailure() {
        CapabilityResultV1 pending=resultWithAuthenticity("NOT_VERIFIED",false);
        assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "本能力尚未核验材料真实性，需人工补充核验。",List.of(pending)));
        assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "材料未通过真实性验证。",List.of(pending)));
        assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "因此材料虚假。",List.of(pending)));
        assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "尚未核验，不能说未通过真实性验证或材料虚假。",List.of(pending)));
        assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "报告引用外部原话“材料虚假”，但当前尚未核验，不能采信该说法。",List.of(pending)));
        assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "尚未核验，但材料未通过真实性验证。",List.of(pending)));
        assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "外部人员声称“材料虚假”。",List.of(pending)));
        CapabilityResultV1 failed=resultWithAuthenticity("FAILED",false);
        assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "真实性核验失败，需人工查看明确失败依据。",List.of(failed)));
        assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                "真实性核验失败，需人工处理。",List.of(failed,pending)));
    }

    private static CapabilityResultV1 resultWithAuthenticity(String status,boolean verified) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,"request-auth","trace-auth",
                CapabilityResultV1.Status.COMPLETED,"完成",Map.of("authenticityStatus",status,"authenticityVerified",verified),
                List.of(),List.of(),"synthetic",Instant.now(),null,null);
    }
}
