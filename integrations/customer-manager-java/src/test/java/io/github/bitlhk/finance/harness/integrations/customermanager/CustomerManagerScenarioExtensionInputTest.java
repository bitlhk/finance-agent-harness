package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class CustomerManagerScenarioExtensionInputTest {
    @Test
    void acceptsWorkbenchRunCorrelationWithoutRelaxingTheActionBody() throws Exception {
        assertDoesNotThrow(() -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"runId\":\"run-1\"}")));
        assertThrows(IllegalArgumentException.class, () -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"customerRef\":\"CUST-1\"}")));
    }
}
