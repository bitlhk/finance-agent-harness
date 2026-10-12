package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Existing private scope/checkpoint file primitives, without any SDK dependency. */
final class HostPrivateFiles {
    static void directory(Path path) throws IOException {
        // macOS may expose the JVM's trusted temporary root through /var, a
        // system-owned symlink.  Still reject every caller-controlled path
        // component below that root.
        Path systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        for (Path p = path.toAbsolutePath().normalize(); p != null && !p.equals(systemTemp); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw new IOException("TASK_STATE_SYMLINK_REJECTED");
        Files.createDirectories(path);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    static void save(Path path, Object value) throws IOException {
        directory(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".task-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(HarnessJson.mapper().writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
