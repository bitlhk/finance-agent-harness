package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.assembly.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.execution.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import io.github.bitlhk.finance.harness.core.observation.ObservationEventV01;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import static io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native SDK boundary probe only; product admission and full HTTP-bank qualification are separate gates. */
class AgentScopeTaskSessionTest {
    @TempDir Path temporary;
    final List<Fixture> fixtures = new ArrayList<>();
    @RegisterExtension final TestWatcher evidence = new TestWatcher() {
        public void testSuccessful(ExtensionContext context) { write(context, "PASS", ""); }
        public void testFailed(ExtensionContext context, Throwable failure) { write(context, "FAIL", failure.getClass().getSimpleName()); }
        private void write(ExtensionContext context, String status, String failure) {
            try {
                Path directory = Path.of(System.getProperty("agentscope.evidenceDirectory", AgentScopeQueryTest.ROOT.resolve(
                        "integrations/agentscope-java/target/task-evidence/" + AgentScopeQueryTest.RUN).toString()));
                Files.createDirectories(directory);
                var rows = fixtures.stream().map(f -> Map.of("modelHttp", f.host.model.requests,
                        "bankHttp", f.bankHttp, "syntheticProviderCalls", f.bank.calls, "syntheticSubmissions", f.bank.writes,
                        "observations", f.host.events)).toList();
                Files.write(directory.resolve(context.getRequiredTestMethod().getName() + "-" + UUID.randomUUID() + ".json"),
                        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("test", context.getDisplayName(),
                                "testMethod", context.getRequiredTestMethod().getName(), "checkIds", context.getTags(), "status", status,
                                "failure", failure, "sessions", rows, "runtimeArtifacts", RuntimeInventory.inspect(List.of(fixtures.getFirst().host.provider)))));
            } catch (Exception error) { throw new AssertionError(error); }
        }
    };
    Fixture fixture() throws Exception { var result = new Fixture(); fixtures.add(result); return result; }
    static final String CAP = "transfer.internal.create";
    static final Map<String, Object> PARAMETERS = Map.of("fromAccountId", "synthetic-account", "beneficiaryId", "synthetic-beneficiary", "amount", 12);
    static final class Journal implements TaskCoordination.Journal {
        final Map<String, TaskCoordination.Saved> rows = new HashMap<>();
        boolean failWrites;
        public synchronized <T> T locked(Function<TaskCoordination.Access, T> fn) {
            return fn.apply(new TaskCoordination.Access() {
                public TaskCoordination.Saved get(String handle) { return rows.get(handle); }
                public void put(String handle, TaskCoordination.Saved value) {
                    if (failWrites) throw new IllegalStateException("SYNTHETIC_DISK_FAILURE");
                    rows.put(handle, value);
                }
            });
        }
    }
    static final class Bank implements DescribedFinanceProvider {
        int calls, writes; boolean loseResponse; Runnable beforeReturn = () -> {};
        public ProviderDescriptorV1 descriptor() {
            return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, "synthetic-bank", "0.1",
                    List.of(new ProviderDescriptorV1.CapabilitySupport(CAP, CapabilityRequestV1.Operation.TASK)));
        }
        public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError("NOT_QUERY"); }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            calls++;
            assertEquals(CapabilityRequestV1.Operation.TASK, request.operation());
            boolean approval = request.confirmation() != null;
            if (approval) { writes++; if (loseResponse) throw new IllegalStateException("SYNTHETIC_RESPONSE_LOST"); }
            boolean complete = approval || Boolean.TRUE.equals(request.continuation().get("submitOutcomeUnknown"));
            beforeReturn.run();
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                    complete ? CapabilityResultV1.Status.COMPLETED : CapabilityResultV1.Status.NEED_CONFIRMATION,
                    complete ? "Synthetic completed" : "Synthetic confirmation required", PARAMETERS, List.of(), List.of(), "synthetic-bank", Instant.now(),
                    new CapabilityResultV1.TaskState("synthetic-task", complete ? "COMPLETED" : "CREATED", !complete, Map.of()), null);
        }
    }
    static final class Fixture implements AutoCloseable {
        final AgentScopeQueryTest.Harness host;
        final Journal journal = new Journal(); final Bank bank = new Bank();
        AgentScopeQueryAssembler.Prepared prepared;
        TaskCoordination.Journal storage = journal;
        final List<Map<String, Object>> bankHttp = new CopyOnWriteArrayList<>();
        Fixture() throws Exception {
            host = new AgentScopeQueryTest.Harness(tool("transfer_internal_create", PARAMETERS), text("Await trusted confirmation."));
            host.session.close();
            var original = host.initialGrant;
            host.grant.set(new BoundFinanceProvider.Grant(original.principalId(), original.principalType(), Set.of(host.subject),
                    Set.of("customer-self-service"), Set.of("transfer:create"), host.sessionId, "fixture-authority", Instant.now().plusSeconds(300)));
            var pack = new FinanceCapabilityPackLoader().load(AgentScopeQueryTest.ROOT.resolve("capability-packs/internal-transfer/pack.yaml"));
            prepared = new AgentScopeQueryAssembler.Prepared(host.prepared.spec, host.prepared.assets,
                    Map.of(CAP, new AgentScopeQueryAssembler.Binding(pack.capabilities().getFirst(), bank)),
                    CapabilityDescriptionCatalogV01.base(List.of(pack)), pack.systemPrompt(), host.prepared.assessment,
                    List.of(), List.of(pack), host.prepared.profile);
            open(1, host.subject);
        }
        void open(long generation, String subject) {
            host.session = prepared.openSession(new AgentScopeQuerySession.Identity(host.principal, FinanceContextV1.PrincipalType.CUSTOMER,
                    subject, host.sessionId, "customer-self-service", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, generation), host.grant::get,
                    new ModelConnection(host.model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10)),
                    new AgentScopeQuerySession.TaskHost("fixture-installation", storage));
        }
        String start() {
            var reply = host.call("Make the synthetic transfer.");
            assertEquals(1, reply.taskResults().size());
            assertEquals(0, bank.writes);
            return (String) reply.taskResults().getFirst().get(TaskCoordination.HANDLE_ARGUMENT);
        }
        Object action(String action, String handle, TaskCoordination.Approval approval) {
            return host.session.taskAction(host.input("Trusted host action"), action, handle, approval);
        }
        TaskCoordination.Approval approval(String handle) {
            String binding = (String) action("binding", handle, null);
            return new TaskCoordination.Approval("fixture-event", binding, Instant.now().minusSeconds(1), Instant.now().plusSeconds(60));
        }
        public void close() { host.close(); }
    }
    @Test @Tag("task-native-confirmation") @Tag("task-repeated-confirmation") void nativeToolRequiresHostAndOnlyTrustedConfirmationSubmits() throws Exception {
        try (var f = fixture()) {
            assertThrows(SecurityException.class, AgentScopeQueryTest.testAuthority(f.host.provider)::requireTask);
            assertThrows(IllegalArgumentException.class, () -> f.prepared.openSession(new AgentScopeQuerySession.Identity(f.host.principal,
                    FinanceContextV1.PrincipalType.CUSTOMER, f.host.subject, f.host.sessionId, "customer-self-service", "isolated",
                    FinanceContextV1.AuthenticationLevel.STRONG, 2), f.host.grant::get,
                    new ModelConnection(f.host.model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10))));
            String handle = f.start(); var approval = f.approval(handle);
            assertEquals("COMPLETED", ((Map<?, ?>) f.action("confirm", handle, approval)).get("status"));
            f.action("confirm", handle, approval);
            assertEquals(1, f.bank.writes); assertEquals(2, f.host.model.requests.size());
            assertEquals("TASK", ((Map<?, ?>)((List<?>)((Map<?, ?>)((List<?>)f.host.session.lock().get("installations")).getFirst()).get("capabilities")).getFirst()).get("operation"));
        }
    }
    @Test @Tag("task-unknown-recovery") void unknownSurvivesNewSessionAndResumesWithoutSecondSubmission() throws Exception {
        try (var f = fixture()) {
            String handle = f.start(); var approval = f.approval(handle); f.bank.loseResponse = true;
            assertEquals("NEED_USER", ((Map<?, ?>)f.action("confirm", handle, approval)).get("status"));
            f.host.session.close(); f.open(2, f.host.subject);
            assertEquals("COMPLETED", ((Map<?, ?>)f.action("resume", handle, null)).get("status"));
            assertEquals(1, f.bank.writes); assertEquals(2, f.host.model.requests.size());
        }
    }
    @Test @Tag("task-revoke-before-return") void revocationBeforeProviderReturnPreventsNextModelRequest() throws Exception {
        try (var f = fixture()) {
            f.bank.beforeReturn = () -> f.host.grant.set(null);
            assertThrows(RuntimeException.class, () -> f.start());
            assertEquals(1, f.host.model.requests.size()); assertEquals(0, f.bank.writes);
            assertFalse(f.journal.rows.isEmpty());
        }
    }
    @Test @Tag("task-owner-isolation") void newSubjectCannotResumeOriginalTask() throws Exception {
        try (var f = fixture()) {
            String handle = f.start(); f.host.session.close();
            var g = f.host.grant.get();
            f.host.grant.set(new BoundFinanceProvider.Grant(g.principalId(), g.principalType(), Set.of("other-subject"),
                    Set.of("customer-self-service"), Set.of("transfer:create"), f.host.sessionId, "fixture-authority", Instant.now().plusSeconds(300)));
            f.open(2, "other-subject");
            assertThrows(SecurityException.class, () -> f.action("resume", handle, null));
            assertEquals(1, f.bank.calls); assertEquals(0, f.bank.writes);
        }
    }
    @Test @Tag("task-persistent-restart") void pendingTaskRestoresFromNewFileJournal() throws Exception {
        try (var f = fixture()) {
            f.host.session.close(); f.storage = new FileTaskJournal(temporary); f.open(2, f.host.subject);
            String handle = f.start(); var approval = f.approval(handle);
            f.host.session.close(); f.storage = new FileTaskJournal(temporary); f.open(3, f.host.subject);
            assertEquals("COMPLETED", ((Map<?, ?>) f.action("confirm", handle, approval)).get("status"));
            assertEquals(1, f.bank.writes);
        }
    }
    @Test @Tag("task-storage-failure") void failedCheckpointStopsProvider() throws Exception {
        try (var f = fixture()) {
            f.journal.failWrites = true;
            assertThrows(RuntimeException.class, f::start);
            assertEquals(0, f.bank.calls); assertEquals(0, f.bank.writes); assertEquals(1, f.host.model.requests.size());
        }
    }
    @Test @Tag("task-parameter-freeze") void nativeContinuationCannotChangeFrozenParameters() throws Exception {
        try (var f = fixture()) {
            String handle = f.start();
            f.host.model.enqueue(tool("transfer_internal_create", Map.of("financeTaskHandle", handle, "amount", 99)));
            f.host.model.enqueue(text("Frozen parameters cannot change."));
            var reply = f.host.call("Change the original amount.");
            assertEquals("TASK_PARAMETERS_CHANGED", reply.taskResults().getFirst().get("errorCode"));
            assertEquals(1, f.bank.calls); assertEquals(0, f.bank.writes);
        }
    }
    @Test @Tag("task-cancel-late") void cancelledNativeCallDoesNotSendLateTaskResultToModel() throws Exception {
        try (var f = fixture()) {
            var reached = new CountDownLatch(1); var release = new CountDownLatch(1);
            f.bank.beforeReturn = () -> {
                reached.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("TEST_TIMEOUT"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            };
            var future = f.host.session.invoke(f.host.input("Prepare synthetic transfer"))
                    .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()).toFuture();
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS));
                f.host.session.cancel(); release.countDown();
                assertThrows(Exception.class, () -> future.get(10, TimeUnit.SECONDS));
                f.journal.locked(a -> null); // Wait for the late provider completion under the same journal lock.
                assertEquals(1, f.host.model.requests.size()); assertEquals(0, f.bank.writes);
            } finally { release.countDown(); future.cancel(true); }
        }
    }
    @Test @Tag("task-http-bank") void originalProviderUsesSyntheticBankHttpAndNeverResubmitsUnknown() throws Exception {
        try (var f = fixture()) {
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            var submits = new AtomicInteger(); var confirms = new AtomicInteger(); var creates = new AtomicInteger();
            var status = new java.util.concurrent.atomic.AtomicReference<>("CREATED");
            server.createContext("/api/v1/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                f.bankHttp.add(Map.of("method", exchange.getRequestMethod(), "route", path));
                exchange.getRequestBody().readAllBytes();
                Map<String, Object> data;
                if (path.equals("/api/v1/sessions/current")) {
                    data = Map.of("sessionId", "synthetic-source-session", "customerRef", f.host.subject,
                            "identityStrength", "I2", "expiresAt", Instant.now().plusSeconds(300).toString());
                } else if (path.endsWith("/preflight")) {
                    data = Map.of("preflightId", "synthetic-preflight", "capabilityId", CAP, "capabilityVersion", "1.0.0",
                            "riskLevel", "R3", "canSubmit", true, "summary", "Synthetic transfer", "requiredConfirmations", List.of("Confirm"));
                } else {
                    if (path.equals("/api/v1/tasks")) { creates.incrementAndGet(); status.set("CREATED"); }
                    if (path.endsWith("/confirm")) { confirms.incrementAndGet(); status.set("CONFIRMED"); }
                    if (path.endsWith("/submit")) { submits.incrementAndGet(); status.set("UNKNOWN"); }
                    if (path.endsWith("/reconcile")) status.set("COMPLETED");
                    data = Map.of("taskId", "synthetic-bank-task", "capabilityId", CAP, "status", status.get(),
                            "operation", "SUBMIT", "result", Map.of("receiptId", "synthetic-receipt"));
                }
                byte[] body = HarnessJson.mapper().writeValueAsBytes(Map.of("status", "SUCCESS", "data", data,
                        "sourceSystem", "synthetic-bank", "asOf", Instant.now().toString()));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            try {
                var provider = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(
                        java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofSeconds(5),
                        () -> new NonAiBankingProvider.HostSession("synthetic-source-session", f.host.subject, f.host.principal,
                                f.host.sessionId, Instant.now().plusSeconds(300))).observations(AgentScopeObservationCollector::currentRun));
                var previous = f.prepared;
                f.prepared = new AgentScopeQueryAssembler.Prepared(previous.spec, previous.assets,
                        Map.of(CAP, new AgentScopeQueryAssembler.Binding(previous.packs.getFirst().capabilities().getFirst(), provider)),
                        previous.catalog, previous.prompt, previous.assessment, previous.domains, previous.packs, previous.profile);
                f.host.session.close(); f.open(2, f.host.subject);
                String handle = f.start(); assertEquals(1, creates.get()); assertEquals(0, submits.get());
                var result = (Map<?, ?>) f.action("confirm", handle, f.approval(handle));
                assertEquals("NEED_USER", result.get("status"));
                f.host.session.close(); f.open(3, f.host.subject);
                assertEquals("COMPLETED", ((Map<?, ?>) f.action("resume", handle, null)).get("status"));
                assertEquals(1, creates.get()); assertEquals(1, confirms.get()); assertEquals(1, submits.get());
                assertEquals(2, f.host.model.requests.size());
                assertTrue(f.host.events.stream().anyMatch(e -> e.component().equals("capability") && e.phase() == ObservationEventV01.Phase.END));
            } finally { server.stop(0); }
        }
    }
}
