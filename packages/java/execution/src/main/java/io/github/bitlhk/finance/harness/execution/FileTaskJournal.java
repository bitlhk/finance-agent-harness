package io.github.bitlhk.finance.harness.execution;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Single-host durable Journal reference. Hosts may supply a transactional database instead. */
public final class FileTaskJournal implements TaskCoordination.Journal {
    private static final String VERSION = "finance-task-journal.v0.1";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = HarnessJson.mapper().copy()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;
    private record Entry(String schemaVersion, TaskCoordination.Saved task) { }
    public FileTaskJournal(Path directory) throws IOException {
        Path absolute = directory.toAbsolutePath().normalize();
        for (Path part = absolute; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Task journal symlink rejected");
        Files.createDirectories(absolute);
        this.directory = absolute.toRealPath();
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
    }
    public <T> T locked(Function<TaskCoordination.Access, T> operation) {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            try (var channel = FileChannel.open(directory.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); var lock = channel.lock()) {
                return operation.apply(new TaskCoordination.Access() {
                    public TaskCoordination.Saved get(String handle) {
                        Path file = path(handle);
                        try {
                            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
                            if (Files.isSymbolicLink(file)) throw new IOException("Task journal symlink rejected");
                            var entry = JSON.readValue(file.toFile(), Entry.class);
                            if (!VERSION.equals(entry.schemaVersion()) || entry.task() == null)
                                throw new IOException("Task journal version not supported");
                            return entry.task();
                        } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    }
                    public void put(String handle, TaskCoordination.Saved value) {
                        Path target = path(handle);
                        try {
                            if (Files.isSymbolicLink(target)) throw new IOException("Task journal symlink rejected");
                            Path temp = Files.createTempFile(directory, ".task-", ".tmp", PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
                            try {
                                try (var output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                                    var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(new Entry(VERSION, value)));
                                    while (bytes.hasRemaining()) output.write(bytes);
                                    output.force(true);
                                }
                                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                try (var dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
                            } finally { Files.deleteIfExists(temp); }
                        } catch (IOException failure) { throw new UncheckedIOException(failure); }
                    }
                });
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }
    private Path path(String handle) {
        if (handle == null || !handle.matches("fh-task-handle-[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid task handle");
        return directory.resolve(handle + ".json");
    }
}
