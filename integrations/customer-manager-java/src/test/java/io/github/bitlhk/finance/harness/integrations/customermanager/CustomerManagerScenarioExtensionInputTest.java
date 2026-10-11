package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CustomerManagerScenarioExtensionInputTest {
    @Test
    void acceptsWorkbenchRunCorrelationWithoutRelaxingTheActionBody() throws Exception {
        assertDoesNotThrow(() -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"runId\":\"run-1\"}")));
        assertThrows(IllegalArgumentException.class, () -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"customerRef\":\"CUST-1\"}")));
    }

    @Test void syntheticMarkerIsNotExposedInCustomerFacingNames() {
        assertEquals("周明远", CustomerManagerScenarioExtension.displayName("合成客户周明远"));
        assertEquals("周明远、沈嘉禾", CustomerManagerScenarioExtension.displayNames(
                java.util.List.of("合成客户周明远", "合成客户沈嘉禾")));
        assertEquals("周明远", CustomerManagerScenarioExtension.displayName("周明远"));
    }

    @Test void extractsOnlyExplicitProductReviewParameters() {
        var values=CustomerManagerScenarioExtension.productArguments("为周明远筛选10万元、12个月、手机银行可办理的产品候选");
        assertEquals(100000.0,values.get("amount"));
        assertEquals(12,values.get("horizonMonths"));
        assertEquals("手机银行",values.get("channel"));
        assertTrue(CustomerManagerScenarioExtension.productArguments("推荐一个产品").isEmpty());
    }

    @Test void pendingProductFlowContinuesForParametersButYieldsToExplicitPreparation() {
        assertTrue(CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PREPARE,true,"手机银行"));
        assertTrue(CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PRODUCT_REVIEW,false,"为周明远筛选产品"));
        assertEquals(false,CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PREPARE,true,"先不推荐产品，帮我准备周明远的回访"));
        assertEquals(false,CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.CANCEL_PRODUCT,true,"取消产品筛选"));
    }
}
