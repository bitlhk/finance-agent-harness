package io.github.bitlhk.finance.harness.examples.wealth;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable installed method content verified against the Builder Lock. */
public record InstalledPreparationMethod(String id, String version, String digest,
        String lockDigest, String content) {
    public InstalledPreparationMethod {
        if (blank(id) || blank(version) || blank(digest) || blank(lockDigest) || blank(content))
            throw new IllegalArgumentException("INSTALLED_METHOD_INVALID");
    }
    public static InstalledPreparationMethod load(String id, String version, Path file,
            String expectedDigest, String lockDigest) throws Exception {
        Path resolved = file.toAbsolutePath();
        if (!Files.isRegularFile(resolved) || Files.isSymbolicLink(resolved))
            throw new SecurityException("INSTALLED_METHOD_FILE_REJECTED");
        byte[] bytes = Files.readAllBytes(resolved);
        if (bytes.length == 0 || bytes.length > 256 * 1024) throw new IllegalArgumentException("INSTALLED_METHOD_SIZE_INVALID");
        String actual = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!actual.equals(expectedDigest)) throw new SecurityException("INSTALLED_METHOD_DIGEST_MISMATCH");
        return new InstalledPreparationMethod(id, version, actual, lockDigest,
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
