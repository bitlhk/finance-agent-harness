package io.github.bitlhk.finance.harness.examples.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticityScopeTest {
    private static List<CapabilityResultV1> facts() throws Exception {
        try (var input=AuthenticityScopeTest.class.getResourceAsStream("/audit-authenticity-first-failure.json")) {
            var root=HarnessJson.mapper().readTree(input);
            return List.of(HarnessJson.mapper().treeToValue(root.path("deterministicResults").get(0),CapabilityResultV1.class),
                    HarnessJson.mapper().treeToValue(root.path("deterministicResults").get(1),CapabilityResultV1.class));
        }
    }

    @Test void frozenFullFirstResponsePassesWithoutChangingStoredFailure() throws Exception {
        try (var input=getClass().getResourceAsStream("/audit-authenticity-first-failure.json")) {
            var root=HarnessJson.mapper().readTree(input);
            String text=root.path("explanation").asText();
            assertEquals(2565,text.length());
            assertEquals("FAILED",root.path("explanationStatus").asText());
            assertEquals("IllegalArgumentException: MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT",root.path("explanationError").asText());
            assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
        }
    }

    @TestFactory Stream<DynamicTest> boundedScopeCases() {
        var allowed=List.of(
            "不构成核验失败或材料虚假的结论。",
            "不代表材料虚假。",
            "不意味着真实性验证失败或材料虚假。",
            "不等于材料虚假。",
            "不能据此认定材料虚假。",
            "尚未核验，不能说未通过真实性验证或材料虚假。",
            "不代表真实性验证失败，也不构成材料虚假结论。",
            "既不代表真实性验证失败，也不代表材料虚假。",
            "没有证据证明材料虚假。",
            "报告引用外部原话“材料虚假”，但当前尚未核验，不能采信该说法。",
            "不能认定材料虚假，但需另行核验。",
            "不构成真实性核验失败或真实性不合格。"
        );
        var denied=List.of(
            "材料虚假。", "真实性验证失败。", "材料未通过真实性验证。",
            "不构成审批结论，但材料虚假。",
            "不能认定材料虚假，然而材料虚假已获确认。",
            "不代表真实性验证失败，材料虚假。",
            "不代表材料虚假；材料虚假。",
            "不能不认定材料虚假。",
            "并非不代表材料虚假。",
            "不是不构成材料虚假。",
            "不得不说材料虚假。",
            "不代表材料有效，实际已经确认材料虚假。",
            "不能认为没有材料虚假。",
            "不构成任何审批意见，因此材料虚假。",
            "有人声称“材料虚假”。",
            "有人声称“材料虚假”，但没有说不能采信该说法。",
            "不能说未通过真实性验证，且可以认定材料虚假。"
        );
        return Stream.concat(allowed.stream().map(text->DynamicTest.dynamicTest("allow: "+text,()->
                assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts())))),
            denied.stream().map(text->DynamicTest.dynamicTest("deny: "+text,()-> {
                var error=assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
                assertEquals("MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT",error.getMessage());
            })));
    }
}
