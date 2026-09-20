package io.github.bitlhk.finance.harness.assembly;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;
/** Existing tree digest convention shared without changing lock bytes or TASK execution. */
public final class AssetDigests {
    private AssetDigests() {}
    public static void rejectSymlinks(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            if (paths.anyMatch(Files::isSymbolicLink)) throw new IllegalArgumentException("Asset symlinks are not supported");
        }
    }

    public static String sha256(Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256Tree(Path directory) throws IOException {
        rejectSymlinks(directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            for (Path part : directory.relativize(path)) {
                                if (Set.of("target", "build", "logs", ".git").contains(part.toString())) return false;
                            }
                            return true;
                        })
                        .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                        .toList()) {
                    digest.update(directory.relativize(path).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }


}
