package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
