package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.execution.TaskCoordination;
import org.junit.Test;
import org.junit.Rule;
import org.junit.Before;
import org.junit.rules.TemporaryFolder;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class HostTaskStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    Path root;
    @Before public void prepare() { root = temporary.getRoot().toPath(); }
    static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    static final String HANDLE = "fh-task-handle-11111111-1111-1111-1111-111111111111";
    HostTaskState state(String owner, Clock clock, Runnable access) throws Exception {
        return new HostTaskState(root, owner, clock, access, access);
    }
    void save(HostTaskState state) {
        state.locked(a -> {
            a.put(HANDLE, new TaskCoordination.Saved("scope", "example.update", "task-a",
                    Map.of("amount", new BigDecimal("90071992547409.91")), Map.of("taskRef", "bank-original"), null));
            return null;
        });
    }
    static class Backend implements HostTaskState.InstalledTask {
        String binding = "a".repeat(64);
        int confirms, resumes, declines;
        boolean fail;
        public String binding(String handle) { return binding; }
        public Object confirm(String handle, TaskCoordination.Approval confirmation) {
            confirms++;
            if (fail) throw new IllegalStateException("response lost");
            return "PROCESSING";
        }
        public Object resume(String handle) { resumes++; return "ORIGINAL_TASK"; }
        public Object decline(String handle) { declines++; return "DECLINED"; }
    }

    public static void main(String[] args) throws Exception {
        var state = new HostTaskState(Path.of(args[0]), "owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { }, () -> { });
        state.confirm(HANDLE, args[1], new Backend() {
            @Override public Object confirm(String handle, TaskCoordination.Approval confirmation) {
                Runtime.getRuntime().halt(71); // Test process only: durable nonce consumed, Installer not invoked.
                return null;
            }
        });
    }

    @Test(timeout = 20000) public void processDeathBeforeInstallerDoesNotReuseApproval() throws Exception {
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });save(state);
        var backend = new Backend();
        String nonce = (String) state.challenge(HANDLE, backend).get("nonce");
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                HostTaskStateTest.class.getName(), root.toString(), nonce).inheritIO().start();
        try {
            assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(71, child.exitValue());
        } finally { if (child.isAlive()) child.destroyForcibly().waitFor(); }
        var restored = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
        assertEquals("ORIGINAL_TASK", restored.confirm(HANDLE, nonce, backend));
        assertEquals(0, backend.confirms);assertEquals(1, backend.resumes);
        String fresh = (String) restored.challenge(HANDLE, backend).get("nonce");
        assertEquals("PROCESSING", restored.confirm(HANDLE, fresh, backend));
        assertEquals(1, backend.confirms);
    }

    @Test public void pendingCheckpointPreventsNewTaskBeforeAnyProviderCall() throws Exception {
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });save(state);
        var previous = state.task(HANDLE);
        var error = assertThrows(IllegalStateException.class, () -> state.locked(a -> {
            a.put("fh-task-handle-22222222-2222-2222-2222-222222222222", previous);return null;
        }));
        assertEquals("TASK_PENDING_USE_ORIGINAL", error.getMessage());
        assertEquals(List.of(HANDLE), state.handles());
    }

    @Test public void restartPreservesPrecisionAndOwnerIndex() throws Exception {
        var first = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
        save(first);
        var restored = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
        assertEquals(List.of(HANDLE), restored.handles());
        assertEquals(new BigDecimal("90071992547409.91"), restored.task(HANDLE).arguments().get("amount"));
        var other = state("owner-b", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
        assertEquals(List.of(), other.handles());
        assertThrows(SecurityException.class, () -> other.task(HANDLE));
    }

    @Test public void consumedApprovalIsNeverReissuedAfterFailureOrRestart() throws Exception {
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { });
        save(state);
        var backend = new Backend(); backend.fail = true;
        var challenge = state.challenge(HANDLE, backend);
        String nonce = (String) challenge.get("nonce");
        assertThrows(IllegalStateException.class, () -> state.confirm(HANDLE, nonce, backend));
        var restored = state("owner-a", Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC), () -> { });
        assertEquals("ORIGINAL_TASK", restored.confirm(HANDLE, nonce, backend));
        assertEquals(1, backend.confirms); assertEquals(1, backend.resumes);
        assertEquals("bank-original", restored.task(HANDLE).checkpoint().get("taskRef"));
    }

    @Test public void expiryAndChangedBindingDoNotConfirm() throws Exception {
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { }); save(state);
        var backend = new Backend();
        String nonce = (String) state.challenge(HANDLE, backend).get("nonce");
        backend.binding = "b".repeat(64);
        assertThrows(SecurityException.class, () -> state.confirm(HANDLE, nonce, backend));
        backend.binding = "a".repeat(64);
        var expired = state("owner-a", Clock.fixed(NOW.plusSeconds(75), ZoneOffset.UTC), () -> { });
        assertThrows(SecurityException.class, () -> expired.confirm(HANDLE, nonce, backend));
        assertEquals(0, backend.confirms); assertEquals(0, backend.resumes);
    }

    @Test public void revokedAuthorityBlocksSavedApprovalAndFacts() throws Exception {
        var allowed = new AtomicBoolean(true);
        Runnable access = () -> { if (!allowed.get()) throw new SecurityException("revoked"); };
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), access); save(state);
        var backend = new Backend();
        String nonce = (String) state.challenge(HANDLE, backend).get("nonce");
        allowed.set(false);
        assertThrows(SecurityException.class, () -> state.confirm(HANDLE, nonce, backend));
        assertThrows(SecurityException.class, () -> state.task(HANDLE));
        assertThrows(SecurityException.class, state::handles);
        assertEquals(0, backend.confirms); assertEquals(0, backend.resumes);
    }

    @Test public void declineDoesNotConfirmAndUnknownNonceIsRejected() throws Exception {
        var state = state("owner-a", Clock.fixed(NOW, ZoneOffset.UTC), () -> { }); save(state);
        var backend = new Backend();
        assertEquals("DECLINED", state.decline(HANDLE, backend));
        assertThrows(SecurityException.class, () -> state.confirm(HANDLE, "missing", backend));
        assertEquals(1, backend.declines); assertEquals(0, backend.confirms);
    }
}
