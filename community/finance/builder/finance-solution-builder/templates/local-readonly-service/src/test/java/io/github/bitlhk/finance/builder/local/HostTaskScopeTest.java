package io.github.bitlhk.finance.builder.local;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.channels.OverlappingFileLockException;
import java.time.Instant;
import static org.junit.Assert.*;

public class HostTaskScopeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    public static void main(String[] args) throws Exception {
        try (var scope = new HostTaskScope(java.nio.file.Path.of(args[0]), "a".repeat(64), "agent-test")) {
            System.out.println("LOCKED:" + scope.sessionId);
            System.out.flush();
            System.in.read();
        }
    }

    @Test(timeout = 20000) public void separateProcessExcludesSecondHostAndReleasesAfterTermination() throws Exception {
        var path = temporary.getRoot().toPath();
        var child = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                HostTaskScopeTest.class.getName(), path.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String id;
        try {
            var line = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream())).readLine();
            assertNotNull(line);
            assertTrue(line.startsWith("LOCKED:"));
            id = line.substring("LOCKED:".length());
            var denied = assertThrows(java.io.IOException.class,
                    () -> new HostTaskScope(path, "a".repeat(64), "agent-test"));
            assertEquals("TASK_STATE_ALREADY_IN_USE", denied.getMessage());
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        try (var restored = new HostTaskScope(path, "a".repeat(64), "agent-test")) {
            assertEquals(id, restored.sessionId);
        }
    }

    @Test public void sourceSessionIsPrivateBoundAndNotSilentlyReplaced() throws Exception {
        var path = temporary.getRoot().toPath();
        var expiry = Instant.parse("2030-01-01T00:00:00Z");
        try (var scope = new HostTaskScope(path, "a".repeat(64), "agent-test")) {
            assertNull(scope.sourceSession("http://127.0.0.1:1234"));
            var login = new BankAccess.Login(scope.sessionId, "sensitive-test-session", "customer-a", expiry);
            scope.saveSourceSession(login, "http://127.0.0.1:1234");
            assertThrows(SecurityException.class, () -> scope.saveSourceSession(login, "http://127.0.0.1:1234"));
        }
        try (var scope = new HostTaskScope(path, "a".repeat(64), "agent-test")) {
            var saved = scope.sourceSession("http://127.0.0.1:1234");
            assertEquals("sensitive-test-session", saved.bankSession());
            assertEquals(expiry, saved.expires());
            assertFalse(saved.toString().contains("sensitive-test-session"));
            assertThrows(SecurityException.class, () -> scope.sourceSession("http://127.0.0.1:5678"));
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    java.nio.file.Files.getPosixFilePermissions(path.resolve("source-session.json")));
        }
    }

    @Test public void exclusiveRootAndStableIdentityAfterReopen() throws Exception {
        var path = temporary.getRoot().toPath();
        String id;
        try (var first = new HostTaskScope(path, "a".repeat(64), "agent-test")) {
            id = first.sessionId;
            first.bindIdentity("principal-a", "customer-a");
            assertThrows(OverlappingFileLockException.class, () -> new HostTaskScope(path, "a".repeat(64), "agent-test"));
        }
        try (var reopened = new HostTaskScope(path, "a".repeat(64), "agent-test")) {
            assertEquals(id, reopened.sessionId);
            reopened.bindIdentity("principal-a", "customer-a");
            assertThrows(SecurityException.class, () -> reopened.bindIdentity("principal-b", "customer-a"));
            assertThrows(SecurityException.class, () -> reopened.bindIdentity("principal-a", "customer-b"));
        }
        assertThrows(SecurityException.class, () -> new HostTaskScope(path, "b".repeat(64), "agent-test"));
        assertThrows(SecurityException.class, () -> new HostTaskScope(path, "a".repeat(64), "other-agent"));
        try (var reopened = new HostTaskScope(path, "a".repeat(64), "agent-test")) { assertEquals(id, reopened.sessionId); }
    }
}
