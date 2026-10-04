package io.github.bitlhk.finance.harness.integrations.agentscope;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelConnectionTimeoutTest {
    @Test void acceptsExplicitBoundedSlowModelBudget() {
        for (long seconds : new long[]{1, 45, 60, 120}) {
            var connection = new ModelConnection(URI.create("https://model.example/v1"),
                    "fixture", "secret", Duration.ofSeconds(seconds));
            assertEquals(Duration.ofSeconds(seconds), connection.timeout());
        }
    }
    @Test void rejectsUnboundedOrNonPositiveBudget() {
        for (long seconds : new long[]{-1, 0, 121, Long.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ModelConnection(
                    URI.create("https://model.example/v1"), "fixture", "secret", Duration.ofSeconds(seconds)));
        }
    }
}
