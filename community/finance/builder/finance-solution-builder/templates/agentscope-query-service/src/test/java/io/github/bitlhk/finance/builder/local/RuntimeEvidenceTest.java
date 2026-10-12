package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class RuntimeEvidenceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void restartKeepsPriorImmutableInstallationAndTurnFiles() throws Exception {
        Path root = temporary.getRoot().toPath();
        String session = UUID.randomUUID().toString();
        HarnessJson.mapper().writeValue(root.resolve("instance.json").toFile(), Map.of("instanceId", UUID.randomUUID().toString()));
        Path first = NativeConversation.runtimeEvidenceDirectory(root, session);
        Files.createDirectories(first);
        ExecutionArchive.writeImmutable(first.resolve("runtime-lock.json"), "{\"installation\":1}".getBytes(), 100);
        ExecutionArchive.writeImmutable(first.resolve("turn-1.json"), "{\"turn\":1}".getBytes(), 100);
        HarnessJson.mapper().writeValue(root.resolve("instance.json").toFile(), Map.of("instanceId", UUID.randomUUID().toString()));
        Path second = NativeConversation.runtimeEvidenceDirectory(root, session);
        Files.createDirectories(second);
        ExecutionArchive.writeImmutable(second.resolve("runtime-lock.json"), "{\"installation\":2}".getBytes(), 100);
        assertNotEquals(first, second);
        assertEquals("{\"installation\":1}", Files.readString(first.resolve("runtime-lock.json")));
        assertEquals("{\"turn\":1}", Files.readString(first.resolve("turn-1.json")));
    }
    @Test public void invalidInstanceCannotSelectAnEvidencePath() throws Exception {
        Path root = temporary.getRoot().toPath();
        HarnessJson.mapper().writeValue(root.resolve("instance.json").toFile(), Map.of("instanceId", "../outside"));
        assertThrows(IllegalArgumentException.class, () -> NativeConversation.runtimeEvidenceDirectory(root, UUID.randomUUID().toString()));
    }
}
