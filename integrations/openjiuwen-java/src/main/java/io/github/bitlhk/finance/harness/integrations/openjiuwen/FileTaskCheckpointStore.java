package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Small single-host reference store. Production hosts may implement the same interface with their DB. */
public final class FileTaskCheckpointStore implements TaskCheckpointStore {
    // Only checkpoint decoding changes; existing Core JSON consumers retain their numeric types.
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = HarnessJson.mapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private final Path directory;

    public FileTaskCheckpointStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        this.directory = directory.toRealPath();
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
    }

    @Override
    public <T> T locked(Function<Access, T> action) {
        synchronized (LOCKS.computeIfAbsent(directory, ignored -> new Object())) {
            Path lockFile = directory.resolve(".lock");
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.lock()) {
                return action.apply(new Access() {
                    public SavedTask get(String handle) {
                        Path file = path(handle);
                        try {
                            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
                            if (Files.isSymbolicLink(file)) throw new IOException("Checkpoint symlink rejected");
                            return JSON.readValue(file.toFile(), SavedTask.class);
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }

                    public void put(String handle, SavedTask task) {
                        Path file = path(handle);
                        try {
                            Path temp = Files.createTempFile(directory, ".checkpoint-", ".tmp",
                                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                            try {
                                byte[] bytes = JSON.writeValueAsBytes(task);
                                try (FileChannel output = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                                    while (buffer.hasRemaining()) output.write(buffer);
                                    output.force(true);
                                }
                                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                                try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) {
                                    dir.force(true);
                                }
                            } finally {
                                Files.deleteIfExists(temp);
                            }
                        } catch (IOException exception) {
                            throw new UncheckedIOException(exception);
                        }
                    }
                });
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private Path path(String handle) {
        if (handle == null || !handle.matches("fh-task-handle-[a-f0-9-]{36}")) {
            throw new IllegalArgumentException("Invalid checkpoint handle");
        }
        return directory.resolve(handle + ".json");
    }
}
