package io.github.bitlhk.finance.builder.transfer;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SimulatorAccessTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    HttpServer server;
    SimulatorAccess bank;
    SimulatorAccess.Session session;
    Path policy;
    Set<String> scopes = SimulatorAccess.SCOPES;
    boolean enabled = true;
    String actualCustomer = "customer-a", instance = "isolated-a", strength = "I2";
    AtomicInteger businessReads = new AtomicInteger(), mutations = new AtomicInteger();

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Object data;
            if (path.equals("/health")) data = Map.of("providerMode", "simulator", "service", "non-ai-banking");
            else if (path.equals("/api/v1/sessions/runtime")) data = Map.of("instanceId", instance);
            else if (path.equals("/api/v1/sessions/current")) data = envelope(Map.of("sessionId", "bank-a", "customerRef", actualCustomer,
                    "identityStrength", strength, "expiresAt", Instant.now().plusSeconds(300).toString()));
            else if (path.equals("/api/v1/accounts")) {
                businessReads.incrementAndGet();
                data = envelope(Map.of("accounts", List.of(Map.of("accountId", "account-a", "currency", "CNY", "status", "NORMAL"))));
            } else if (path.equals("/api/v1/beneficiaries")) {
                businessReads.incrementAndGet();
                data = envelope(List.of(Map.of("beneficiaryId", "beneficiary-a", "internal", true, "status", "ACTIVE")));
            } else { mutations.incrementAndGet(); data = Map.of("status", "FAILED"); }
            byte[] bytes = HostFiles.JSON.writeValueAsBytes(data);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        policy = temp.getRoot().toPath().resolve("policy.json");
        writePolicy(base);
        bank = new SimulatorAccess(base, policy, instance);
        session = new SimulatorAccess.Session("host-a", "csrf-a", "bank-a", "customer-a", Instant.now().plusSeconds(300));
    }
    @After public void stop() { if (server != null) server.stop(0); }
    static Object envelope(Object data) { return Map.of("status", "SUCCESS", "data", data); }
    void writePolicy(URI base) throws Exception {
        HostFiles.save(policy, Map.of("schemaVersion", "simulated-transfer-access.v0.1", "bankUrl", base.toString(),
                "simulatorInstance", "isolated-a", "maxAmountCny", "100.00", "subjects", Map.of("customer-a",
                        Map.of("enabled", enabled, "scopes", scopes, "expiresAt", Instant.now().plusSeconds(300)))));
    }
    Map<String, Object> parameters(String amount) {
        return Map.of("fromAccountId", "account-a", "beneficiaryId", "beneficiary-a", "amount", amount);
    }
    @Test public void validSavedInternalRecipientDoesNotMutateBank() {
        bank.validateTransfer(session, parameters("0.01"));
        assertEquals(2, businessReads.get()); assertEquals(0, mutations.get());
    }
    @Test public void actualIdentityMismatchBlocksBeforeBusinessRead() {
        actualCustomer = "customer-b";
        assertThrows(SecurityException.class, () -> bank.choices(session));
        assertEquals(0, businessReads.get());
    }
    @Test public void insufficientActualScopesCannotBorrowCapabilityRequirements() throws Exception {
        scopes = Set.of("account:read"); writePolicy(bank.base);
        assertThrows(SecurityException.class, () -> bank.choices(session));
        assertEquals(0, businessReads.get());
    }
    @Test public void revokedGrantIsNotCached() throws Exception {
        bank.current(session); enabled = false; writePolicy(bank.base);
        assertThrows(SecurityException.class, () -> bank.choices(session));
        assertEquals(0, businessReads.get());
    }
    @Test public void changedSimulatorCannotReuseExistingSession() {
        instance = "other-instance";
        assertThrows(SecurityException.class, () -> bank.choices(session));
        assertEquals(0, businessReads.get());
    }
    @Test public void strongAuthenticationIsNotAnApproval() throws Exception {
        strength = "I1";
        assertThrows(SecurityException.class, () -> bank.requireStrong(session));
        strength = "I2"; bank.requireStrong(session);
        assertEquals(0, businessReads.get()); assertEquals(0, mutations.get());
    }
    @Test public void invalidMoneyNeverReachesBusinessQuery() {
        for (String amount : List.of("0", "-0.01", "0.001", "100.01", "NaN", "90071992547409.91"))
            assertThrows(IllegalArgumentException.class, () -> bank.validateTransfer(session, parameters(amount)));
        assertEquals(0, businessReads.get()); assertEquals(0, mutations.get());
    }
    @Test public void foreignRecipientRejectedWithoutMutation() {
        assertThrows(IllegalArgumentException.class, () -> bank.validateTransfer(session,
                Map.of("fromAccountId", "account-a", "beneficiaryId", "foreign", "amount", "0.01")));
        assertEquals(0, mutations.get());
    }
    @Test public void metadataKeepsExactMoney() throws Exception {
        var file = temp.getRoot().toPath().resolve("metadata.json");
        var amount = new BigDecimal("90071992547409.91");
        HostFiles.save(file, Map.of("amount", amount));
        assertEquals(amount, HostFiles.JSON.readValue(file.toFile(), Map.class).get("amount"));
    }
    @Test public void browserDecimalStringBecomesExactToolNumberWithoutMutatingInput() {
        var input = parameters("0.01");
        var normalized = TransferRuntime.normalizedParameters(input);
        assertEquals(new BigDecimal("0.01"), normalized.get("amount"));
        assertEquals("0.01", input.get("amount"));
    }
}
