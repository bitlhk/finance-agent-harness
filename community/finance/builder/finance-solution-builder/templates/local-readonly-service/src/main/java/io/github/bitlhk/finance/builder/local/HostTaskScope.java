package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;

/** One private task scope per trusted owner/build; survives Host restart, never supplied by a model. */
final class HostTaskScope implements AutoCloseable {
    final Path root;
    final String sessionId;
    private final FileChannel channel;
    private final FileLock lock;
    record Identity(String principal, String subject) { }
    record SourceSession(String hostSession, String bankSession, String customer, Instant expires, String bankUrl) {
        @Override public String toString() { return "SourceSession[REDACTED]"; }
    }

    HostTaskScope(Path root, String trustedOwner, String buildId) throws IOException {
        if (trustedOwner == null || !trustedOwner.matches("[a-f0-9]{64}")
                || buildId == null || !buildId.matches("[A-Za-z0-9_-]{1,160}"))
            throw new IllegalArgumentException("TRUSTED_TASK_SCOPE_REQUIRED");
        this.root = root.toAbsolutePath().normalize();
        HostPrivateFiles.directory(this.root);
        Path lockPath = this.root.resolve("host.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("TASK_STATE_SYMLINK_REJECTED");
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("TASK_STATE_ALREADY_IN_USE");
        } catch (IOException | RuntimeException failure) { channel.close(); throw failure; }
        lock = acquired;
        sessionId = UUID.nameUUIDFromBytes((trustedOwner + ":" + buildId).getBytes(StandardCharsets.UTF_8)).toString();
        try {
            Path binding = this.root.resolve("scope.json");
            var expected = Map.of("owner", trustedOwner, "buildId", buildId, "sessionId", sessionId);
            if (Files.exists(binding)) {
                if (Files.isSymbolicLink(binding)
                        || !HarnessJson.mapper().readTree(binding.toFile()).equals(HarnessJson.mapper().valueToTree(expected)))
                    throw new SecurityException("TASK_SCOPE_MISMATCH");
            } else HostPrivateFiles.save(binding, expected);
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    synchronized void bindIdentity(String verifiedPrincipal, String verifiedSubject) throws IOException {
        if (verifiedPrincipal == null || verifiedPrincipal.isBlank() || verifiedSubject == null || verifiedSubject.isBlank())
            throw new SecurityException("VERIFIED_TASK_IDENTITY_REQUIRED");
        var expected = new Identity(verifiedPrincipal, verifiedSubject);
        Path path = root.resolve("identity.json");
        if (Files.exists(path)) {
            if (Files.isSymbolicLink(path) || !expected.equals(HarnessJson.mapper().readValue(path.toFile(), Identity.class)))
                throw new SecurityException("TASK_IDENTITY_CHANGED");
        } else HostPrivateFiles.save(path, expected);
    }

    synchronized SourceSession sourceSession(String bankUrl) throws IOException {
        Path path = root.resolve("source-session.json");
        if (!Files.exists(path)) return null;
        if (Files.isSymbolicLink(path)) throw new SecurityException("TASK_STATE_SYMLINK_REJECTED");
        var value = HarnessJson.mapper().readValue(path.toFile(), SourceSession.class);
        if (!sessionId.equals(value.hostSession()) || !bankUrl.equals(value.bankUrl())
                || value.bankSession() == null || value.bankSession().isBlank()
                || value.customer() == null || value.customer().isBlank() || value.expires() == null)
            throw new SecurityException("TASK_SOURCE_SESSION_MISMATCH");
        return value;
    }

    synchronized void saveSourceSession(BankAccess.Login login, String bankUrl) throws IOException {
        if (!sessionId.equals(login.hostSession)) throw new SecurityException("TASK_SOURCE_SESSION_MISMATCH");
        Path path = root.resolve("source-session.json");
        if (Files.exists(path)) throw new SecurityException("TASK_SOURCE_SESSION_ALREADY_BOUND");
        HostPrivateFiles.save(path, new SourceSession(login.hostSession, login.bankSession, login.customer, login.expires, bankUrl));
    }

    @Override public void close() throws IOException {
        try { if (lock.isValid()) lock.release(); }
        finally { channel.close(); }
    }
}
