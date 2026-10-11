package io.github.bitlhk.finance.harness.core;

import static org.junit.jupiter.api.Assertions.*;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ManagedScenarioExtensionTest {
    @Test void modelConnectionDoesNotRenderCredential() {
        var connection = new ManagedScenarioExtensionV01.ModelConnection(
                "FinanceSnapshotOpenAI", "private-key", "https://model.example/v1", "model");
        assertFalse(connection.toString().contains("private-key"));
        assertTrue(connection.toString().contains("[REDACTED]"));
    }

    @Test void legacyContextHasNoObservationOutsideAHostRequest() {
        var context = new ManagedScenarioExtensionV01.Context(Path.of("artifact"), Path.of("state"),
                "build", "instance", "owner", null);
        assertNull(context.currentObservation().get());
    }
}
