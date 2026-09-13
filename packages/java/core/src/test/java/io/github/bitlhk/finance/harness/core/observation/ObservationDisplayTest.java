package io.github.bitlhk.finance.harness.core.observation;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.*;

class ObservationDisplayTest {
    @Test void sharedSchemaCasesMatchJava() throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.isDirectory(root.resolve("contracts/fixtures/observation"))) root = root.getParent();
        var mapper = io.github.bitlhk.finance.harness.core.HarnessJson.mapper();
        for (var item : mapper.readTree(root.resolve("contracts/fixtures/observation/display-cases.json").toFile())) {
            boolean valid;
            try { mapper.treeToValue(item.get("value"), ObservationDisplayV01.class); valid = true; }
            catch (Exception invalid) { valid = false; }
            assertEquals(item.path("valid").asBoolean(), valid, item.path("name").asText());
        }
    }
    @Test void snapshotDoesNotFollowLaterListChanges() {
        var operations = new ArrayList<>(List.of(new OperationLabel("balance", "GET", "/accounts", "Original", "Source facts")));
        var source = new SourceLabel("reference", "0.1", operations);
        operations.clear();
        assertEquals("Original", source.operations().getFirst().title());
        assertThrows(UnsupportedOperationException.class, () -> source.operations().clear());
    }
    @Test void ambiguousRouteAndUnboundedTextReject() {
        var operation = new OperationLabel("balance", "GET", "/accounts", "Original", "Source facts");
        assertThrows(IllegalArgumentException.class, () -> new SourceLabel("reference", "0.1", List.of(operation, operation)));
        assertThrows(IllegalArgumentException.class, () -> new OperationLabel("balance", "GET", "/accounts?id=private", "Name", "Source"));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityLabel("balance", "pack", "0.1", "x".repeat(501)));
    }
}
