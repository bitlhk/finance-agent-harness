package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Public Python SDK API probe: no reflection, clock patches, deployment or source changes. */
@EnabledIfSystemProperty(named = "curated.reference.python", matches = ".+")
class ReferenceCleanupCompensationTest {
    @Test void installedDependencyHasIdleCleanupButNotAnAbsoluteSessionLifetime() throws Exception {
        Path report = Path.of("target/cleanup-ttl-probe.json");
        Path log = Path.of("target/cleanup-ttl-probe.log");
        var process = new ProcessBuilder(System.getProperty("curated.reference.python"),
                "src/test/resources/cleanup_ttl_probe.py")
                .redirectOutput(report.toFile()).redirectError(log.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "TTL probe must terminate within its process deadline");
            assertEquals(0, process.exitValue(), () -> {
                try { return Files.readString(log); } catch (Exception error) { return error.toString(); }
            });
            var evidence = HarnessJson.mapper().readTree(report.toFile());
            assertEquals("BLOCK", evidence.path("qualification").asText());
            assertEquals(1800, evidence.path("defaultIdleTimeoutSeconds").asInt());
            assertFalse(evidence.path("defaultTtlWallClockTested").asBoolean());
            assertEquals(0.25, evidence.path("probeIdleTimeoutSeconds").asDouble());
            assertEquals(404, evidence.path("oversizedInitializeAfterIdleStatus").asInt());
            assertEquals(202, evidence.path("otherSessionWhileKeptAliveStatus").asInt());
            assertEquals(202, evidence.path("initializeInFlightBeyondIdleStatus").asInt());
            assertEquals(404, evidence.path("initializeAfterReleasedIdleStatus").asInt());
            assertEquals(0, evidence.path("deleteRequests").asInt());
            assertTrue(evidence.path("provenance").path("mcp").path("sha256").size() >= 2);
        } finally {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }
}
