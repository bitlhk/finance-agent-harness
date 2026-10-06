package io.github.bitlhk.finance.harness.integrations.agentscope;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.execution.CaseFileWorkspaceV1;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel.*;

/** Real Harness installation and SDK execution; no bank Provider exists in this fixture. */
class MethodOnlyNativeTest {
    @TempDir Path root;
    final List<Harness> opened = new ArrayList<>();
    @AfterEach void close() { opened.forEach(Harness::close); }
    @RegisterExtension final TestWatcher evidence = new TestWatcher() {
        public void testSuccessful(ExtensionContext context) { write(context, "PASS"); }
        public void testFailed(ExtensionContext context, Throwable ignored) { write(context, "FAIL"); }
        void write(ExtensionContext context, String status) {
            try {
                Path directory = Path.of(System.getProperty("agentscope.methodEvidenceDirectory",
                        "target/method-controlled-evidence"));
                Files.createDirectories(directory);
                Files.write(directory.resolve(context.getRequiredTestMethod().getName() + ".json"),
                        HarnessJson.mapper().writeValueAsBytes(Map.of("test", context.getDisplayName(), "checkIds", context.getTags(),
                                "status", status, "runtimeArtifacts", RuntimeInventory.inspect(List.of()),
                                "collectorDeclaration", AgentScopeObservationCollector.methodDeclaration(true),
                                "collectorIdentity", AgentScopeObservationCollector.methodDeclaration(true).identityDigest(),
                                "qualificationIdentity", RuntimeQualification.identity(RuntimeInventory.inspect(List.of()),
                                        AgentScopeObservationCollector.methodDeclaration(true).identityDigest()),
                                "sessions", opened.stream().map(h -> Map.of("modelHttp", h.model.requests,
                                        "lock", h.session.lock(), "observations", h.events)).toList())));
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    };

    @BeforeEach void assets() throws Exception {
        Files.createDirectories(root.resolve("profiles")); Files.createDirectories(root.resolve("catalog"));
        Files.copy(AgentScopeQueryTest.ROOT.resolve("profiles/local-read-only.md"), root.resolve("profiles/local-read-only.md"));
        Files.copy(AgentScopeQueryTest.ROOT.resolve("catalog/finance-assets.v1.json"), root.resolve("catalog/finance-assets.v1.json"));
        Files.createDirectories(root.resolve("method"));
        Files.writeString(root.resolve("method/SKILL.md"), "METHOD_CONTENT_8821: explain the supplied synthetic text, without bank access.");
        Files.writeString(root.resolve("method/pack.yaml"), """
                schemaVersion: finance-domain-pack.v1
                packId: method-only
                version: 0.1.0
                profile: customer-self-service
                capabilities: []
                skills:
                  - directory: method-only
                    entry: SKILL.md
                scope: []
                outOfScope: []
                """);
        var solution = (ObjectNode) HarnessJson.mapper().readTree(AgentScopeQueryTest.REFERENCE.resolve("solution.json").toFile());
        solution.putArray("capabilityPackRefs"); solution.putArray("providerRefs");
        solution.putArray("domainPackRefs").addObject().put("id", "method-only").put("version", "0.1.0")
                .put("source", "repo://method/pack.yaml").put("digest", AssetDigests.sha256Tree(root.resolve("method")));
        Files.writeString(root.resolve("solution.json"), solution.toString());
        var support = (ObjectNode) HarnessJson.mapper().readTree(AgentScopeQueryTest.REFERENCE.resolve("support.json").toFile());
        support.put("schemaVersion", "agentscope-method-support.v0.1"); support.put("operation", "METHOD_ONLY");
        support.putArray("descriptions"); support.putArray("terminology"); support.putArray("providers");
        var requirements = new ObservationRequirementsV01(ObservationRequirementsV01.VERSION, "method-observation", "0.1.0",
                AgentScopeObservationCollector.baseline(true));
        Files.write(root.resolve("observation.json"), HarnessJson.mapper().writeValueAsBytes(requirements));
        support.putArray("observationRequirements").addObject().put("id", "method-observation").put("version", "0.1.0")
                .put("source", "repo://observation.json").put("digest", AssetDigests.sha256(root.resolve("observation.json")));
        Files.writeString(root.resolve("support.json"), support.toString());
    }

    RuntimeQualification bootstrap() throws Exception {
        return new RuntimeQualification(RuntimeInventory.inspect(List.of()), AgentScopeObservationCollector.methodDeclaration(true).identityDigest(),
                "sha256:" + "0".repeat(64), "test-only://not-product-qualification", false, true);
    }
    AgentScopeQueryAssembler.Prepared prepare(RuntimeQualification proof) throws Exception {
        return new AgentScopeQueryAssembler().prepare(root, root.resolve("solution.json"), root.resolve("support.json"), Map.of(), proof);
    }
    Harness harness(Response... script) throws Exception { var h = new Harness(script); opened.add(h); return h; }

    @Test @Tag("method-native") @Tag("method-parameters") @Tag("method-observation")
    void realMethodInstalledNoFinancialToolsAndModelParametersObserved() throws Exception {
        var h = harness(text("Synthetic explanation."));
        assertEquals("Synthetic explanation.", h.call("Explain." ).text());
        assertTrue(h.session.installedTools().isEmpty());
        assertEquals(1, h.model.requests.size());
        var sent = HarnessJson.mapper().readTree(h.model.requests.getFirst().get("rawBody").toString());
        assertTrue(sent.path("messages").toString().contains("METHOD_CONTENT_8821"));
        assertTrue(sent.path("messages").toString().contains("HOST_CONTEXT_PROVENANCE_V1"));
        assertTrue(sent.path("messages").toString().contains("not user-provided facts"));
        assertTrue(sent.path("messages").toString().contains("Trusted business date:"));
        assertEquals(0, sent.path("tools").size());
        assertEquals("controlled", sent.path("model").asText());
        assertEquals(0.25, sent.path("temperature").asDouble()); assertEquals(0.8, sent.path("top_p").asDouble());
        assertEquals(1234, sent.path("max_tokens").asInt());
        var events = HarnessJson.mapper().valueToTree(h.events).toString();
        assertTrue(events.contains("installation")); assertTrue(events.contains("authorization")); assertTrue(events.contains("client-prepared-call"));
        assertFalse(h.session.lock().toString().contains("PROVIDER_RUNTIME"));
        assertTrue(h.session.lock().toString().contains("method-only"));
    }
    @Test @Tag("method-isolation") void separateCasesDoNotInheritEarlierInputs() throws Exception {
        var first = harness(text("FIRST_RESPONSE_SECRET")); first.call("FIRST_CASE_SECRET");
        var second = harness(text("Second.")); second.call("SECOND_CASE");
        assertFalse(second.model.requests.toString().contains("FIRST_CASE_SECRET"));
        assertFalse(second.model.requests.toString().contains("FIRST_RESPONSE_SECRET"));
    }
    @Test @Tag("method-revocation") void denialBeforeSendAndRevocationBeforeResponseClearSession() throws Exception {
        var denied = harness(text("Must not send")); denied.grant.set(null);
        assertThrows(RuntimeException.class, () -> denied.call("Denied")); assertEquals(0, denied.model.requests.size());
        assertFalse(denied.session.valid());
        var h = harness(); var response = text("Late private answer");
        h.model.enqueue(new Response(200, response.body(), () -> h.grant.set(null)));
        assertThrows(RuntimeException.class, () -> h.call("Revoke during HTTP"));
        assertEquals(1, h.model.requests.size()); assertFalse(h.session.valid());
        assertThrows(RuntimeException.class, () -> h.call("Retry")); assertEquals(1, h.model.requests.size());
    }
    @Test @Tag("method-revocation") void wrongIdentitySubjectPurposeSessionAndExpiredGrantBlockBeforeSend() throws Exception {
        for (String field : List.of("principal", "type", "subject", "purpose", "session", "expiry")) {
            var h = harness(text("Not sent"));
            h.grant.set(new BoundFinanceProvider.Grant(field.equals("principal") ? "other" : "actor",
                    field.equals("type") ? FinanceContextV1.PrincipalType.EMPLOYEE : FinanceContextV1.PrincipalType.CUSTOMER,
                    Set.of(field.equals("subject") ? "other" : "subject"), Set.of(field.equals("purpose") ? "other" : "customer-self-service"),
                    Set.of(), field.equals("session") ? "other" : h.sessionId, "controlled-authority",
                    field.equals("expiry") ? Instant.EPOCH : Instant.now().plusSeconds(60)));
            assertThrows(RuntimeException.class, () -> h.call("Denied"), field);
            assertEquals(0, h.model.requests.size(), field); assertFalse(h.session.valid(), field);
        }
    }
    @Test @Tag("method-unknown-tool") void ModelCannotInventUninstalledBankTool() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("Not reached"));
        assertThrows(RuntimeException.class, () -> h.call("Do not query"));
        assertEquals(1, h.model.requests.size()); assertFalse(h.session.valid());
    }
    @Test @Tag("method-model-error") void modelErrorIsSingleAttemptAndInvalidates() throws Exception {
        var h = harness(failure(() -> {}));
        assertThrows(RuntimeException.class, () -> h.call("Explain"));
        assertEquals(1, h.model.requests.size()); assertFalse(h.session.valid());
    }
    @Test @Tag("method-cancel") void cancellationRejectsLateAnswerAndFurtherTurns() throws Exception {
        var h = harness(); var received = new CountDownLatch(1); var release = new CountDownLatch(1);
        var reply = text("Late answer");
        h.model.enqueue(new Response(200, reply.body(), () -> {
            received.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }));
        var future = h.session.invoke(h.input("Wait")).toFuture();
        try { assertTrue(received.await(5, TimeUnit.SECONDS)); h.session.cancel(); }
        finally { release.countDown(); }
        assertThrows(Exception.class, () -> future.get(10, TimeUnit.SECONDS));
        assertFalse(h.session.valid()); assertThrows(RuntimeException.class, () -> h.call("Again"));
        assertEquals(1, h.model.requests.size());
    }
    @Test @Tag("method-sink-degradation") void sinkFailureDoesNotChangeCompletedResponse() throws Exception {
        var h = harness(text("Completed")); h.failSink = true;
        var reply = h.call("Explain");
        assertEquals("Completed", reply.text()); assertTrue(reply.droppedObservationEvents() > 0);
    }
    @Test @Tag("method-admission") void missingMethodAndHiddenToolsCannotBecomeMethodOnly() throws Exception {
        var input = (ObjectNode) HarnessJson.mapper().readTree(root.resolve("solution.json").toFile());
        var original = input.deepCopy(); input.putArray("domainPackRefs");
        Files.writeString(root.resolve("solution.json"), input.toString());
        assertThrows(IllegalArgumentException.class, () -> prepare(bootstrap()));
        Files.writeString(root.resolve("solution.json"), original.toString());
        var domain = root.resolve("method/pack.yaml");
        Files.writeString(domain, Files.readString(domain).replace("capabilities: []", "capabilities: [account.balance.query]"));
        ((ObjectNode) original.withArray("domainPackRefs").get(0)).put("digest", AssetDigests.sha256Tree(root.resolve("method")));
        Files.writeString(root.resolve("solution.json"), original.toString());
        assertThrows(IllegalArgumentException.class, () -> prepare(bootstrap()));
    }
    @Test @Tag("method-qualification") void queryQualificationCannotAuthorizeMethodModeAndEvidenceIsRequired() throws Exception {
        var wrong = new RuntimeQualification(RuntimeInventory.inspect(List.of()), AgentScopeObservationCollector.declaration(true).identityDigest(),
                "sha256:" + "0".repeat(64), "test-only://wrong-mode");
        assertThrows(SecurityException.class, () -> prepare(wrong));
        assertThrows(SecurityException.class, () -> bootstrap().requireMode(false));
        assertThrows(SecurityException.class, () -> bootstrap().requireTask());
        Path file = root.resolve("unverified.json"); Files.writeString(file, "{}");
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(file, AssetDigests.sha256(file)));
    }
    @Test @Tag("method-case-workspace") void optionalCaseFileToolRemainsIsolatedAndCloses() throws Exception {
        var h = harness(tool("read_case_file", Map.of("path", "case.txt")), text("Read file"));
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("case.txt\0CASE_PRIVATE_811\0".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var files = new CaseFileWorkspaceV1(List.of(Map.of("path", "case.txt", "content", "CASE_PRIVATE_811")), digest);
        h.session.close(); h.session = h.prepared.openSession(h.identity, h.grant::get, h.connection(), null, files);
        assertEquals("Read file", h.call("Read case.txt").text());
        assertEquals(Set.of("read_case_file"), h.session.installedTools());
        assertFalse(h.model.requests.getFirst().toString().contains("CASE_PRIVATE_811"));
        assertTrue(h.model.requests.getLast().toString().contains("CASE_PRIVATE_811"));
        h.session.close(); assertThrows(IllegalStateException.class, () -> files.read(Map.of("path", "case.txt")));
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "agentscope.methodQualificationFile", matches = ".+")
    void operatorPinnedEvidenceAdmitsFreshNativeSession() throws Exception {
        Path file = Path.of(System.getProperty("agentscope.methodQualificationFile"));
        var qualification = RuntimeQualification.load(file, System.getProperty("agentscope.methodQualificationDigest"));
        var h = harness(text("Fresh qualified execution"));
        h.session.close();
        h.session = prepare(qualification).openSession(h.identity, h.grant::get, h.connection());
        assertEquals("Fresh qualified execution", h.call("Explain").text());
        assertEquals(1, h.model.requests.size());
        assertTrue(h.session.lock().toString().contains(qualification.digest()));
    }

    final class Harness implements AutoCloseable {
        final ControlledModel model;
        final AgentScopeQueryAssembler.Prepared prepared;
        final String sessionId = UUID.randomUUID().toString();
        final AgentScopeQuerySession.Identity identity = new AgentScopeQuerySession.Identity("actor", FinanceContextV1.PrincipalType.CUSTOMER,
                "subject", sessionId, "customer-self-service", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, 1);
        final AtomicReference<BoundFinanceProvider.Grant> grant = new AtomicReference<>(new BoundFinanceProvider.Grant("actor",
                FinanceContextV1.PrincipalType.CUSTOMER, Set.of("subject"), Set.of("customer-self-service"), Set.of(), sessionId,
                "controlled-authority", Instant.now().plusSeconds(60)));
        final List<ObservationEventV01> events = new CopyOnWriteArrayList<>();
        AgentScopeQuerySession session; boolean failSink;
        Harness(Response... responses) throws Exception {
            prepared = prepare(bootstrap()); model = new ControlledModel(responses);
            try { session = prepared.openSession(identity, grant::get, connection()); }
            catch (RuntimeException failed) { model.close(); throw failed; }
        }
        ModelConnection connection() { return new ModelConnection(model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10),
                new ModelConnection.Generation(0.25, 0.8, 1234)); }
        AgentScopeQuerySession.Input input(String text) {
            String id = UUID.randomUUID().toString();
            var run = new ObservationRun(id, "test-boot", "test-host", event -> {
                if (failSink) throw new IllegalStateException("CONTROLLED_SINK_FAILURE");
                events.add(event); return true;
            });
            return new AgentScopeQuerySession.Input(text, id, UUID.randomUUID().toString(),
                    new TrustedBusinessTimeV01(LocalDate.of(2026, 10, 3), ZoneId.of("Asia/Shanghai"), "test-clock"), run, "host-parent");
        }
        AgentScopeQuerySession.Reply call(String text) { return session.invoke(input(text)).block(Duration.ofSeconds(20)); }
        public void close() { session.close(); model.close(); }
    }
}
