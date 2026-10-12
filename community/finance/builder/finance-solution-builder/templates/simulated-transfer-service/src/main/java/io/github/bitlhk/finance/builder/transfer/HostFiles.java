package io.github.bitlhk.finance.builder.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Small private Host metadata, not a second business task store. */
final class HostFiles {
    static final ObjectMapper JSON = HarnessJson.mapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        if (Files.isSymbolicLink(path)) throw new IOException("STATE_SYMLINK_REJECTED");
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }
    static void save(Path path, Object value) throws IOException {
        directory(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".host-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(JSON.writeValueAsBytes(value));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (var channel = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temp); }
    }
    static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    static String env(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CONFIGURATION_MISSING: " + key);
        return value;
    }
    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,160}")) throw new IllegalArgumentException("INVALID_IDENTIFIER");
        return value;
    }
}
