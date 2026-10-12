package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.execution.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Private Host handles and approval events; business state stays in the existing Checkpoint/Provider. */
final class HostTaskState implements TaskCoordination.Journal {
    private static final ObjectMapper JSON = HarnessJson.mapper().copy()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private final FileTaskJournal store;
    private final Path root;
    private final Clock clock;
    private final Runnable access;
    private final Runnable approvalAccess;

    interface InstalledTask {
        String binding(String handle);
        Object confirm(String handle, TaskCoordination.Approval confirmation);
        Object resume(String handle) throws Exception;
        Object decline(String handle);
    }
    record Challenge(String handle, String nonce, String binding, Instant issuedAt, Instant expiresAt, boolean consumed) { }

    HostTaskState(Path privateRoot, String trustedOwnerScope, Clock clock, Runnable access, Runnable approvalAccess) throws IOException {
        this.root = privateRoot.resolve(identifier(trustedOwnerScope));
        this.clock = Objects.requireNonNull(clock);
        this.access = Objects.requireNonNull(access);
        this.approvalAccess = Objects.requireNonNull(approvalAccess);
        directory(root);
        directory(root.resolve("handles"));
        directory(root.resolve("confirmations"));
        store = new FileTaskJournal(root.resolve("checkpoints-v01"));
    }

    @Override public <T> T locked(Function<TaskCoordination.Access, T> action) {
        return store.locked(delegate -> action.apply(new TaskCoordination.Access() {
            public TaskCoordination.Saved get(String handle) { return delegate.get(handle); }
            public void put(String handle, TaskCoordination.Saved task) {
                // Write the index first; an interrupted first write is a visible absent checkpoint, never a new submission.
                Path index = root.resolve("handles").resolve(identifier(handle) + ".json");
                try {
                    if (!Files.exists(index)) {
                        try (var files = Files.list(root.resolve("handles"))) {
                            for (var prior : files.toList()) {
                                if (!prior.getFileName().toString().endsWith(".json")) continue;
                                String name = prior.getFileName().toString();
                                var previous = delegate.get(name.substring(0, name.length() - 5));
                                if (previous == null || previous.terminalResponse() == null)
                                    throw new TaskCoordination.NewTaskRejected();
                            }
                        }
                        save(index, Map.of("handle", handle));
                    }
                }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                delegate.put(handle, task);
            }
        }));
    }

    List<String> handles() throws IOException {
        access.run();
        try (var files = Files.list(root.resolve("handles"))) {
            return files.filter(p -> Files.isRegularFile(p) && !Files.isSymbolicLink(p))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("fh-task-handle-[a-f0-9-]{36}\\.json"))
                    .sorted().map(n -> n.substring(0, n.length() - 5)).toList();
        }
    }

    TaskCoordination.Saved task(String handle) {
        access.run(); identifier(handle);
        if (!Files.isRegularFile(root.resolve("handles").resolve(handle + ".json")))
            throw new SecurityException("TASK_NOT_AVAILABLE");
        var saved = locked(a -> a.get(handle));
        if (saved == null) throw new IllegalStateException("TASK_CHECKPOINT_INCOMPLETE");
        return saved;
    }

    // The owning Host serializes these operations with its existing native lock and single-process state lock.
    synchronized Map<String, Object> challenge(String handle, InstalledTask installed) throws IOException {
        approvalAccess.run();
        var saved = task(handle);
        String binding = installed.binding(handle);
        if (binding == null || !binding.matches("[a-f0-9]{64}")) throw new IllegalStateException("TASK_NOT_AWAITING_CONFIRMATION");
        Instant now = clock.instant();
        var challenge = new Challenge(handle, UUID.randomUUID().toString(), binding, now, now.plusSeconds(75), false);
        save(root.resolve("confirmations").resolve(challenge.nonce() + ".json"), challenge);
        return Map.of("handle", handle, "nonce", challenge.nonce(), "binding", binding,
                "parameters", saved.arguments(), "expiresAt", challenge.expiresAt());
    }

    synchronized Object confirm(String handle, String nonce, InstalledTask installed) throws Exception {
        approvalAccess.run();
        var saved = task(handle);
        Path file = root.resolve("confirmations").resolve(identifier(nonce) + ".json");
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) throw new SecurityException("CONFIRMATION_NOT_AVAILABLE");
        Challenge challenge = JSON.readValue(file.toFile(), Challenge.class);
        if (!handle.equals(challenge.handle()) || !nonce.equals(challenge.nonce()))
            throw new SecurityException("CONFIRMATION_BINDING_MISMATCH");
        if (saved.terminalResponse() != null || challenge.consumed()) return installed.resume(handle);
        if (!challenge.expiresAt().isAfter(clock.instant()) || !challenge.binding().equals(installed.binding(handle)))
            throw new SecurityException("CONFIRMATION_EXPIRED_OR_CHANGED");
        // Consume durably before invoking Installer; after a crash only resume the original task.
        save(file, new Challenge(handle, nonce, challenge.binding(), challenge.issuedAt(), challenge.expiresAt(), true));
        return installed.confirm(handle, new TaskCoordination.Approval(nonce, challenge.binding(), clock.instant(), challenge.expiresAt()));
    }

    synchronized Object decline(String handle, InstalledTask installed) {
        task(handle);
        return installed.decline(handle);
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,160}")) throw new IllegalArgumentException("INVALID_TASK_REFERENCE");
        return value;
    }
    static void directory(Path path) throws IOException {
        HostPrivateFiles.directory(path);
    }
    static void save(Path path, Object value) throws IOException {
        HostPrivateFiles.save(path, value);
    }
}
