package io.github.bitlhk.finance.harness.integrations.employeeagent;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentIdentityBinding;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthFactoryV01;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeAgentWealthSessionTest {
    private static final String PREVISIT = EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY;
    private static final String ALLOCATION = EmployeeAgentWealthProvider.WEALTH_ALLOCATION_CONTEXT_QUERY;
    private static final EmployeeAgentRuntimeIdentity RUNTIME = new EmployeeAgentRuntimeIdentity(
            "synthetic-runtime", "synthetic-agent", "lgj-synthetic");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger tokens = new AtomicInteger();
    private final AtomicReference<EmployeeAgentWealthSession.Authority> authority = new AtomicReference<>();
    private final AtomicReference<FinanceContextV1> context = new AtomicReference<>();
    private HttpServer server;
    private EmployeeAgentWealthSession session;
    private ManagedProviderFactoryV01.Connection connection;
    private volatile Runnable beforeResponse = () -> {};

    @BeforeEach void start() throws Exception {
        authority.set(snapshot("owner-a", "authority-a", Set.of("syn-customer"), Set.of("wealth:previsit:read")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform-tools/mcp", exchange -> {
            calls.incrementAndGet();
            var input = HarnessJson.mapper().readTree(exchange.getRequestBody());
            assertEquals("prepare_wealth_previsit_context", input.path("params").path("name").asText());
            assertEquals("syn-customer", input.path("params").path("arguments").path("customer_id").asText());
            beforeResponse.run();
            var payload = Map.of("schema", "ea.wealth-previsit-context.v1", "status", "ready",
                    "customer", Map.of("customerId", "syn-customer"));
            var receipt = Map.of("receiptId", "synthetic-receipt", "receiptFingerprint", "a".repeat(64),
                    "createdAt", "2026-10-08T00:00:00Z");
            byte[] body = HarnessJson.mapper().writeValueAsBytes(Map.of("jsonrpc", "2.0",
                    "id", input.path("id").asText(), "result", Map.of("content", List.of(Map.of(
                            "type", "text", "text", "EA_WEALTH_PREVISIT_CONTEXT:"
                                    + HarnessJson.mapper().writeValueAsString(payload))),
                            "_meta", Map.of("eaContextReceipt", receipt))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        connection = new ManagedProviderFactoryV01.Connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(5),
                () -> { throw new AssertionError("Must not use the customer simulator session"); },
                () -> null, ignored -> {});
        session = new EmployeeAgentWealthSession("owner-a", "syn-session", RUNTIME, authority::get,
                requestContext -> { context.set(requestContext); tokens.incrementAndGet(); return "synthetic-token"; },
                connection, Set.of(PREVISIT, ALLOCATION));
    }

    @AfterEach void stop() {
        if (session != null) session.close();
        if (server != null) server.stop(0);
    }

    private static EmployeeAgentWealthSession.Authority snapshot(String owner, String generation,
            Set<String> customers, Set<String> scopes) {
        var expires = Instant.now().plusSeconds(120);
        return new EmployeeAgentWealthSession.Authority(owner,
                new EmployeeAgentIdentityBinding("synthetic-manager", RUNTIME, generation, expires),
                new BoundFinanceProvider.Grant("synthetic-manager", FinanceContextV1.PrincipalType.EMPLOYEE,
                        customers, Set.of("WEALTH_PREVISIT"), scopes, "syn-session", generation, expires),
                FinanceContextV1.AuthenticationLevel.BASIC);
    }

    private CapabilityResultV1 query() { return session.query("syn-request", "syn-trace", "syn-customer", PREVISIT, Map.of()); }

    private void assertNoCall() { assertEquals(0, calls.get()); assertEquals(0, tokens.get()); }

    @Test void constructionDoesNotReadCrmOrIssueTokens() { assertNoCall(); }

    @Test void usesVerifiedPrincipalAndPreservesEvidenceWithoutElevatingAuthentication() {
        var result = query();
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertTrue(result.data().containsKey("previsitContext"));
        assertEquals("synthetic-manager", context.get().principalId());
        assertEquals("syn-session", context.get().sessionRef());
        assertEquals(FinanceContextV1.AuthenticationLevel.BASIC, context.get().authenticationLevel());
        assertEquals(1, calls.get());
    }

    @Test void differentLoggedInOwnerCannotBorrowAnAdoption() {
        authority.set(snapshot("owner-b", "authority-a", Set.of("syn-customer"), Set.of("wealth:previsit:read")));
        assertThrows(SecurityException.class, this::query); assertNoCall();
    }

    @Test void customerOutsideActualGrantCannotBeSelected() {
        assertThrows(SecurityException.class, () -> session.query("r", "t", "other-customer", PREVISIT, Map.of()));
        assertNoCall();
    }

    @Test void metadataCannotGrantAllocationPermission() {
        assertThrows(SecurityException.class, () -> session.query("r", "t", "syn-customer", ALLOCATION, Map.of()));
        assertNoCall();
    }

    @Test void missingMappingDoesNotCreateUserOrFallBackToCustomerIdentity() {
        authority.set(null);
        assertThrows(SecurityException.class, this::query); assertNoCall();
    }

    @Test void rejectsModelSuppliedIdentityAndCustomerFields() {
        for (var input : List.of(Map.<String, Object>of("customerRef", "other"),
                Map.<String, Object>of("principalId", "other"), Map.<String, Object>of("confirmed", true))) {
            assertThrows(IllegalArgumentException.class, () -> session.query("r", "t", "syn-customer", PREVISIT, input));
        }
        assertNoCall();
    }

    @Test void noTransferOrCrmMutationCanUseThisSession() {
        for (String capability : List.of("transfer.create", "crm.followup.create")) {
            assertThrows(IllegalArgumentException.class, () -> session.query("r", "t", "syn-customer", capability, Map.of()));
        }
        assertNoCall();
    }

    @Test void revocationDuringRemoteRequestDiscardsFacts() {
        beforeResponse = () -> authority.set(null);
        assertThrows(SecurityException.class, this::query);
        assertEquals(1, calls.get());
    }

    @Test void customerGrantChangeDuringRequestDiscardsFactsEvenWithSameBinding() {
        beforeResponse = () -> authority.set(snapshot("owner-a", "authority-a", Set.of("other-customer"), Set.of("wealth:previsit:read")));
        assertThrows(SecurityException.class, this::query);
        assertEquals(1, calls.get());
    }

    @Test void replacedAuthorityDiscardsFacts() {
        beforeResponse = () -> authority.set(snapshot("owner-a", "authority-b", Set.of("syn-customer"), Set.of("wealth:previsit:read")));
        assertThrows(SecurityException.class, this::query);
        assertEquals(1, calls.get());
    }

    @Test void closedSessionCannotBeRestoredByLateResultOrNewQuery() {
        beforeResponse = session::close;
        assertThrows(SecurityException.class, this::query);
        assertThrows(SecurityException.class, this::query);
        assertEquals(1, calls.get()); assertEquals(1, tokens.get());
    }

    @Test void alreadyClosedSessionDoesNotDispatch() {
        session.close(); session.close();
        assertThrows(SecurityException.class, this::query); assertNoCall();
    }

    @Test void authorityLoggingDoesNotExposeIdentity() {
        assertEquals("Authority[redacted]", authority.get().toString());
    }

    @Test void mismatchedPrincipalSessionAuthorityOrExpiredGrantIsRejected() {
        var original = authority.get();
        var grant = original.grant();
        for (var invalid : List.of(
                new BoundFinanceProvider.Grant("other-manager", grant.principalType(), grant.subjects(), grant.purposes(),
                        grant.scopes(), grant.sessionRef(), grant.authorityRef(), grant.expiresAt()),
                new BoundFinanceProvider.Grant(grant.principalId(), FinanceContextV1.PrincipalType.CUSTOMER,
                        grant.subjects(), grant.purposes(), grant.scopes(), grant.sessionRef(), grant.authorityRef(), grant.expiresAt()),
                new BoundFinanceProvider.Grant(grant.principalId(), grant.principalType(), grant.subjects(), grant.purposes(),
                        grant.scopes(), "other-session", grant.authorityRef(), grant.expiresAt()),
                new BoundFinanceProvider.Grant(grant.principalId(), grant.principalType(), grant.subjects(), grant.purposes(),
                        grant.scopes(), grant.sessionRef(), "other-authority", grant.expiresAt()),
                new BoundFinanceProvider.Grant(grant.principalId(), grant.principalType(), grant.subjects(), grant.purposes(),
                        grant.scopes(), grant.sessionRef(), grant.authorityRef(), Instant.EPOCH))) {
            authority.set(new EmployeeAgentWealthSession.Authority(original.ownerRef(), original.binding(), invalid,
                    original.authenticationLevel()));
            assertThrows(SecurityException.class, this::query);
        }
        assertNoCall();
    }

    @Test void differentAdoptionAndExpiredBindingAreRejected() {
        var original = authority.get();
        for (var binding : List.of(
                new EmployeeAgentIdentityBinding(original.binding().principalId(),
                        new EmployeeAgentRuntimeIdentity("other-runtime", "other-agent", "lgj-other"),
                        original.binding().authorityRef(), original.binding().expiresAt()),
                new EmployeeAgentIdentityBinding(original.binding().principalId(), RUNTIME,
                        original.binding().authorityRef(), Instant.EPOCH))) {
            authority.set(new EmployeeAgentWealthSession.Authority(original.ownerRef(), binding, original.grant(),
                    original.authenticationLevel()));
            assertThrows(SecurityException.class, this::query);
        }
        assertNoCall();
    }

    @Test void unknownAuthenticationCannotBecomeStrongBecauseAnAgentExists() {
        var original = authority.get();
        authority.set(new EmployeeAgentWealthSession.Authority(original.ownerRef(), original.binding(), original.grant(),
                FinanceContextV1.AuthenticationLevel.UNKNOWN));
        assertThrows(SecurityException.class, this::query); assertNoCall();
    }

    @Test void permissionDoesNotInstallAnUnselectedCapability() {
        authority.set(snapshot("owner-a", "authority-a", Set.of("syn-customer"),
                Set.of("wealth:previsit:read", "wealth:allocation:read")));
        try (var limited = new EmployeeAgentWealthSession("owner-a", "syn-session", RUNTIME, authority::get,
                ignored -> { tokens.incrementAndGet(); return "unused"; }, connection, Set.of(PREVISIT))) {
            assertThrows(IllegalArgumentException.class,
                    () -> limited.query("r", "t", "syn-customer", ALLOCATION, Map.of()));
        }
        assertNoCall();
    }
}
