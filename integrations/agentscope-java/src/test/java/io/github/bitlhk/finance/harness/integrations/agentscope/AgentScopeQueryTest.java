package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel.*;

class AgentScopeQueryTest {
    static final Path ROOT = Path.of(System.getProperty("user.dir")).toAbsolutePath().resolve("../..").normalize();
    static final Path REFERENCE = ROOT.resolve("integrations/agentscope-java/reference");
    static final String RUN = System.getProperty("agentscope.evidenceRun", UUID.randomUUID().toString());
    final List<Harness> harnesses = new ArrayList<>();
    @RegisterExtension final TestWatcher evidence = new TestWatcher() {
        public void testSuccessful(ExtensionContext c) { write(c, "PASS", ""); }
        public void testFailed(ExtensionContext c, Throwable cause) { write(c, "FAIL", cause.toString()); }
        private void write(ExtensionContext c, String status, String failure) {
            try {
                Path directory = Path.of(System.getProperty("agentscope.evidenceDirectory",
                        ROOT.resolve("integrations/agentscope-java/target/controlled-evidence/" + RUN).toString()));
                Files.createDirectories(directory);
                var rows = harnesses.stream().map(h -> Map.of("modelHttp", h.model.requests, "bankHttp", h.bank.requests,
                        "observations", h.events, "callbackThreads", h.threads, "replies", h.replies, "terminalMemoryChecks", h.memoryChecks)).toList();
                Files.write(directory.resolve(c.getRequiredTestMethod().getName() + "-" + UUID.randomUUID() + ".json"),
                        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("test", c.getDisplayName(),
                                "testMethod", c.getRequiredTestMethod().getName(), "checkIds", c.getTags(), "status", status, "failure", failure, "sessions", rows,
                                "runtimeArtifacts", harnesses.isEmpty() ? List.of() : RuntimeInventory.inspect(List.of(harnesses.getFirst().provider)))));
            } catch (Exception failureToWrite) { throw new AssertionError(failureToWrite); }
        }
    };
    @AfterEach void cleanup() { harnesses.forEach(Harness::close); }
    Harness harness(Response... responses) throws Exception { var h = new Harness(responses); harnesses.add(h); return h; }

    @Test @Tag("native-query") void nativeBalanceJsonAndReceipt() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("Balance 12345.67 CNY from controlled source."));
        var reply = h.call("Read my authorized balance.");
        assertEquals(2, h.model.requests.size()); assertEquals(1, h.bank.customerReads());
        assertEquals(1, reply.executions().size()); assertEquals(CapabilityResultV1.Status.COMPLETED, reply.executions().getFirst().result().status());
        assertEquals(0, reply.droppedObservationEvents());
        var sent = HarnessJson.mapper().readTree(h.model.requests.get(1).get("rawBody").toString());
        assertTrue(sent.path("messages").toString().contains("12345.67"));
        assertTrue(sent.path("messages").findValuesAsText("role").contains("tool"));
        assertEquals(Set.of("account_balance_query", "finance_transaction_summary", "finance_spending_analysis"), h.session.installedTools());
        assertFalse(sent.path("stream").asBoolean()); assertNotNull(reply.executions().getFirst().receipt());
        assertParents(h);
    }
    @RepeatedTest(3) @Tag("compound") void compoundOriginalProviderAndMethod() throws Exception {
        var h = harness(tool("finance_transaction_summary", Map.of("period", "2026-08")),
                tool("finance_spending_analysis", Map.of("period", "2026-08")), text("Controlled compound complete."));
        var reply = h.call("Summarize 2026-08 cashflow and then explain spending.");
        assertEquals(3, h.model.requests.size()); assertEquals(2, h.bank.customerReads());
        assertEquals(2, reply.executions().size()); assertEquals(0, reply.droppedObservationEvents());
        assertEquals(2, reply.executions().stream().map(e -> e.request().context().requestId()).distinct().count());
        assertEquals(2, reply.executions().stream().map(AgentScopeQuerySession.QueryExecution::toolCallId).distinct().count());
        for (var execution : reply.executions()) {
            assertEquals(reply.requestId(), execution.rootRequestId());
            assertNotEquals(reply.requestId(), execution.receipt().requestId());
            assertEquals(execution.request().context().requestId(), execution.receipt().requestId());
            assertEquals("2026-08", execution.request().parameters().get("period"));
            assertEquals(CapabilityResultV1.Status.COMPLETED, execution.result().status());
            var direct = h.provider.query(execution.request());
            var expected = HarnessJson.mapper().valueToTree(direct.data());
            var actual = HarnessJson.mapper().valueToTree(execution.result().data());
            ((com.fasterxml.jackson.databind.node.ObjectNode) expected.at("/facts/source")).remove("observedAt");
            ((com.fasterxml.jackson.databind.node.ObjectNode) actual.at("/facts/source")).remove("observedAt");
            assertEquals(expected, actual);
        }
        String first = h.model.requests.getFirst().get("rawBody").toString();
        assertTrue(first.contains("2026-09-20")); assertTrue(first.contains("Asia/Shanghai"));
        assertTrue(first.contains("cashflow"));
        String sop = h.prepared.domains.getFirst().skills().getFirst().instructions();
        var messages = HarnessJson.mapper().readTree(first).path("messages");
        assertTrue(messages.findValuesAsText("content").stream().anyMatch(s -> s.contains(sop)));
        assertParents(h);
    }
    @RepeatedTest(3) @Tag("multi-turn") void multiTurnUsesNewPeriodWithoutRepeatingAnalysis() throws Exception {
        var h = harness(tool("finance_transaction_summary", Map.of("period", "2026-08")), text("August summary."),
                tool("finance_transaction_summary", Map.of("period", "2026-07")), text("July summary."));
        h.call("Only summarize last month."); var second = h.call("Change to 2026-07.");
        assertEquals(4, h.model.requests.size()); assertEquals(2, h.bank.customerReads());
        assertEquals("2026-07", second.executions().getFirst().request().parameters().get("period"));
        assertTrue(h.bank.requests.stream().noneMatch(row -> row.get("uri").toString().contains("spending-analysis")));
        assertEquals(0, second.droppedObservationEvents());
    }
    @Test @Tag("native-continuation") void completedReplyAllowsImmediateReactiveContinuation() throws Exception {
        var h = harness(text("First response."), text("Second response."), text("Third response."));
        var reply = h.session.invoke(h.input("First turn."))
                .flatMap(first -> h.session.invoke(h.input("Second turn.")))
                .flatMap(second -> h.session.invoke(h.input("Third turn.")))
                .block(Duration.ofSeconds(20));
        assertEquals("Third response.", reply.text());
        assertEquals(3, h.model.requests.size());
        assertEquals(0, h.bank.customerReads());
        assertTrue(h.session.valid());
    }
    @RepeatedTest(3) @Tag("missing-data") void missingAnalysisRemainsFailedNotZero() throws Exception {
        var h = harness(tool("finance_transaction_summary", Map.of("period", "2026-08")),
                tool("finance_spending_analysis", Map.of("period", "2026-08")), text("Summary retained; analysis unavailable."));
        h.bank.analysisUnavailable = true;
        var reply = h.call("Summarize and classify 2026-08.");
        assertEquals(3, h.model.requests.size()); assertEquals(2, reply.executions().size());
        assertEquals(CapabilityResultV1.Status.COMPLETED, reply.executions().get(0).result().status());
        assertEquals(CapabilityResultV1.Status.FAILED, reply.executions().get(1).result().status());
        assertNotNull(reply.executions().get(1).result().error()); assertEquals(0, reply.droppedObservationEvents());
    }
    @Test void ambiguousYearCanClarifyWithoutBank() throws Exception {
        var h = harness(text("Which year do you mean by August?"));
        assertEquals(0, h.call("Summarize August.").executions().size()); assertEquals(0, h.bank.requests.size());
    }
    @Test @Tag("revoke-before-return") void revokeBeforeProviderReturnStopsAllFurtherHttp() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("MUST_NOT_SEND"));
        h.bank.beforeResponse = path -> h.grant.set(null);
        assertThrows(RuntimeException.class, () -> h.call("Read balance."));
        assertEquals(1, h.bank.customerReads()); assertEquals(1, h.model.requests.size()); assertFalse(h.session.valid());
        h.grant.set(h.initialGrant);
        assertThrows(RuntimeException.class, () -> h.call("Reuse stale generation."));
        assertEquals(1, h.model.requests.size());
    }
    @Test @Tag("tool-denial") void toolBoundaryDenialSendsNoBankRequest() throws Exception {
        var response = tool("account_balance_query", Map.of());
        var h = harness(); h.model.enqueue(new Response(response.status(), response.body(), () -> h.grant.set(null)));
        assertThrows(RuntimeException.class, () -> h.call("Read balance."));
        assertEquals(1, h.model.requests.size()); assertEquals(0, h.bank.requests.size());
    }
    @Test @Tag("schema-denial") void invalidJsonAndUninstalledToolsCannotContinue() throws Exception {
        for (var response : List.of(tool("finance_transaction_summary", Map.of("period", "last-month")),
                tool("account_balance_query", Map.of("subjectRef", "other")), tool("bank_transfer", Map.of()), tool("shell", Map.of()))) {
            var h = harness(response, text("MUST_NOT_SEND"));
            assertThrows(RuntimeException.class, () -> h.call("Query."));
            assertEquals(1, h.model.requests.size()); assertEquals(0, h.bank.requests.size()); assertFalse(h.session.valid());
        }
    }
    @Test void wrongHostAndSourceSubjectsAreDenied() throws Exception {
        var host = harness(text("MUST_NOT_SEND")); host.grant.set(new BoundFinanceProvider.Grant("other", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of(host.subject), Set.of("customer-self-service"), Set.of("account:read", "cashflow:read"), host.sessionId, "fixture-authority", Instant.now().plusSeconds(300)));
        assertThrows(RuntimeException.class, () -> host.call("Query.")); assertEquals(0, host.model.requests.size());
        var source = harness(tool("account_balance_query", Map.of()));
        source.bankSession.set(new NonAiBankingProvider.HostSession("fixture-token", "other", source.principal, source.sessionId, Instant.now().plusSeconds(300)));
        assertThrows(RuntimeException.class, () -> source.call("Query."));
        assertEquals(0, source.bank.requests.size()); assertEquals(1, source.model.requests.size());
    }
    @Test @Tag("model-single-attempt") void modelFailureHasNoRetryFallbackOrBankRedo() throws Exception {
        var first = harness(failure(() -> {}));
        assertThrows(RuntimeException.class, () -> first.call("Query.")); assertEquals(1, first.model.requests.size());
        var later = harness(tool("account_balance_query", Map.of()), failure(() -> {}));
        assertThrows(RuntimeException.class, () -> later.call("Query."));
        assertEquals(2, later.model.requests.size()); assertEquals(1, later.bank.customerReads());
    }
    @Test @Tag("provider-error") void providerExceptionCannotLeakAsNativeErrorText() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("MUST_NOT_SEND"));
        h.session.close();
        DescribedFinanceProvider broken = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() { return h.provider.descriptor(); }
            public List<Class<?>> artifactTypes() { return List.of(getClass(), NonAiBankingProvider.class); }
            public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new IllegalStateException("PRIVATE_PROVIDER_FAILURE"); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new UnsupportedOperationException(); }
        };
        h.open(broken);
        assertThrows(RuntimeException.class, () -> h.call("Query.")); assertEquals(1, h.model.requests.size()); assertEquals(0, h.bank.requests.size());
    }
    @Test @Tag("isolation-parent") void explicitAsyncParentsAndConcurrentSessionIsolation() throws Exception {
        var a = harness(tool("account_balance_query", Map.of()), text("A complete"));
        var b = harness(tool("account_balance_query", Map.of()), text("B complete"));
        CountDownLatch arrived = new CountDownLatch(2), release = new CountDownLatch(1);
        for (var h : List.of(a, b)) h.bank.beforeResponse = p -> { arrived.countDown(); await(release); };
        var fa = a.session.invoke(a.input("PRIVATE_A")).toFuture(); var fb = b.session.invoke(b.input("PRIVATE_B")).toFuture();
        assertTrue(arrived.await(10, TimeUnit.SECONDS));
        assertThrows(RuntimeException.class, () -> a.call("Concurrent same session.")); assertTrue(a.session.valid());
        release.countDown(); fa.get(15, TimeUnit.SECONDS); fb.get(15, TimeUnit.SECONDS);
        assertFalse(a.model.requests.toString().contains("PRIVATE_B")); assertFalse(b.model.requests.toString().contains("PRIVATE_A"));
        assertParents(a); assertParents(b);
        assertTrue(a.threads.stream().anyMatch(t -> t.get("component").equals("runtime-tool") && !t.get("thread").equals(Thread.currentThread().getName())));
        assertNull(AgentScopeObservationCollector.currentRun());
    }
    @Test @Tag("cancel-late") void cancellationInvalidatesLateResultsWithoutBankRetry() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("MUST_NOT_SEND"));
        CountDownLatch arrived = new CountDownLatch(1), release = new CountDownLatch(1);
        h.bank.beforeResponse = p -> { arrived.countDown(); await(release); };
        var future = h.session.invoke(h.input("Query.")).toFuture(); assertTrue(arrived.await(10, TimeUnit.SECONDS));
        future.cancel(true); release.countDown();
        assertFalse(h.session.valid()); assertThrows(RuntimeException.class, () -> h.call("Again."));
        Thread.sleep(200); assertEquals(1, h.model.requests.size()); assertEquals(1, h.bank.customerReads());
    }
    @Test @Tag("sink-degradation") void failedTelemetryDoesNotRedoBusiness() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("Done")); h.failSink = true;
        var reply = h.call("Query."); assertTrue(reply.droppedObservationEvents() > 0);
        assertEquals(CapabilityResultV1.Status.COMPLETED, reply.executions().getFirst().result().status());
        assertEquals(1, h.bank.customerReads()); assertEquals(2, h.model.requests.size());
    }
    @Test @Tag("mode-denial") void forbiddenModesAndQualificationMismatchFailBeforeInstallation() throws Exception {
        var h = harness(text("MUST_NOT_SEND"));
        for (String name : List.of("stream", "fallback", "pendingToolRecovery", "modelAttempts", "toolAttempts", "operation", "sdkVersion")) {
            var tree = HarnessJson.mapper().readTree(REFERENCE.resolve("support.json").toFile()).deepCopy();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) tree;
            if (Set.of("stream", "fallback", "pendingToolRecovery").contains(name)) object.put(name, true);
            else if (name.endsWith("Attempts")) object.put(name, 2); else object.put(name, "UNSUPPORTED");
            assertThrows(Exception.class, () -> HarnessJson.mapper().treeToValue(object, AgentScopeQueryAssembler.Support.class));
        }
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(REFERENCE.resolve("support.json"), "sha256:" + "0".repeat(64)));
        var wrong = new RuntimeQualification(List.of(), "wrong", "sha256:" + "0".repeat(64), "test-only://wrong");
        assertThrows(SecurityException.class, () -> prepare(h.provider, wrong)); assertEquals(0, h.model.requests.size());
    }
    @Test @Tag("required-observation") @Tag("lock-integrity") void requiredObservationAndLockIdentityAreReal() throws Exception {
        var h = harness(); assertEquals(ObservationAssessmentV01.Status.READY, h.prepared.observationAssessment().status());
        var frozen = h.session.lock(); Thread.sleep(5); assertSame(frozen, h.session.lock());
        assertTrue(h.prepared.assets().stream().anyMatch(a -> a.kind().equals("ASSEMBLY")));
        assertTrue(h.prepared.assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_QUALIFICATION")));
        assertTrue(h.prepared.assets().stream().anyMatch(a -> a.kind().equals("DOMAIN_PACK") && a.version().equals("0.1.3")));
        var required = new ObservationRequirementsV01(ObservationRequirementsV01.VERSION, "test-required", "0.1", List.of(
                new ObservationRequirementsV01.Requirement("missing.semantic", "0.1", ObservationRequirementsV01.Obligation.REQUIRED,
                        ObservationRequirementsV01.Boundary.MODEL_CLIENT, ObservationRequirementsV01.Content.METADATA, Set.of("at"), Set.of())));
        assertThrows(ObservationAssessmentV01.UnmetRequirements.class, () -> ObservationAssessmentV01.assess(required,
                List.of(AgentScopeObservationCollector.declaration(true)), h.prepared.capabilityIds()).requireReady());
        assertThrows(IllegalStateException.class, () -> h.prepared.lock(Set.of("shell")));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.openjiuwen.core.singleagent.BaseAgent"));
        var lock = HarnessJson.mapper().valueToTree(h.session.lock());
        assertEquals("finance-solution-lock.v1", lock.path("schemaVersion").asText());
        assertEquals("customer-self-service", lock.path("domainInstallations").get(0).path("profile").asText());
        assertEquals(2, lock.path("domainInstallations").get(0).path("capabilities").size());
        assertTrue(h.prepared.assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_TEMPLATE") && a.version().equals("2.0.3")));
        assertTrue(h.prepared.assets().stream().noneMatch(a -> a.version().equals("actual")));
    }
    @Test @Tag("profile-admission") void profileIdentityAndScopeRejectBeforeHttp() throws Exception {
        var h = harness();
        for (String field : List.of("version", "id", "source")) {
            var spec = (com.fasterxml.jackson.databind.node.ObjectNode) HarnessJson.mapper().readTree(REFERENCE.resolve("solution.json").toFile());
            ((com.fasterxml.jackson.databind.node.ObjectNode) spec.path("profileRef")).put(field, "unsupported");
            Path file = Files.createTempFile(ROOT.resolve("integrations/agentscope-java/target"), "profile-negative-", ".json");
            Files.write(file, HarnessJson.mapper().writeValueAsBytes(spec));
            assertThrows(IllegalArgumentException.class, () -> new AgentScopeQueryAssembler().prepare(ROOT, file,
                    REFERENCE.resolve("support.json"), Map.of(h.provider.descriptor().providerId(), h.provider), testAuthority(h.provider)));
        }
        assertThrows(IllegalArgumentException.class, () -> h.prepared.openSession(new AgentScopeQuerySession.Identity(h.principal,
                FinanceContextV1.PrincipalType.EMPLOYEE, h.subject, h.sessionId, "customer-self-service", "test", FinanceContextV1.AuthenticationLevel.STRONG, 1),
                h.grant::get, new ModelConnection(h.model.uri(), "controlled", "fixture", Duration.ofSeconds(5))));
        assertEquals(0, h.bank.requests.size()); assertEquals(0, h.model.requests.size());
    }
    @Test @Tag("declaration-admission") void declaredProfileScopeAndProviderArtifactAreNotLabels() throws Exception {
        var h = harness();
        for (String mismatch : List.of("profile-capabilities", "domain-profile", "connection-kind", "artifact", "provider-duplicate")) {
            var support = (com.fasterxml.jackson.databind.node.ObjectNode) HarnessJson.mapper().readTree(REFERENCE.resolve("support.json").toFile());
            var profile = (com.fasterxml.jackson.databind.node.ObjectNode) support.path("profiles").get(0);
            var provider = (com.fasterxml.jackson.databind.node.ObjectNode) support.path("providers").get(0);
            switch (mismatch) {
                case "profile-capabilities" -> profile.putArray("allowedCapabilityIds").add("not.installed.query");
                case "domain-profile" -> profile.putArray("domainProfiles").add("unrelated-profile");
                case "connection-kind" -> provider.put("connectionKind", "different-host-connection.v1");
                case "artifact" -> provider.put("artifactRef", "maven://example:wrong-provider:1.0");
                case "provider-duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) support.path("providers")).add(provider.deepCopy());
                default -> throw new AssertionError();
            }
            Path file = Files.createTempFile(ROOT.resolve("integrations/agentscope-java/target"), "compatibility-negative-", ".json");
            Files.write(file, HarnessJson.mapper().writeValueAsBytes(support));
            assertThrows(IllegalArgumentException.class, () -> new AgentScopeQueryAssembler().prepare(ROOT, REFERENCE.resolve("solution.json"), file,
                    Map.of(h.provider.descriptor().providerId(), h.provider), testAuthority(h.provider)));
        }
        assertThrows(IllegalArgumentException.class, () -> RuntimeInventory.requireProviderArtifact(h.provider, "maven://example:wrong-provider:1.0"));
        assertEquals(0, h.model.requests.size()); assertEquals(0, h.bank.requests.size());
    }
    @Test @Tag("qualification-admission") void qualificationRequiresNamedPassedEvidenceNotCounts() throws Exception {
        var h = harness();
        Path dir = Files.createTempDirectory(ROOT.resolve("integrations/agentscope-java/target"), "qualification-negative-");
        var artifacts = RuntimeInventory.inspect(List.of(h.provider));
        var collector = AgentScopeObservationCollector.declaration(true);
        var canonical = new TreeMap<String, Object>();
        var components = new TreeMap<String, String>();
        for (var artifact : artifacts) components.put(artifact.role(), artifact.coordinate() + "|" + artifact.version() + "|" + artifact.digest());
        canonical.put("artifacts", components); canonical.put("collectorDeclaration", collector.identityDigest());
        var bytes = HarnessJson.mapper().writeValueAsBytes(canonical);
        assertEquals("sha256:" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), RuntimeQualification.identity(artifacts, collector.identityDigest()));
        var checks = new ArrayList<RuntimeQualification.Check>();
        // Synthetic parser fixtures are deliberately not the evidence emitted for final qualification.
        for (String id : RuntimeQualification.REQUIRED_CHECKS) {
            List<RuntimeQualification.Evidence> refs = new ArrayList<>();
            for (int i = 0; i < (Set.of("compound", "multi-turn", "missing-data").contains(id) ? 3 : 1); i++) {
                Path raw = dir.resolve(id + i + ".json");
                Files.write(raw, HarnessJson.mapper().writeValueAsBytes(Map.of("test", "synthetic-parser-fixture", "status", "PASS", "checkIds", List.of(id))));
                refs.add(new RuntimeQualification.Evidence(raw.getFileName().toString(), AssetDigests.sha256(raw).substring(7)));
            }
            checks.add(new RuntimeQualification.Check(id, true, refs));
        }
        var proof = new RuntimeQualification.Proof("agentscope-query-qualification-evidence.v0.1", "agentscope", "2.0.3", "PASS", collector, artifacts, checks);
        Path proofFile = dir.resolve("proof.json"), reportFile = dir.resolve("qualification.json");
        java.util.function.Consumer<RuntimeQualification.Proof> write = value -> {
            try {
                Files.write(proofFile, HarnessJson.mapper().writeValueAsBytes(value));
                var c = new RuntimeQualification.Collector(collector.collectorId(), RuntimeQualification.identity(artifacts, collector.identityDigest()),
                        collector.capabilities().stream().map(ObservationCapabilitiesV01.Capability::semanticId).collect(java.util.stream.Collectors.toSet()), true,
                        List.of(new RuntimeQualification.Evidence("proof.json", AssetDigests.sha256(proofFile).substring(7))));
                Files.write(reportFile, HarnessJson.mapper().writeValueAsBytes(new RuntimeQualification.Report(RuntimeQualification.VERSION, List.of(c))));
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
        write.accept(proof);
        RuntimeQualification.load(reportFile, AssetDigests.sha256(reportFile)).requireCurrent(artifacts, collector.identityDigest());
        var incomplete = new ArrayList<>(checks); incomplete.removeLast();
        incomplete.add(new RuntimeQualification.Check("count-padding-not-required-id", true, checks.getFirst().evidence()));
        write.accept(new RuntimeQualification.Proof(proof.schemaVersion(), proof.family(), proof.sdkVersion(), proof.status(), collector, artifacts, incomplete));
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(reportFile, AssetDigests.sha256(reportFile)));
        var failed = new ArrayList<>(checks); failed.set(0, new RuntimeQualification.Check(checks.getFirst().id(), false, checks.getFirst().evidence()));
        write.accept(new RuntimeQualification.Proof(proof.schemaVersion(), proof.family(), proof.sdkVersion(), proof.status(), collector, artifacts, failed));
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(reportFile, AssetDigests.sha256(reportFile)));
        write.accept(proof); Files.writeString(dir.resolve(checks.getFirst().evidence().getFirst().file()), "tampered");
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(reportFile, AssetDigests.sha256(reportFile)));
    }
    @Test @Tag("diagnostic-boundaries") void bodyCaptureRequiresSeparateEnablementAndAuthorization() throws Exception {
        for (boolean enabled : List.of(false, true)) for (boolean authorized : List.of(false, true)) {
            var h = harness(tool("account_balance_query", Map.of()), text("Done"));
            var captures = new CopyOnWriteArrayList<Map<String, Object>>();
            var ordinary = h.input("Query.");
            var input = new AgentScopeQuerySession.Input(ordinary.text(), ordinary.requestId(), ordinary.traceId(), ordinary.businessTime(), ordinary.observation(), ordinary.parentId(),
                    new ModelDiagnostics(enabled, () -> authorized, (id, boundary, state, value) -> captures.add(Map.of("id", id, "boundary", boundary, "state", state, "body", value))));
            var result = h.session.invoke(input).block(Duration.ofSeconds(20));
            assertEquals(0, result.diagnosticFailures()); assertEquals(enabled && authorized ? 4 : 0, captures.size());
            assertEquals(2, h.model.requests.size()); assertEquals(1, h.bank.customerReads());
            assertTrue(captures.stream().allMatch(c -> Set.of("CLIENT_REQUEST_PREPARED", "PARSED_MODEL_RESPONSE").contains(c.get("boundary"))));
            assertFalse(captures.toString().contains("fixture-key"));
        }
        var h = harness(tool("account_balance_query", Map.of()), text("Done")); var ordinary = h.input("Query.");
        var failed = new AgentScopeQuerySession.Input(ordinary.text(), ordinary.requestId(), ordinary.traceId(), ordinary.businessTime(), ordinary.observation(), ordinary.parentId(),
                new ModelDiagnostics(true, () -> true, (id, boundary, state, value) -> { throw new IllegalStateException("CAPTURE_WRITE_FAILURE"); }));
        var reply = h.session.invoke(failed).block(Duration.ofSeconds(20)); assertEquals(4, reply.diagnosticFailures());
        assertEquals(CapabilityResultV1.Status.COMPLETED, reply.executions().getFirst().result().status()); assertEquals(1, h.bank.customerReads());
    }
    @Test @Tag("redirect-denial") void redirectNeverBypassesUrlOrRevokedAuthority() throws Exception {
        for (int status : List.of(307, 308)) for (boolean revoke : List.of(false, true)) {
            var target = harness(text("MUST_NOT_RECEIVE_BODY")); var origin = harness();
            origin.model.enqueue(redirect(status, target.model.uri().resolve("/redirect-target"), () -> { if (revoke) origin.grant.set(null); }));
            assertThrows(RuntimeException.class, () -> origin.call("PRIVATE_REDIRECT_PROMPT"));
            assertEquals(1, origin.model.requests.size()); assertEquals(0, target.model.requests.size());
            assertEquals(0, origin.bank.requests.size()); assertFalse(origin.session.valid());
            assertCleared(origin);
        }
    }
    @Test @Tag("bank-auth-poison") void bank401And403PoisonWithoutModelContinuation() throws Exception {
        for (int status : List.of(401, 403)) {
            var h = harness(tool("account_balance_query", Map.of()), text("MUST_NOT_SEND")); h.bank.accountStatus = status;
            assertThrows(RuntimeException.class, () -> h.call("Read balance."));
            assertEquals(1, h.bank.customerReads()); assertEquals(1, h.model.requests.size()); assertFalse(h.session.valid());
            assertCleared(h); h.bank.accountStatus = 200;
            assertThrows(RuntimeException.class, () -> h.call("Old generation cannot resume."));
            assertEquals(1, h.model.requests.size());
        }
    }
    @Test @Tag("terminal-cancel-cleanup") void cancelledStubbornProviderEventuallyLeavesNoMemoryOrSummary() throws Exception {
        var h = harness(tool("account_balance_query", Map.of()), text("MUST_NOT_SEND")); h.session.close();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), returned = new CountDownLatch(1);
        DescribedFinanceProvider stubborn = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() { return h.provider.descriptor(); }
            public List<Class<?>> artifactTypes() { return List.of(getClass(), NonAiBankingProvider.class); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new UnsupportedOperationException(); }
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                var result = h.provider.query(request); entered.countDown();
                boolean interrupted = false;
                while (true) try { if (release.await(10, TimeUnit.SECONDS)) break; throw new IllegalStateException("TEST_RELEASE_TIMEOUT"); }
                catch (InterruptedException ignored) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
                returned.countDown(); return result;
            }
        };
        h.open(stubborn); state(h).setSummary("PRIVATE_OLD_SUMMARY");
        var future = h.session.invoke(h.input("PRIVATE_CANCELLED_REQUEST")).toFuture();
        assertTrue(entered.await(10, TimeUnit.SECONDS)); assertTrue(future.cancel(true)); release.countDown();
        assertTrue(returned.await(10, TimeUnit.SECONDS));
        for (int i = 0; i < 100 && h.events.stream().noneMatch(e -> e.component().equals("runtime-tool") && e.phase() == ObservationEventV01.Phase.END); i++) Thread.sleep(10);
        assertTrue(h.events.stream().anyMatch(e -> e.component().equals("runtime-tool") && e.phase() == ObservationEventV01.Phase.END));
        assertCleared(h); assertEquals(1, h.model.requests.size()); assertEquals(1, h.bank.customerReads());
        assertThrows(RuntimeException.class, () -> h.call("Again."));
    }
    @Test @Tag("terminal-delivery-cleanup") void finalNativeResponseWriteCannotSurviveDeliveryRevocation() throws Exception {
        var h = harness(text("PRIVATE_TERMINAL_RESPONSE")); state(h).setSummary("PRIVATE_OLD_SUMMARY");
        var input = h.input("PRIVATE_INPUT");
        var diagnostic = new ModelDiagnostics(true, () -> true, (id, boundary, status, body) -> {
            if (boundary.equals("PARSED_MODEL_RESPONSE")) h.grant.set(null);
        });
        assertThrows(RuntimeException.class, () -> h.session.invoke(new AgentScopeQuerySession.Input(input.text(), input.requestId(), input.traceId(),
                input.businessTime(), input.observation(), input.parentId(), diagnostic)).block(Duration.ofSeconds(20)));
        assertCleared(h); assertEquals(1, h.model.requests.size());
    }
    static io.agentscope.core.state.AgentState state(Harness h) throws Exception {
        var field = AgentScopeQuerySession.class.getDeclaredField("agent"); field.setAccessible(true);
        var nativeAgent = (io.agentscope.core.ReActAgent) field.get(h.session);
        return nativeAgent.getAgentState(h.principal, h.sessionId);
    }
    static void assertCleared(Harness h) throws Exception {
        assertFalse(h.session.valid());
        for (int i = 0; i < 20; i++) {
            Thread.sleep(10);
            var actual = state(h);
            h.memoryChecks.add(Map.of("sample", i, "contextMessages", actual.contextMutable().size(), "summaryCharacters", actual.getSummary().length(), "valid", h.session.valid()));
            assertTrue(actual.contextMutable().isEmpty(), "Native context was written after invalidation");
            assertEquals("", actual.getSummary(), "Native summary survived invalidation");
        }
    }
    static void assertParents(Harness h) {
        var tools = h.events.stream().filter(e -> e.component().equals("runtime-tool")).map(ObservationEventV01::spanId).collect(java.util.stream.Collectors.toSet());
        var providers = h.events.stream().filter(e -> e.component().equals("capability")).toList();
        assertFalse(tools.isEmpty()); assertFalse(providers.isEmpty());
        assertTrue(providers.stream().allMatch(e -> tools.contains(e.parentSpanId())));
        var providerIds = providers.stream().map(ObservationEventV01::spanId).collect(java.util.stream.Collectors.toSet());
        assertTrue(h.events.stream().filter(e -> e.component().equals("provider-http")).allMatch(e -> providerIds.contains(e.parentSpanId())));
        assertTrue(h.events.stream().filter(e -> e.component().equals("runtime-tool")).allMatch(e -> "host-parent".equals(e.parentSpanId())));
    }
    static void await(CountDownLatch latch) { try { if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("fixture barrier timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    static RuntimeQualification testAuthority(DescribedFinanceProvider provider) throws Exception {
        return new RuntimeQualification(RuntimeInventory.inspect(List.of(provider)), AgentScopeObservationCollector.declaration(true).identityDigest(),
                "sha256:" + "0".repeat(64), "test-only://bootstrap-not-product-qualification");
    }
    static AgentScopeQueryAssembler.Prepared prepare(DescribedFinanceProvider provider, RuntimeQualification qualification) throws Exception {
        return new AgentScopeQueryAssembler().prepare(ROOT, REFERENCE.resolve("solution.json"), REFERENCE.resolve("support.json"),
                Map.of(provider.descriptor().providerId(), provider), qualification);
    }
    static final class Harness implements AutoCloseable {
        final String principal = "principal-" + UUID.randomUUID(), subject = "subject-" + UUID.randomUUID(), sessionId = "session-" + UUID.randomUUID();
        final FixtureBank bank; final ControlledModel model; final NonAiBankingProvider provider;
        final BoundFinanceProvider.Grant initialGrant;
        final AtomicReference<BoundFinanceProvider.Grant> grant;
        final AtomicReference<NonAiBankingProvider.HostSession> bankSession;
        final List<ObservationEventV01> events = new CopyOnWriteArrayList<>();
        final List<Map<String, String>> threads = new CopyOnWriteArrayList<>();
        final List<AgentScopeQuerySession.Reply> replies = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> memoryChecks = new CopyOnWriteArrayList<>();
        AgentScopeQueryAssembler.Prepared prepared; AgentScopeQuerySession session; boolean failSink;
        Harness(Response... responses) throws Exception {
            bank = new FixtureBank(ROOT); model = new ControlledModel(responses);
            initialGrant = new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.CUSTOMER, Set.of(subject), Set.of("customer-self-service"),
                    Set.of("account:read", "cashflow:read"), sessionId, "fixture-authority", Instant.now().plusSeconds(300));
            grant = new AtomicReference<>(initialGrant);
            bankSession = new AtomicReference<>(new NonAiBankingProvider.HostSession("fixture-token", subject, principal, sessionId, Instant.now().plusSeconds(300)));
            provider = new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(bank.uri(), Duration.ofSeconds(5), bankSession::get)
                    .observations(AgentScopeObservationCollector::currentRun));
            open(provider);
        }
        void open(DescribedFinanceProvider selected) throws Exception {
            prepared = prepare(selected, testAuthority(selected));
            session = prepared.openSession(new AgentScopeQuerySession.Identity(principal, FinanceContextV1.PrincipalType.CUSTOMER, subject, sessionId,
                    "customer-self-service", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, 1), grant::get,
                    new ModelConnection(model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10)));
        }
        AgentScopeQuerySession.Input input(String text) {
            String id = "request-" + UUID.randomUUID();
            var run = new ObservationRun(id, "test-boot", "isolated-host", event -> {
                if (failSink) throw new IllegalStateException("TEST_SINK_FAILURE");
                events.add(event); threads.add(Map.of("spanId", event.spanId(), "component", event.component(), "thread", Thread.currentThread().getName())); return true;
            });
            return new AgentScopeQuerySession.Input(text, id, "trace-" + UUID.randomUUID(),
                    new TrustedBusinessTimeV01(LocalDate.of(2026, 9, 20), ZoneId.of("Asia/Shanghai"), "test-clock"), run, "host-parent");
        }
        AgentScopeQuerySession.Reply call(String text) { var reply = session.invoke(input(text)).block(Duration.ofSeconds(20)); replies.add(reply); return reply; }
        public void close() { if (session != null) session.close(); model.close(); bank.close(); }
    }
}
