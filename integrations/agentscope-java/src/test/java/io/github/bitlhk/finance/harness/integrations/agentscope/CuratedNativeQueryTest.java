package io.github.bitlhk.finance.harness.integrations.agentscope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel;
import io.github.bitlhk.finance.harness.providers.curatedmcp.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel.*;

/** Native Runtime + actual bounded MCP. This suite alone does not issue qualification. */
@EnabledIfSystemProperty(named = "curated.sourceConfig", matches = ".+")
@Timeout(60)
class CuratedNativeQueryTest {
    static final Path ROOT = AgentScopeQueryTest.ROOT;
    final List<Fixture> fixtures = new ArrayList<>();
    @RegisterExtension final TestWatcher evidence = new TestWatcher() {
        public void testSuccessful(ExtensionContext c) { write(c, "PASS", ""); }
        public void testFailed(ExtensionContext c, Throwable failure) { write(c, "FAIL", failure.toString()); }
        private void write(ExtensionContext c, String status, String failure) {
            try {
                Path dir = Path.of(System.getProperty("curated.evidenceDirectory")).resolve("agentscope");
                Files.createDirectories(dir);
                Files.write(dir.resolve(c.getRequiredTestMethod().getName() + "-" + UUID.randomUUID() + ".json"),
                    HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.ofEntries(
                        Map.entry("test", c.getDisplayName()), Map.entry("testMethod", c.getRequiredTestMethod().getName()),
                        Map.entry("status", status), Map.entry("failure", failure), Map.entry("checkIds", c.getTags()),
                        Map.entry("scope", "NATIVE_CURATED_COVERAGE_NOT_QUALIFICATION"), Map.entry("realModel", false),
                        Map.entry("sourceConfigDigest", System.getProperty("curated.sourceConfigSha256")),
                        Map.entry("runtimeArtifacts", fixtures.isEmpty() ? List.of() : RuntimeInventory.inspect(List.of(fixtures.getFirst().provider))),
                        Map.entry("sessions", sessionEvidence()))));
            } catch (Exception e) { throw new AssertionError(e); }
        }
        private List<Map<String, Object>> sessionEvidence() throws Exception {
            var rows = new ArrayList<Map<String, Object>>();
            for (var fixture : fixtures) rows.add(fixture.evidence());
            return rows;
        }
    };
    @AfterEach void close() { fixtures.forEach(Fixture::close); }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"customer,C005", "customer,C006", "wealth,C005", "wealth,C006"})
    @Tag("native-query")
    void explicitWealthCustomerHasMatchingFactsAndIsolatedContext(String pack, String customer) throws Exception {
        var f = fixture(pack);
        for (var tool : f.tools) {
            var args = new LinkedHashMap<>(f.args(tool));
            if (tool.selector().equals("customer_id")) args.put("synthetic_customer_id", customer);
            f.model.enqueue(tool(tool.toolName(), args));f.model.enqueue(text("Synthetic read; preserve gaps."));
            var reply = f.call("Read the explicitly selected synthetic customer " + customer);
            var result = reply.executions().getFirst().result();
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
            var expected = tool.selector().equals("customer_id") ? f.expected.path("_customerVariants").path(customer).path(tool.name()) : f.expected.path(tool.name());
            assertEquals(expected, HarnessJson.mapper().valueToTree(result.data().get("sourceResult")));
            assertFalse(f.model.requests.getLast().get("rawBody").toString().contains(customer.equals("C005") ? "C006" : "C005"));
        }
        assertEquals(f.tools.size() * 2, f.model.requests.size());
    }
    Fixture fixture(String pack, Response... responses) throws Exception {
        var f = new Fixture(pack, responses); fixtures.add(f); return f;
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "wealth", "insurance", "research", "audit", "transfer-risk", "post-loan"})
    @Tag("native-query")
    void selectedPackUsesActualMcpAndOnlyInstallsItsTools(String pack) throws Exception {
        var f = fixture(pack);
        assertEquals(f.tools.stream().map(CuratedTools.Tool::toolName).collect(java.util.stream.Collectors.toSet()), f.session.installedTools());
        for (var tool : f.tools) {
            f.model.enqueue(tool(tool.toolName(), f.args(tool))); f.model.enqueue(text("Read completed; not a business approval."));
            var reply = f.call("Read the declared synthetic resource.");
            assertEquals(1, reply.executions().size());
            assertFact(f, tool, reply.executions().getFirst().result());
            assertTrue(f.model.requests.getLast().get("rawBody").toString().contains("sourceResult"));
            assertEquals(0, reply.droppedObservationEvents());
        }
        assertEquals(f.tools.size() * 2, f.model.requests.size());
        var spans = f.events.stream().filter(e -> e.component().equals("runtime-tool")).map(ObservationEventV01::spanId).toList();
        var capabilities = f.events.stream().filter(e -> e.component().equals("capability")).toList();
        assertFalse(capabilities.isEmpty());
        assertTrue(capabilities.stream().allMatch(e -> spans.contains(e.parentSpanId())));
        var exchanges = f.events.stream().filter(e -> e.component().equals("provider-mcp")).toList();
        var capabilitySpans = capabilities.stream().map(ObservationEventV01::spanId).toList();
        assertFalse(exchanges.isEmpty());
        assertTrue(exchanges.stream().allMatch(e -> capabilitySpans.contains(e.parentSpanId())));
        var exchangeSpans = exchanges.stream().map(ObservationEventV01::spanId).toList();
        var http = f.events.stream().filter(e -> e.component().equals("provider-http")).toList();
        assertFalse(http.isEmpty());
        assertTrue(http.stream().allMatch(e -> exchangeSpans.contains(e.parentSpanId())));
        assertTrue(http.stream().anyMatch(e -> e.phase() == ObservationEventV01.Phase.END
                && e.dispatch() == ObservationEventV01.Dispatch.SENT && "200".equals(e.links().get("httpStatus"))));
        assertTrue(f.events.stream().noneMatch(e -> e.phase() == ObservationEventV01.Phase.END
                && e.outcome() == ObservationEventV01.Outcome.INCOMPLETE));
        assertTrue(f.session.valid());
    }

    @RepeatedTest(3) @Tag("compound") @Tag("native-continuation") void nativeContinuationPreservesBothResearchFactsAndMissingness() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of("reference", "DEMO001")),
                tool("curated_wind_financial_docs", Map.of("reference", "DEMO001")), text("Both sources, with missing facts."));
        var reply = f.call("Read both synthetic financial facts and documents.");
        assertEquals(2, reply.executions().size()); assertEquals(3, f.model.requests.size());
        for (int i = 0; i < 2; i++) assertFact(f, f.tools.get(i), reply.executions().get(i).result());
        var sent = f.model.requests.getLast().get("rawBody").toString();
        assertTrue(sent.contains("consensus")); assertTrue(sent.contains("DOC001"));
        assertTrue(sent.contains("missing"));
    }

    @Test @Tag("revoke-before-return") void revocationAfterSourceReturnBlocksNextModelRequest() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of("reference", "DEMO001")));
        f.session.close();
        var revoking = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() { return f.provider.descriptor(); }
            public List<Class<?>> artifactTypes() { return List.of(getClass(), CuratedMcpProvider.class); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 r) { throw new UnsupportedOperationException(); }
            public CapabilityResultV1 query(CapabilityRequestV1 r) {
                var result = f.provider.query(r); assertFact(f, f.tools.getFirst(), result);
                f.sourceReturns.add(result); f.grant.set(null); return result;
            }
        };
        f.open(revoking);
        assertThrows(RuntimeException.class, () -> f.call("Read financials."));
        assertEquals(1, f.sourceReturns.size()); assertEquals(1, f.model.requests.size());
        assertFalse(f.session.valid());
        assertThrows(RuntimeException.class, () -> f.call("Repeat the previous facts."));
        assertEquals(1, f.model.requests.size());
    }

    @Test void missingGrantAndUninstalledToolCannotReachProvider() throws Exception {
        var f = fixture("research", tool("curated_fraud_transfer_risk_assess", Map.of("reference", "bene-risk")));
        try { f.call("An uninstalled operation."); } catch (RuntimeException expected) { }
        assertFalse(f.session.valid()); assertEquals(1, f.model.requests.size());
        assertTrue(f.httpAttempts.isEmpty());
        assertTrue(f.events.stream().noneMatch(e -> e.component().equals("capability")));
        var denied = fixture("research", text("Must not reach model.")); denied.grant.set(null);
        assertThrows(RuntimeException.class, () -> denied.call("Read."));
        assertEquals(0, denied.model.requests.size());
        assertTrue(denied.httpAttempts.isEmpty());
    }

    @Test @Tag("model-single-attempt") void modelFailureDoesNotRetryOrStartSource() throws Exception {
        var f = fixture("research", failure(() -> {}));
        assertThrows(RuntimeException.class, () -> f.call("Read."));
        assertEquals(1, f.model.requests.size()); assertFalse(f.session.valid());
        assertTrue(f.httpAttempts.isEmpty());
        assertTrue(f.events.stream().noneMatch(e -> e.component().equals("capability")));
    }

    @RepeatedTest(3) @Tag("missing-data") void sourceUnknownRemainsUnknownInsteadOfBusinessSuccess() throws Exception {
        var f = fixture("transfer-risk", tool("curated_fraud_transfer_risk_assess", Map.of("reference", "bene-unknown")), text("The source decision is unknown."));
        var result = f.call("Read the synthetic unknown risk decision.").executions().getFirst().result();
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.data().get("sourceState")).get("decision"));
        assertTrue(f.model.requests.getLast().get("rawBody").toString().contains("UNKNOWN"));
    }

    @RepeatedTest(3) @Tag("multi-turn") void laterTurnReceivesEarlierFactsWithoutRepeatingItsTool() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of("reference", "DEMO001")), text("Financials read."),
                tool("curated_wind_financial_docs", Map.of("reference", "DEMO001")), text("Documents read."));
        assertFact(f, f.tools.getFirst(), f.call("FIRST_TURN_MARKER read financials.").executions().getFirst().result());
        assertFact(f, f.tools.getLast(), f.call("Now read the documents for that resource.").executions().getFirst().result());
        assertEquals(4, f.model.requests.size());
        String secondTurn = f.model.requests.get(2).get("rawBody").toString();
        assertTrue(secondTurn.contains("FIRST_TURN_MARKER")); assertTrue(secondTurn.contains("consensus"));
        assertTrue(secondTurn.contains("sourceResult"));
        assertEquals(2, f.events.stream().filter(e -> e.component().equals("capability") && e.phase() == ObservationEventV01.Phase.START).count());
    }

    @Test @Tag("tool-denial") void revokeAtToolBoundarySendsNoSourceRequest() throws Exception {
        var f = fixture("research"); var response = tool("curated_wind_stock_data", Map.of());
        f.model.enqueue(new Response(response.status(), response.body(), () -> f.grant.set(null)));
        assertThrows(RuntimeException.class, () -> f.call("Read."));
        assertEquals(1, f.model.requests.size()); assertTrue(f.httpAttempts.isEmpty()); assertCleared(f);
    }

    @Test @Tag("schema-denial") void invalidInputsAndUnknownToolsStopBeforeProvider() throws Exception {
        for (var response : List.of(tool("curated_wind_stock_data", Map.of("reference", 7)),
                tool("curated_wind_stock_data", Map.of("subjectRef", "another-owner")), tool("shell", Map.of()))) {
            var f = fixture("research", response, text("MUST_NOT_SEND"));
            assertThrows(RuntimeException.class, () -> f.call("Read."));
            assertEquals(1, f.model.requests.size()); assertTrue(f.httpAttempts.isEmpty()); assertCleared(f);
        }
    }

    @Test @Tag("provider-error") void throwingProviderDoesNotExposeItsErrorToModel() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of()));
        f.replace(r -> { throw new IllegalStateException("PRIVATE_PROVIDER_FAILURE"); });
        assertThrows(RuntimeException.class, () -> f.call("Read."));
        assertEquals(1, f.model.requests.size()); assertTrue(f.httpAttempts.isEmpty()); assertCleared(f);
        assertFalse(f.events.toString().contains("PRIVATE_PROVIDER_FAILURE"));
    }

    @Test @Tag("isolation-parent") void concurrentSessionsKeepHistoryAndParentsSeparate() throws Exception {
        var a = fixture("research", tool("curated_wind_stock_data", Map.of()), text("A done"));
        var b = fixture("research", tool("curated_wind_stock_data", Map.of()), text("B done"));
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        for (var f : List.of(a, b)) f.replace(r -> {
            var result = f.provider.query(r); entered.countDown(); AgentScopeQueryTest.await(release); return result;
        });
        var fa = a.session.invoke(a.input("PRIVATE_A")).toFuture(); var fb = b.session.invoke(b.input("PRIVATE_B")).toFuture();
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertThrows(RuntimeException.class, () -> a.call("Concurrent invocation."));
        } finally { release.countDown(); }
        fa.get(15, TimeUnit.SECONDS); fb.get(15, TimeUnit.SECONDS);
        assertFalse(a.model.requests.toString().contains("PRIVATE_B")); assertFalse(b.model.requests.toString().contains("PRIVATE_A"));
        for (var f : List.of(a, b)) {
            var ids = f.events.stream().filter(e -> e.component().equals("runtime-tool")).map(ObservationEventV01::spanId).toList();
            assertFalse(ids.isEmpty());
            assertTrue(f.events.stream().filter(e -> e.component().equals("capability")).allMatch(e -> ids.contains(e.parentSpanId())));
        }
        assertNull(AgentScopeObservationCollector.currentRun());
    }

    @Test @Tag("cancel-late") @Tag("terminal-cancel-cleanup") void cancelledLateSourceCannotWriteNativeMemory() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of()), text("MUST_NOT_SEND"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var returned = new CountDownLatch(1);
        f.replace(r -> {
            var result = f.provider.query(r); f.sourceReturns.add(result); entered.countDown();
            boolean interrupted = false;
            try {
                for (;;) try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("BARRIER_TIMEOUT"); break; }
                catch (InterruptedException e) { interrupted = true; }
                return result;
            } finally { if (interrupted) Thread.currentThread().interrupt(); returned.countDown(); }
        });
        state(f).setSummary("PRIVATE_SUMMARY");
        var pending = f.session.invoke(f.input("PRIVATE_CANCELLED_REQUEST")).toFuture();
        try { assertTrue(entered.await(10, TimeUnit.SECONDS)); assertTrue(pending.cancel(true)); }
        finally { release.countDown(); }
        assertTrue(returned.await(10, TimeUnit.SECONDS)); assertCleared(f);
        assertEquals(1, f.model.requests.size()); assertEquals(1, f.sourceReturns.size());
        assertThrows(RuntimeException.class, () -> f.call("Reuse."));
    }

    @Test @Tag("sink-degradation") void brokenSinkCannotFailOrRedoCompletedSourceRead() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of()), text("Done")); f.failSink = true;
        var reply = f.call("Read."); assertTrue(reply.droppedObservationEvents() > 0);
        assertEquals(1, reply.executions().size()); assertFact(f, f.tools.getFirst(), reply.executions().getFirst().result());
        assertEquals(2, f.model.requests.size());
    }

    @Test @Tag("mode-denial") void forbiddenExecutionModesCannotBeDeclaredAsSupported() throws Exception {
        var f = fixture("research");
        for (String name : List.of("stream", "fallback", "pendingToolRecovery", "modelAttempts", "toolAttempts", "operation", "sdkVersion")) {
            var value = (ObjectNode) HarnessJson.mapper().readTree(f.support.toFile());
            if (Set.of("stream", "fallback", "pendingToolRecovery").contains(name)) value.put(name, true);
            else if (name.endsWith("Attempts")) value.put(name, 2); else value.put(name, "UNSUPPORTED");
            assertThrows(Exception.class, () -> HarnessJson.mapper().treeToValue(value, AgentScopeQueryAssembler.Support.class));
        }
        assertTrue(f.model.requests.isEmpty()); assertTrue(f.httpAttempts.isEmpty());
    }

    @Test @Tag("required-observation") @Tag("lock-integrity") void observationAndInstalledToolLockRemainMandatory() throws Exception {
        var f = fixture("research");
        assertEquals(ObservationAssessmentV01.Status.READY, f.prepared.observationAssessment().status());
        assertSame(f.session.lock(), f.session.lock());
        assertThrows(IllegalStateException.class, () -> f.prepared.lock(Set.of("shell")));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.openjiuwen.core.singleagent.BaseAgent"));
        assertTrue(f.prepared.assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_QUALIFICATION")));
        assertTrue(f.prepared.assets().stream().anyMatch(a -> a.kind().equals("PROVIDER_RUNTIME") && a.version().equals(CuratedMcpProvider.VERSION)));
        var missing = new ObservationRequirementsV01(ObservationRequirementsV01.VERSION, "missing", "0.1", List.of(
                new ObservationRequirementsV01.Requirement("unimplemented", "0.1", ObservationRequirementsV01.Obligation.REQUIRED,
                        ObservationRequirementsV01.Boundary.PROVIDER, ObservationRequirementsV01.Content.METADATA, Set.of("at"), Set.of())));
        assertThrows(ObservationAssessmentV01.UnmetRequirements.class, () -> ObservationAssessmentV01.assess(missing,
                List.of(AgentScopeObservationCollector.declaration(true)), f.prepared.capabilityIds()).requireReady());
    }

    @Test @Tag("profile-admission") @Tag("declaration-admission") void invalidProfileAndProviderBindingsRejectBeforeHttp() throws Exception {
        var f = fixture("research");
        for (String mismatch : List.of("capabilities", "connection", "artifact", "duplicate", "profile")) {
            var support = (ObjectNode) HarnessJson.mapper().readTree(f.support.toFile());
            var profile = (ObjectNode) support.path("profiles").get(0); var provider = (ObjectNode) support.path("providers").get(0);
            switch (mismatch) {
                case "capabilities" -> profile.putArray("allowedCapabilityIds").add("not.installed");
                case "connection" -> provider.put("connectionKind", "unapproved.v1");
                case "artifact" -> provider.put("artifactRef", "maven://wrong:provider:1.0");
                case "duplicate" -> ((com.fasterxml.jackson.databind.node.ArrayNode) support.path("providers")).add(provider.deepCopy());
                case "profile" -> ((ObjectNode) profile.path("profile")).put("version", "not-installed");
            }
            Path file = Files.createTempFile(f.support.getParent(), "invalid-", ".json"); Files.write(file, HarnessJson.mapper().writeValueAsBytes(support));
            assertThrows(IllegalArgumentException.class, () -> new AgentScopeQueryAssembler().prepare(ROOT, f.solution, file,
                    Map.of(CuratedMcpProvider.ID, f.provider), AgentScopeQueryTest.testAuthority(f.provider)));
        }
        assertThrows(IllegalArgumentException.class, () -> f.prepared.openSession(new AgentScopeQuerySession.Identity(f.principal,
                FinanceContextV1.PrincipalType.EMPLOYEE, f.subject, f.sessionId, "synthetic-evaluation", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, 1),
                f.grant::get, new ModelConnection(f.model.uri(), "controlled", "fixture-key", Duration.ofSeconds(5))));
        assertTrue(f.model.requests.isEmpty()); assertTrue(f.httpAttempts.isEmpty());
    }

    @Test @Tag("qualification-admission") void unqualifiedEvidenceAndOtherProviderInventoryCannotBeUsed() throws Exception {
        var f = fixture("research"); var collector = AgentScopeObservationCollector.declaration(true);
        Path proofFile = f.solution.resolveSibling("invalid-proof.json"), reportFile = f.solution.resolveSibling("invalid-qualification.json");
        var artifacts = RuntimeInventory.inspect(List.of(f.provider));
        var proof = new RuntimeQualification.Proof("agentscope-query-qualification-evidence.v0.1", "agentscope", "2.0.3", "PASS", collector, artifacts, List.of());
        Files.write(proofFile, HarnessJson.mapper().writeValueAsBytes(proof));
        var entry = new RuntimeQualification.Collector(collector.collectorId(), RuntimeQualification.identity(artifacts, collector.identityDigest()),
                collector.capabilities().stream().map(ObservationCapabilitiesV01.Capability::semanticId).collect(java.util.stream.Collectors.toSet()), true,
                List.of(new RuntimeQualification.Evidence(proofFile.getFileName().toString(), AssetDigests.sha256(proofFile).substring(7))));
        Files.write(reportFile, HarnessJson.mapper().writeValueAsBytes(new RuntimeQualification.Report(RuntimeQualification.VERSION, List.of(entry))));
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(reportFile, AssetDigests.sha256(reportFile)));
        assertThrows(SecurityException.class, () -> RuntimeQualification.load(reportFile, "sha256:" + "0".repeat(64)));
        var incomplete = new RuntimeQualification(List.of(), collector.identityDigest(), "sha256:" + "0".repeat(64), "test-only://invalid");
        assertThrows(SecurityException.class, () -> new AgentScopeQueryAssembler().prepare(ROOT, f.solution, f.support,
                Map.of(CuratedMcpProvider.ID, f.provider), incomplete));
        assertTrue(f.model.requests.isEmpty()); assertTrue(f.httpAttempts.isEmpty());
    }

    @Test @Tag("diagnostic-boundaries") void snapshotsNeedSeparatePermissionAndStorageFailureIsNonfatal() throws Exception {
        for (boolean enabled : List.of(false, true)) for (boolean allowed : List.of(false, true)) {
            var f = fixture("research", tool("curated_wind_stock_data", Map.of()), text("Done"));
            var captures = new CopyOnWriteArrayList<Map<String, Object>>(); var input = f.input("Read.");
            var reply = f.session.invoke(new AgentScopeQuerySession.Input(input.text(), input.requestId(), input.traceId(), input.businessTime(), input.observation(), input.parentId(),
                    new ModelDiagnostics(enabled, () -> allowed, (id, boundary, status, body) -> captures.add(Map.of("boundary", boundary, "body", body)))))
                    .block(Duration.ofSeconds(20));
            assertEquals(enabled && allowed ? 4 : 0, captures.size()); assertEquals(0, reply.diagnosticFailures());
            assertFalse(captures.toString().contains("fixture-key")); assertEquals(2, f.model.requests.size());
        }
        var f = fixture("research", tool("curated_wind_stock_data", Map.of()), text("Done")); var input = f.input("Read.");
        var reply = f.session.invoke(new AgentScopeQuerySession.Input(input.text(), input.requestId(), input.traceId(), input.businessTime(), input.observation(), input.parentId(),
                new ModelDiagnostics(true, () -> true, (id, boundary, status, body) -> { throw new IllegalStateException("STORE_FAILED"); })))
                .block(Duration.ofSeconds(20));
        assertEquals(4, reply.diagnosticFailures()); assertFact(f, f.tools.getFirst(), reply.executions().getFirst().result());
        assertEquals(2, f.model.requests.size());
    }

    @Test @Tag("redirect-denial") void modelRedirectCannotForwardPromptOrBypassRevocation() throws Exception {
        for (int status : List.of(307, 308)) for (boolean revoke : List.of(false, true)) {
            var target = fixture("research", text("MUST_NOT_RECEIVE")); var origin = fixture("research");
            origin.model.enqueue(redirect(status, target.model.uri(), () -> { if (revoke) origin.grant.set(null); }));
            assertThrows(RuntimeException.class, () -> origin.call("PRIVATE_REDIRECT"));
            assertEquals(1, origin.model.requests.size()); assertTrue(target.model.requests.isEmpty());
            assertTrue(origin.httpAttempts.isEmpty()); assertCleared(origin);
        }
    }

    @Test @Tag("terminal-delivery-cleanup") void deliveryRevocationCannotLeaveNativeHistory() throws Exception {
        var f = fixture("research", text("PRIVATE_RESPONSE")); state(f).setSummary("PRIVATE_SUMMARY"); var input = f.input("Read.");
        assertThrows(RuntimeException.class, () -> f.session.invoke(new AgentScopeQuerySession.Input(input.text(), input.requestId(), input.traceId(), input.businessTime(), input.observation(), input.parentId(),
                new ModelDiagnostics(true, () -> true, (id, boundary, status, body) -> { if (boundary.equals("PARSED_MODEL_RESPONSE")) f.grant.set(null); })))
                .block(Duration.ofSeconds(20)));
        assertCleared(f); assertEquals(1, f.model.requests.size()); assertTrue(f.httpAttempts.isEmpty());
    }

    @Test @Tag("bank-auth-poison") void sourceHttp401And403InvalidateSessionWithoutModelContinuation() throws Exception {
        for (int status : List.of(401, 403)) {
            var f = fixture("research", tool("curated_wind_stock_data", Map.of()), text("MUST_NOT_SEND"));
            var count = new java.util.concurrent.atomic.AtomicInteger();
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                count.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("X-Finance-Source-Sha256", f.config.runtimePolicy().sourceIdentitySha256());
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                byte[] body = "{\"error\":\"controlled source refusal\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
            try {
                var config = new CuratedSourceConfig(f.config.schemaVersion(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"),
                        f.config.contractVersion(), f.config.toolSchemaPins(), f.config.subjects(), f.config.runtimePolicy());
                var identity = new ManagedProviderFactoryV01.SourceSession("synthetic", f.subject, f.principal, f.sessionId, Instant.now().plusSeconds(300));
                var denied = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                        Duration.ofSeconds(2), () -> identity, AgentScopeObservationCollector::currentRun, f.httpAttempts::add), config);
                f.replace(denied::query);
                assertThrows(RuntimeException.class, () -> f.call("Read."));
                assertEquals(1, count.get()); assertEquals(1, f.model.requests.size());
                assertTrue(f.httpAttempts.stream().anyMatch(a -> a.status() == status)); assertCleared(f);
                assertThrows(RuntimeException.class, () -> f.call("Reuse old generation."));
                assertEquals(1, f.model.requests.size());
            } finally { server.stop(0); }
        }
    }

    static io.agentscope.core.state.AgentState state(Fixture f) throws Exception {
        var field = AgentScopeQuerySession.class.getDeclaredField("agent"); field.setAccessible(true);
        return ((io.agentscope.core.ReActAgent) field.get(f.session)).getAgentState(f.principal, f.sessionId);
    }
    static void assertCleared(Fixture f) throws Exception {
        assertFalse(f.session.valid());
        for (int i = 0; i < 20; i++) {
            Thread.sleep(10); var state = state(f);
            f.memoryChecks.add(Map.of("contextMessages", state.contextMutable().size(), "summaryCharacters", state.getSummary().length()));
            assertTrue(state.contextMutable().isEmpty()); assertEquals("", state.getSummary());
        }
    }

    static void assertFact(Fixture f, CuratedTools.Tool tool, CapabilityResultV1 result) {
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), result.toString());
        var actual = HarnessJson.mapper().valueToTree(result.data().get("sourceResult"));
        assertEquals(f.expected.path(tool.name()), actual, tool.name());
        var execution = (Map<?, ?>) result.data().get("mcpExecution");
        assertEquals(tool.name(), execution.get("tool"));
        assertEquals(f.config.toolSchemaPins().get(tool.name()), execution.get("schemaSha256"));
        assertEquals(f.config.runtimePolicy().sourceIdentitySha256(), ((Map<?, ?>) result.data().get("sourceIdentity")).get("sha256"));
    }

    static final class Fixture implements AutoCloseable {
        final String principal = "curated-actor-" + UUID.randomUUID(), sessionId = UUID.randomUUID().toString();
        final String subject;
        final ControlledModel model;
        final CuratedSourceConfig config;
        final CuratedMcpProvider provider;
        final AtomicReference<BoundFinanceProvider.Grant> grant;
        final List<CuratedTools.Tool> tools;
        final JsonNode expected;
        final List<ObservationEventV01> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<ManagedProviderFactoryV01.HttpAttempt> httpAttempts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<AgentScopeQuerySession.Reply> replies = new ArrayList<>();
        final List<CapabilityResultV1> sourceReturns = new ArrayList<>();
        final List<Map<String, Object>> memoryChecks = new ArrayList<>();
        final Path solution, support;
        AgentScopeQuerySession session;
        AgentScopeQueryAssembler.Prepared prepared;
        boolean failSink;
        Fixture(String suffix, Response... responses) throws Exception {
            config = CuratedSourceConfig.load(Path.of(System.getProperty("curated.sourceConfig")), System.getProperty("curated.sourceConfigSha256"));
            assertEquals(1, config.subjects().size(), "Qualification requires one explicit synthetic IAM subject");
            subject = config.subjects().keySet().iterator().next();
            expected = HarnessJson.mapper().readTree(Files.readAllBytes(Path.of(System.getProperty("curated.expectedFacts"))));
            var packPath = ROOT.resolve("capability-packs/curated-synthetic-" + suffix + "/pack.yaml");
            var pack = HarnessJson.mapper().readTree(Files.readAllBytes(packPath));
            var toolList = new ArrayList<CuratedTools.Tool>();
            pack.path("capabilities").forEach(c -> toolList.add(CuratedTools.named(c.path("toolName").asText().substring("curated_".length()))));
            tools = List.copyOf(toolList);
            var scopes = tools.stream().map(CuratedTools.Tool::scope).collect(java.util.stream.Collectors.toSet());
            grant = new AtomicReference<>(new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.CUSTOMER,
                    Set.of(subject), Set.of("synthetic-evaluation"), scopes, sessionId, "isolated-test", Instant.now().plusSeconds(300)));
            var sourceSession = new ManagedProviderFactoryV01.SourceSession("synthetic-not-forwarded", subject, principal, sessionId, Instant.now().plusSeconds(300));
            provider = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                    Duration.ofSeconds(5), () -> sourceSession, AgentScopeObservationCollector::currentRun, httpAttempts::add), config);
            Path dir = Files.createTempDirectory(ROOT.resolve("integrations/agentscope-java/target"), "curated-controls-");
            var spec = (ObjectNode) HarnessJson.mapper().readTree(Files.readAllBytes(ROOT.resolve("integrations/agentscope-java/reference/solution.json")));
            spec.put("solutionId", "isolated-curated-" + suffix); spec.set("domainPackRefs", HarnessJson.mapper().valueToTree(List.of()));
            spec.set("capabilityPackRefs", HarnessJson.mapper().valueToTree(List.of(ref(pack.path("packId").asText(), pack.path("version").asText(), packPath))));
            // Isolated wealth validation does not admit the Pack to the managed catalog.
            var providerFile = ROOT.resolve(suffix.equals("wealth")
                    ? "integrations/agentscope-java/reference/curated-wealth-candidate-provider.json"
                    : "providers/curated-synthetic-mcp/assembly.v0.1.json");
            spec.set("providerRefs", HarnessJson.mapper().valueToTree(List.of(ref(CuratedMcpProvider.ID, CuratedMcpProvider.VERSION, providerFile))));
            solution = dir.resolve("solution.json"); Files.write(solution, HarnessJson.mapper().writeValueAsBytes(spec));
            var sup = (ObjectNode) HarnessJson.mapper().readTree(Files.readAllBytes(ROOT.resolve("integrations/agentscope-java/reference/support.json")));
            sup.set("descriptions", HarnessJson.mapper().valueToTree(List.of())); sup.set("terminology", HarnessJson.mapper().valueToTree(List.of()));
            var obs = dir.resolve("observations.json");
            Files.write(obs, HarnessJson.mapper().writeValueAsBytes(Map.of("schemaVersion", "finance-observation-requirements.v0.1",
                    "id", "isolated-curated", "version", "0.1.0", "requirements", AgentScopeObservationCollector.baseline())));
            sup.set("observationRequirements", HarnessJson.mapper().valueToTree(List.of(ref("isolated-curated", "0.1.0", obs))));
            sup.set("profiles", HarnessJson.mapper().valueToTree(List.of(Map.of("profile", spec.path("profileRef"),
                    "allowedCapabilityIds", tools.stream().map(CuratedTools.Tool::capabilityId).toList(), "domainProfiles", List.of(),
                    "principalTypes", List.of("CUSTOMER"), "purposes", List.of("synthetic-evaluation")))));
            var declaration = HarnessJson.mapper().readTree(Files.readAllBytes(providerFile));
            sup.set("providers", HarnessJson.mapper().valueToTree(List.of(Map.of("id", CuratedMcpProvider.ID, "version", CuratedMcpProvider.VERSION,
                    "artifactRef", declaration.path("artifactRef").asText(), "connectionKind", declaration.path("connectionKind").asText()))));
            support = dir.resolve("support.json"); Files.write(support, HarnessJson.mapper().writeValueAsBytes(sup));
            model = new ControlledModel(responses);
            try { open(provider); } catch (Exception e) { model.close(); throw e; }
        }
        static Map<String, String> ref(String id, String version, Path path) throws Exception {
            return Map.of("id", id, "version", version, "source", "repo://" + ROOT.relativize(path), "digest", AssetDigests.sha256(path));
        }
        void open(DescribedFinanceProvider selected) throws Exception {
            // Same test-only bootstrap as the existing SDK qualification probes. Never exported as admission.
            var qualification = AgentScopeQueryTest.testAuthority(selected);
            prepared = new AgentScopeQueryAssembler().prepare(ROOT, solution, support, Map.of(CuratedMcpProvider.ID, selected), qualification);
            session = prepared.openSession(new AgentScopeQuerySession.Identity(principal, FinanceContextV1.PrincipalType.CUSTOMER, subject, sessionId,
                    "synthetic-evaluation", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, 1), grant::get,
                    new ModelConnection(model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10)));
        }
        Map<String, Object> args(CuratedTools.Tool tool) {
            if (tool.selector().isEmpty()) return Map.of();
            String target = expected.path(tool.name()).at(tool.identityPointer()).asText();
            if (tool.selector().equals("customer_id")) return "BOUND_SUBJECT".equals(config.subjects().get(subject).customerSelection())
                    ? Map.of("customer_id", subject) : Map.of("customer_id", subject, "synthetic_customer_id", target);
            return Map.of(tool.selector(), target);
        }
        void replace(java.util.function.Function<CapabilityRequestV1, CapabilityResultV1> query) throws Exception {
            session.close();
            open(new DescribedFinanceProvider() {
                public ProviderDescriptorV1 descriptor() { return provider.descriptor(); }
                public List<Class<?>> artifactTypes() { return List.of(getClass(), CuratedMcpProvider.class); }
                public CapabilityResultV1 executeTask(CapabilityRequestV1 r) { throw new UnsupportedOperationException(); }
                public CapabilityResultV1 query(CapabilityRequestV1 r) { return query.apply(r); }
            });
        }
        AgentScopeQuerySession.Input input(String text) {
            String id = UUID.randomUUID().toString();
            var run = new ObservationRun(id, "isolated-boot", "curated-native-test", event -> {
                if (failSink) throw new IllegalStateException("SINK_FAILURE");
                events.add(event); return true;
            });
            return new AgentScopeQuerySession.Input(text, id, "trace-" + id,
                    new TrustedBusinessTimeV01(LocalDate.of(2026, 9, 5), ZoneId.of("Asia/Shanghai"), "fixed-synthetic-fixture"), run, "host-parent");
        }
        AgentScopeQuerySession.Reply call(String text) {
            var reply = session.invoke(input(text))
                    .block(Duration.ofSeconds(30)); replies.add(reply); return reply;
        }
        Map<String, Object> evidence() throws Exception {
            return Map.of("modelHttp", model.requests, "observations", events, "replies", replies, "sourceReturns", sourceReturns,
                    "installation", session.lock(), "runtimeArtifacts", RuntimeInventory.inspect(List.of(provider)), "httpAttempts", httpAttempts,
                    "terminalMemoryChecks", memoryChecks, "controls", Map.of("solution", solution.toString(), "support", support.toString()));
        }
        public void close() { if (session != null) session.close(); model.close(); }
    }
}
