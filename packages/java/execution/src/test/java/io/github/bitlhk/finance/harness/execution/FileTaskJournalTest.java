package io.github.bitlhk.finance.harness.execution;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileTaskJournalTest {
    @TempDir Path root;
    private static final String HANDLE = "fh-task-handle-00000000-0000-0000-0000-000000000001";
    private TaskCoordination.Saved task() {
        return new TaskCoordination.Saved("scope", "synthetic.task", "task", Map.of("amount", new java.math.BigDecimal("12.34")),
                Map.of("financeHarness.taskRef", "synthetic-task", "financeHarness.continuation", Map.of("submitOutcomeUnknown", true)), null);
    }
    @Test void newJournalRestoresCommittedStateWithoutRewriting() throws Exception {
        var store = new FileTaskJournal(root);
        store.locked(a -> { a.put(HANDLE, task()); return null; });
        byte[] before = Files.readAllBytes(root.resolve(HANDLE + ".json"));
        assertEquals(task(), new FileTaskJournal(root).locked(a -> a.get(HANDLE)));
        assertArrayEquals(before, Files.readAllBytes(root.resolve(HANDLE + ".json")));
    }
    @Test void invalidPathsAndLinksAreRejected() throws Exception {
        var store = new FileTaskJournal(root);
        assertThrows(IllegalArgumentException.class, () -> store.locked(a -> a.get("../other")));
        Path other = Files.createTempFile(root, "outside", ".json");
        Files.createSymbolicLink(root.resolve(HANDLE + ".json"), other);
        assertThrows(UncheckedIOException.class, () -> store.locked(a -> a.get(HANDLE)));
        assertThrows(UncheckedIOException.class, () -> store.locked(a -> { a.put(HANDLE, task()); return null; }));
        Files.createSymbolicLink(root.resolve("alias"), root);
        assertThrows(IOException.class, () -> new FileTaskJournal(root.resolve("alias/child")));
    }
    @Test void unsupportedOrLegacyEncodingIsNotSilentlyConsumed() throws Exception {
        Files.writeString(root.resolve(HANDLE + ".json"), "{\"schemaVersion\":\"future\",\"task\":null}");
        assertThrows(UncheckedIOException.class, () -> new FileTaskJournal(root).locked(a -> a.get(HANDLE)));
    }
    @Test void failedPutDoesNotReportSuccess() throws Exception {
        var store = new FileTaskJournal(root);
        Files.createDirectory(root.resolve(HANDLE + ".json"));
        assertThrows(UncheckedIOException.class, () -> store.locked(a -> { a.put(HANDLE, task()); return null; }));
    }
}
