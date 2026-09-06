package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeTimestampFormatTest {
    @Test void emitActualJavaInstantFormatsForPythonCompatibilityGate() throws Exception {
        var base = Instant.parse("2026-09-06T03:27:24Z");
        var cases = new ArrayList<Map<String, Object>>();
        for (long nanos : new long[]{0, 1, 10, 100, 1_000, 274_594_000, 604_952_000, 123_000_000, 123_456_789, 999_999_999}) {
            var timestamp = base.plusNanos(nanos);
            cases.add(Map.of("timestamp", timestamp, "nanoseconds", nanos));
            assertEquals(nanos, timestamp.getNano());
        }
        Path output = Path.of("target/java-instant-cases.json");
        Files.createDirectories(output.toAbsolutePath().getParent());
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), cases);
        assertEquals(10, HarnessJson.mapper().readTree(output.toFile()).size());
    }
}
