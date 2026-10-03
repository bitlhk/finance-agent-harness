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
                Path dir = Path.of(System.getProperty("curated.evidenceDirectory"));
                Files.createDirectories(dir);
                Files.write(dir.resolve(c.getRequiredTestMethod().getName() + "-" + UUID.randomUUID() + ".json"),
                    HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                        "test", c.getDisplayName(), "status", status, "failure", failure,
                        "scope", "NATIVE_CURATED_COVERAGE_NOT_QUALIFICATION", "realModel", false,
                        "sourceConfigDigest", System.getProperty("curated.sourceConfigSha256"),
                        "sessions", sessionEvidence())));
            } catch (Exception e) { throw new AssertionError(e); }
        }
        private List<Map<String, Object>> sessionEvidence() throws Exception {
            var rows = new ArrayList<Map<String, Object>>();
            for (var fixture : fixtures) rows.add(fixture.evidence());
            return rows;
        }
    };
    @AfterEach void close() { fixtures.forEach(Fixture::close); }
    Fixture fixture(String pack, Response... responses) throws Exception {
        var f = new Fixture(pack, responses); fixtures.add(f); return f;
    }

    @ParameterizedTest @ValueSource(strings = {"customer", "insurance", "research", "audit", "transfer-risk", "post-loan"})
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

    @RepeatedTest(3) void nativeContinuationPreservesBothResearchFactsAndMissingness() throws Exception {
        var f = fixture("research", tool("curated_wind_stock_data", Map.of("reference", "DEMO001")),
                tool("curated_wind_financial_docs", Map.of("reference", "DEMO001")), text("Both sources, with missing facts."));
        var reply = f.call("Read both synthetic financial facts and documents.");
        assertEquals(2, reply.executions().size()); assertEquals(3, f.model.requests.size());
        for (int i = 0; i < 2; i++) assertFact(f, f.tools.get(i), reply.executions().get(i).result());
        var sent = f.model.requests.getLast().get("rawBody").toString();
        assertTrue(sent.contains("consensus")); assertTrue(sent.contains("DOC001"));
        assertTrue(sent.contains("missing"));
    }

    @Test void revocationAfterSourceReturnBlocksNextModelRequest() throws Exception {
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

    @Test void modelFailureDoesNotRetryOrStartSource() throws Exception {
        var f = fixture("research", failure(() -> {}));
        assertThrows(RuntimeException.class, () -> f.call("Read."));
        assertEquals(1, f.model.requests.size()); assertFalse(f.session.valid());
        assertTrue(f.httpAttempts.isEmpty());
        assertTrue(f.events.stream().noneMatch(e -> e.component().equals("capability")));
    }

    @Test void sourceUnknownRemainsUnknownInsteadOfBusinessSuccess() throws Exception {
        var f = fixture("transfer-risk", tool("curated_fraud_transfer_risk_assess", Map.of("reference", "bene-unknown")), text("The source decision is unknown."));
        var result = f.call("Read the synthetic unknown risk decision.").executions().getFirst().result();
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        assertEquals("UNKNOWN", ((Map<?, ?>) result.data().get("sourceState")).get("decision"));
        assertTrue(f.model.requests.getLast().get("rawBody").toString().contains("UNKNOWN"));
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
        final String principal = "curated-actor", subject = "curated-subject", sessionId = UUID.randomUUID().toString();
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
        final Path solution, support;
        AgentScopeQuerySession session;
        Fixture(String suffix, Response... responses) throws Exception {
            config = CuratedSourceConfig.load(Path.of(System.getProperty("curated.sourceConfig")), System.getProperty("curated.sourceConfigSha256"));
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
            spec.set("capabilityPackRefs", HarnessJson.mapper().valueToTree(List.of(ref(pack.path("packId").asText(), "1.0.0", packPath))));
            var providerFile = ROOT.resolve("providers/curated-synthetic-mcp/assembly.v0.1.json");
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
            var prepared = new AgentScopeQueryAssembler().prepare(ROOT, solution, support, Map.of(CuratedMcpProvider.ID, selected), qualification);
            session = prepared.openSession(new AgentScopeQuerySession.Identity(principal, FinanceContextV1.PrincipalType.CUSTOMER, subject, sessionId,
                    "synthetic-evaluation", "isolated", FinanceContextV1.AuthenticationLevel.STRONG, 1), grant::get,
                    new ModelConnection(model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10)));
        }
        Map<String, Object> args(CuratedTools.Tool tool) {
            return Map.of(tool.selector(), tool.selector().equals("customer_id") ? subject : tool.defaultRef());
        }
        AgentScopeQuerySession.Reply call(String text) {
            String id = UUID.randomUUID().toString();
            var run = new ObservationRun(id, "isolated-boot", "curated-native-test", event -> { events.add(event); return true; });
            var reply = session.invoke(new AgentScopeQuerySession.Input(text, id, "trace-" + id,
                    new TrustedBusinessTimeV01(LocalDate.of(2026, 9, 5), ZoneId.of("Asia/Shanghai"), "fixed-synthetic-fixture"), run, "host-parent"))
                    .block(Duration.ofSeconds(30)); replies.add(reply); return reply;
        }
        Map<String, Object> evidence() throws Exception {
            return Map.of("modelHttp", model.requests, "observations", events, "replies", replies, "sourceReturns", sourceReturns,
                    "installation", session.lock(), "runtimeArtifacts", RuntimeInventory.inspect(List.of(provider)), "httpAttempts", httpAttempts);
        }
        public void close() { if (session != null) session.close(); model.close(); }
    }
}
