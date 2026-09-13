package io.github.bitlhk.finance.harness.examples.retailbanking;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Native installed tool -> durable controller -> real Adapter -> isolated HTTP bank.
 * Host approval events are fixtures, not proof of an authenticated browser or bank IAM. */
class TransferHostRecoveryTest {
    static final String CAP = NonAiBankingProvider.TRANSFER_INTERNAL_CREATE;
    static final String HANDLE = "financeTaskHandle";
    static final String TOOL = "transfer_internal_create";
    static final Set<String> SCOPES = Set.of("transfer:create");
    @TempDir Path directory;
    HttpServer server;
    OpenJiuwenCapabilityInstaller.Installation installed;
    final AtomicInteger creates = new AtomicInteger(), confirms = new AtomicInteger(),
            submits = new AtomicInteger(), queries = new AtomicInteger(), reconciles = new AtomicInteger();
    final List<Map<String, Object>> createBodies = new ArrayList<>();
    boolean granted = true, unavailable, brokenSubmit, brokenCreate;
    String bankStatus = "CREATED";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sessions/current", exchange -> reply(exchange, 200, Map.of(
                "sessionId", "bank-session", "customerRef", "customer-a", "identityStrength", "I2",
                "expiresAt", Instant.now().plusSeconds(300).toString())));
        server.createContext("/api/v1/capabilities/" + CAP + "/preflight", exchange -> reply(exchange, 200,
                Map.of("preflightId", "preflight-a", "canSubmit", true, "summary", "Synthetic transfer",
                        "transferBinding", Map.of("currency", "CNY", "beneficiaryVersion", 7,
                                "amount", new BigDecimal("0.01")))));
        server.createContext("/api/v1/tasks", this::task);
        server.start();
        install("host-a");
    }

    @AfterEach void stop() {
        if (installed != null) installed.close();
        if (server != null) server.stop(0);
    }

    void install(String owner) throws Exception {
        if (installed != null) installed.close();
        var source = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(2),
                () -> new NonAiBankingProvider.HostSession("bank-session", "customer-a", "customer-a",
                        owner, Instant.now().plusSeconds(300))));
        var provider = new BoundFinanceProvider(source, () -> granted ? new BoundFinanceProvider.Grant(
                "customer-a", FinanceContextV1.PrincipalType.CUSTOMER, Set.of("customer-a"), Set.of("TRANSFER"),
                SCOPES, owner, "isolated-host-fixture", Instant.now().plusSeconds(300)) : null,
                Map.of(CAP, SCOPES));
        Path root = Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.isDirectory(root.resolve("capability-packs"))) root = root.getParent();
        var retail = new FinanceCapabilityPackLoader().load(root.resolve("capability-packs/retail-banking-basics/pack.yaml"));
        var pack = new FinanceCapabilityPackV1(FinanceCapabilityPackV1.SCHEMA_VERSION,
                "isolated-transfer", "0.1.0", retail.systemPrompt(),
                retail.capabilities().stream().filter(capability -> CAP.equals(capability.capabilityId())).toList());
        installed = new OpenJiuwenCapabilityInstaller().install(
                new ReActAgent(AgentCard.builder().id("isolated-transfer-agent").name("Transfer test").build()),
                Map.of(CAP, new OpenJiuwenCapabilityInstaller.ProviderBinding(
                        NonAiBankingProvider.PROVIDER_ID, source.descriptor().version(), provider)),
                new OpenJiuwenFinanceBridge.InvocationDefaults("customer-a", "customer-a",
                        FinanceContextV1.PrincipalType.CUSTOMER, "fixture", "TRANSFER",
                        FinanceContextV1.AuthenticationLevel.BASIC, SCOPES),
                pack, true, new FileTaskCheckpointStore(directory), owner);
    }

    @SuppressWarnings("unchecked") Map<String, Object> invoke(Map<String, Object> input) {
        return (Map<String, Object>) assertDoesNotThrow(() -> installed.tool(TOOL).invoke(input));
    }

    String create() {
        var result = invoke(Map.of("fromAccountId", "account-a", "beneficiaryId", "saved-beneficiary",
                "amount", new BigDecimal("0.01")));
        assertEquals("NEED_USER", result.get("status"));
        return (String) result.get(HANDLE);
    }

    HostTaskConfirmation approval(String handle) {
        Instant now = Instant.now();
        return new HostTaskConfirmation(UUID.randomUUID().toString(), installed.confirmationBinding(handle),
                now, now.plusSeconds(60));
    }

    @Test void fileReopenKeepsBindingAndDuplicateConfirmationDoesNotSubmitTwice() throws Exception {
        String handle = create();
        var event = approval(handle);
        install("host-a");
        assertEquals(event.bindingFingerprint(), installed.confirmationBinding(handle));
        assertEquals("COMPLETED", installed.confirm(handle, event).get("status"));
        install("host-a");
        assertEquals("COMPLETED", installed.confirm(handle, event).get("status"));
        assertEquals(1, creates.get()); assertEquals(1, confirms.get()); assertEquals(1, submits.get());
        assertEquals(new BigDecimal("0.01"), new BigDecimal(((Map<?, ?>) createBodies.getFirst()
                .get("parameters")).get("amount").toString()));
    }

    @ParameterizedTest @ValueSource(strings = {"", "no", "query progress", "confirm submit"})
    void modelSuppliedConfirmationNeverBecomesHostApproval(String text) {
        String handle = create();
        assertEquals("NEED_USER", invoke(Map.of(HANDLE, handle, "confirmationText", text)).get("status"));
        assertEquals(0, confirms.get()); assertEquals(0, submits.get());
    }

    @Test void revokedGrantAfterFileReopenBlocksEvenAnExistingApproval() throws Exception {
        String handle = create(); var event = approval(handle);
        granted = false;
        install("host-a");
        int before = queries.get();
        assertEquals("AUTHORIZATION_DENIED", installed.confirm(handle, event).get("errorCode"));
        assertEquals(before, queries.get());
        assertEquals(0, confirms.get()); assertEquals(0, submits.get());
    }

    @Test void anotherHostCannotRecoverOrApproveOriginalHandle() throws Exception {
        String handle = create(); var event = approval(handle);
        install("host-b");
        assertEquals("FINANCE_TASK_HANDLE_NOT_FOUND", invoke(Map.of(HANDLE, handle)).get("errorCode"));
        assertThrows(IllegalArgumentException.class, () -> installed.confirm(handle, event));
        assertEquals(0, queries.get()); assertEquals(0, submits.get());
    }

    @Test void changedMoneyAndExpiredApprovalDoNotReachBank() {
        String handle = create();
        assertEquals("TASK_PARAMETERS_CHANGED", invoke(Map.of(HANDLE, handle,
                "amount", new BigDecimal("0.02"))).get("errorCode"));
        Instant now = Instant.now();
        assertEquals("HOST_CONFIRMATION_INVALID", installed.confirm(handle, new HostTaskConfirmation(
                "expired", installed.confirmationBinding(handle), now.minusSeconds(120), now.minusSeconds(1)))
                .get("errorCode"));
        assertEquals(0, queries.get()); assertEquals(0, confirms.get()); assertEquals(0, submits.get());
    }

    @Test void declineCannotLaterBecomeAConfirmation() {
        String handle = create(); var event = approval(handle);
        assertEquals("HOST_DECLINED", installed.decline(handle).get("errorCode"));
        assertEquals("HOST_DECLINED", installed.confirm(handle, event).get("errorCode"));
        assertEquals(0, confirms.get()); assertEquals(0, submits.get());
    }

    @Test void lostSubmitAndTemporaryQueryFailureRecoverWithoutAnotherWrite() throws Exception {
        String handle = create(); brokenSubmit = true;
        assertEquals(handle, installed.confirm(handle, approval(handle)).get(HANDLE));
        install("host-a"); unavailable = true;
        assertEquals(handle, invoke(Map.of(HANDLE, handle)).get(HANDLE));
        assertThrows(IllegalStateException.class, () -> installed.confirmationBinding(handle));
        unavailable = false;
        assertEquals("COMPLETED", invoke(Map.of(HANDLE, handle)).get("status"));
        assertEquals(1, creates.get()); assertEquals(1, confirms.get()); assertEquals(1, submits.get());
        assertEquals(1, reconciles.get());
    }

    @Test void lostCreateThenChangedInputCannotCreateAnotherTask() throws Exception {
        brokenCreate = true;
        String handle = create();
        install("host-a");
        assertEquals("TASK_PARAMETERS_CHANGED", invoke(Map.of(HANDLE, handle,
                "amount", new BigDecimal("0.02"))).get("errorCode"));
        assertEquals("ORIGINAL_TASK_REQUIRES_VERIFICATION", invoke(Map.of(HANDLE, handle)).get("errorCode"));
        assertEquals(1, creates.get()); assertEquals(0, confirms.get()); assertEquals(0, submits.get());
    }

    @SuppressWarnings("unchecked") void task(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v1/tasks")) {
            creates.incrementAndGet();
            createBodies.add(HarnessJson.mapper().readValue(exchange.getRequestBody(), Map.class));
            if (brokenCreate) { malformed(exchange); return; }
        } else if (path.endsWith("/confirm")) {
            confirms.incrementAndGet(); bankStatus = "CONFIRMED";
        } else if (path.endsWith("/submit")) {
            submits.incrementAndGet(); bankStatus = brokenSubmit ? "UNKNOWN" : "COMPLETED";
            if (brokenSubmit) { malformed(exchange); return; }
        } else if (path.endsWith("/reconcile")) {
            reconciles.incrementAndGet(); bankStatus = "COMPLETED";
        } else {
            queries.incrementAndGet();
            if (unavailable) { reply(exchange, 503, Map.of()); return; }
        }
        reply(exchange, 200, Map.of("taskId", "bank-task-a", "capabilityId", CAP, "status", bankStatus,
                "result", Map.of("transferId", "synthetic-transfer-a")));
    }

    static void malformed(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"status\":\"SUCCESS\",\"data\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }

    static void reply(HttpExchange exchange, int status, Map<String, Object> data) throws IOException {
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(Map.of("status", "SUCCESS", "data", data,
                "sourceSystem", "isolated-bank", "asOf", Instant.now().toString()));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var body = exchange.getResponseBody()) { body.write(bytes); }
    }
}
