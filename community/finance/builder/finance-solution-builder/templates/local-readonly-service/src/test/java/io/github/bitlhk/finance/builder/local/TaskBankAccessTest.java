package io.github.bitlhk.finance.builder.local;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import java.nio.file.Files;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Real HTTP to an isolated identity fixture, not a real bank authorization claim. */
public class TaskBankAccessTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    HttpServer server;
    Path policy;
    String base;
    AtomicInteger logins = new AtomicInteger(), identities = new AtomicInteger(), stepUps = new AtomicInteger();
    volatile boolean strong;
    volatile String returnedCustomer = "customer-a";
    volatile CountDownLatch identityEntered, identityRelease;

    @Before public void prepare() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        policy = temporary.getRoot().toPath().resolve("policy.json");
        policy(true);
        server.createContext("/", e -> {
            Object value;
            switch (e.getRequestURI().getPath()) {
                case "/health" -> value = Map.of("service", "non-ai-banking", "providerMode", "simulator");
                case "/api/v1/sessions" -> {
                    logins.incrementAndGet();
                    value = response(Map.of("sessionId", "original-session", "customerRef", "customer-a",
                            "expiresAt", Instant.now().plusSeconds(3600).toString()));
                }
                case "/api/v1/sessions/current" -> {
                    identities.incrementAndGet();
                    if (identityEntered != null) {
                        identityEntered.countDown();
                        try { identityRelease.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                    value = response(Map.of("sessionId", "original-session", "customerRef", returnedCustomer,
                            "identityStrength", strong ? "I2" : "I1", "expiresAt", Instant.now().plusSeconds(3600).toString()));
                }
                case "/api/v1/sessions/step-up" -> {
                    stepUps.incrementAndGet(); strong = true; value = response(Map.of());
                }
                default -> throw new AssertionError("Unexpected source route");
            }
            byte[] bytes = HarnessJson.mapper().writeValueAsBytes(value);
            e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @After public void stop() { if (server != null) server.stop(0); }
    static Object response(Object value) { return Map.of("status", "SUCCESS", "data", value); }
    void policy(boolean enabled) throws Exception {
        policy(enabled, Set.of("transfer:create"));
    }
    void policy(boolean enabled, Set<String> scopes) throws Exception {
        HarnessJson.mapper().writeValue(policy.toFile(), Map.of("schemaVersion", "local-simulator-access.v0.1",
                "bankUrl", base, "subjects", Map.of("customer-a", Map.of("enabled", enabled,
                        "expiresAt", Instant.now().plusSeconds(3600).toString(), "scopes", scopes))));
    }

    @Test public void sharedTaskPolicyIsNarrowedToInstalledAgentScopes() throws Exception {
        policy(true, Set.of("account:read", "transfer:create", "channel:write"));
        var bank = bank(); var login = bank.open("session");
        var grant = bank.current(login);
        assertTrue(grant.scopes().contains("transfer:create"));
        assertTrue(grant.scopes().contains("account:read"));
        assertFalse(grant.scopes().contains("channel:write"));
    }
    BankAccess bank() {
        return new BankAccess(true, Set.of("transfer:create"), Map.of("FH_TASK_SIMULATOR_ENABLED", "true",
                "FH_BANK_URL", base, "FH_BANK_LOGIN", "test-login", "FH_BANK_SECRET", "test-secret",
                "FH_ACCESS_POLICY", policy.toString()));
    }

    @Test(timeout=10000) public void concurrentStatusAuthorizationDoesNotAppendToFinishedChat() throws Exception {
        var candidate = temporary.newFolder("candidate").toPath();
        Files.writeString(candidate.resolve("service-lock.json"), "{\"capabilities\":[]}");
        var archive = new ExecutionArchive(candidate, "build", "instance");
        var request = archive.begin(candidate, "session", "subject", 0);
        var bank = bank(); var login = bank.open("session");
        login.observation = request;
        int before = identities.get();
        identityEntered = new CountDownLatch(1); identityRelease = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var status = pool.submit(() -> bank.current(login, null));
            assertTrue(identityEntered.await(3, TimeUnit.SECONDS));
            request.finish(io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.COMPLETED);
            var frozen = Files.readAllBytes(request.file);
            identityRelease.countDown();
            assertNotNull(status.get(3, TimeUnit.SECONDS));
            assertEquals(before + 1, identities.get());
            assertArrayEquals(frozen, Files.readAllBytes(request.file));
            policy(false);
            assertThrows(SecurityException.class, () -> bank.current(login, null));
        } finally { identityRelease.countDown(); pool.shutdownNow(); }
        identityEntered = null;
        policy(true);
        var next = archive.begin(candidate, "session", "subject", 0);
        login.observation = next;
        bank.current(login);
        next.finish(io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.COMPLETED);
        assertTrue(Files.readString(next.file).contains("authorization-http"));
    }

    @Test public void reopenRevalidatesOriginalSessionWithoutLoggingInAgain() throws Exception {
        var root = temporary.getRoot().toPath().resolve("private-state");
        try (var scope = new HostTaskScope(root, "a".repeat(64), "agent-test")) { bank().open(scope); }
        int before = identities.get();
        try (var scope = new HostTaskScope(root, "a".repeat(64), "agent-test")) {
            var saved = bank().open(scope);
            assertEquals("original-session", saved.bankSession);
            assertEquals(1, logins.get()); assertTrue(identities.get() > before);
            returnedCustomer = "customer-b";
            assertThrows(SecurityException.class, () -> bank().open(scope));
            assertEquals(1, logins.get());
        }
    }

    @Test public void revokedPolicyDoesNotReauthenticateOrStepUp() throws Exception {
        try (var scope = new HostTaskScope(temporary.getRoot().toPath().resolve("private-state"), "a".repeat(64), "agent-test")) {
            var bank = bank(); var login = bank.open(scope);
            policy(false); int before = identities.get();
            assertThrows(SecurityException.class, () -> bank.open(scope));
            assertThrows(SecurityException.class, () -> bank.stepUp(login, "test-code"));
            assertEquals(before, identities.get()); assertEquals(1, logins.get()); assertEquals(0, stepUps.get());
        }
    }

    @Test public void approvalStrengthComesFromSourceIntrospection() throws Exception {
        try (var scope = new HostTaskScope(temporary.getRoot().toPath().resolve("private-state"), "a".repeat(64), "agent-test")) {
            var bank = bank(); var login = bank.open(scope);
            assertThrows(SecurityException.class, () -> bank.requireStrong(login));
            bank.stepUp(login, "test-code"); bank.requireStrong(login);
            assertEquals(1, stepUps.get()); assertEquals(1, logins.get());
        }
    }
}
