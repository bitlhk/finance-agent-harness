package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenJiuwenSolutionAssemblerTest {
    @TempDir
    Path temporaryDirectory;

    @Test void methodOnlyInstallsRealSkillWithoutInventingProviderOrTool() throws Exception {
        Path solution = methodOnlySolution();
        var assembler = new OpenJiuwenSolutionAssembler();
        var prepared = assembler.prepare(temporaryDirectory, solution, Map.of());
        assertTrue(prepared.capabilityPacks().isEmpty());
        assertEquals("Method-only instructions.", prepared.domainPacks().getFirst().skills().getFirst().instructions());
        assertThrows(SecurityException.class, () -> prepared.openQueries(customerWealthDefaults(), true, "method-only")
                .query("account.balance.query", Map.of(), "uninstalled"));
        try (var installed = assembler.install(prepared, cleanAgent("method-only"), customerWealthDefaults(), true,
                new TaskCheckpointStore.Memory(), "method-only")) {
            var lock = new ObjectMapper().valueToTree(installed.lock());
            assertEquals(1, lock.path("domainInstallations").size());
            assertEquals(0, lock.path("installations").size());
            assertFalse(lock.toString().contains("PROVIDER_RUNTIME"));
        }
    }

    @Test void emptyPlanAndUndeclaredMethodRequirementsRemainRejected() throws Exception {
        var mapper = new ObjectMapper();
        Path solution = methodOnlySolution();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(solution.toFile());
        input.putArray("domainPackRefs");
        Files.writeString(solution, input.toString());
        Path emptySolution = solution;
        assertThrows(IllegalArgumentException.class, () -> new OpenJiuwenSolutionAssembler()
                .prepare(temporaryDirectory, emptySolution, Map.of()));
        solution = methodOnlySolution();
        Path domain = temporaryDirectory.resolve("method/pack.yaml");
        Files.writeString(domain, Files.readString(domain).replace("capabilities: []", "capabilities: [account.balance.query]"));
        Path missingCapability = solution;
        assertThrows(IllegalArgumentException.class, () -> new OpenJiuwenSolutionAssembler()
                .prepare(temporaryDirectory, missingCapability, Map.of()));
    }

    private Path methodOnlySolution() throws Exception {
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(root().resolve(
                "examples/solutions/wealth-facts-kit/solution.yaml").toFile());
        input.remove("profileRef"); input.remove("evaluationPackRef");
        input.putArray("capabilityPackRefs"); input.putArray("providerRefs"); input.putObject("capabilityBindings");
        input.putArray("domainPackRefs").addObject().put("id", "method-only").put("version", "0.1.0")
                .put("source", "repo://method");
        Files.createDirectories(temporaryDirectory.resolve("method"));
        Files.writeString(temporaryDirectory.resolve("method/SKILL.md"), "Method-only instructions.");
        Files.writeString(temporaryDirectory.resolve("method/pack.yaml"), """
                schemaVersion: finance-domain-pack.v1
                packId: method-only
                version: 0.1.0
                profile: shared-read-only
                capabilities: []
                skills:
                  - directory: method-only
                    entry: SKILL.md
                scope: []
                outOfScope: []
                """);
        Path path = temporaryDirectory.resolve("solution.json");
        Files.writeString(path, input.toString());
        return path;
    }

    @Test void enhancedDescriptionsAreDigestLockedAndMatchInstalledTools() throws Exception {
        var assembler = new OpenJiuwenSolutionAssembler();
        FinanceProvider provider = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() {
                return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, "wealth-facts-adapter", "0.1.0",
                        List.of(new ProviderDescriptorV1.CapabilitySupport("wealth.facts.describe",
                                CapabilityRequestV1.Operation.QUERY)));
            }
            public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError("no business call"); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new AssertionError("no task call"); }
        };
        var prepared = assembler.prepare(root(), root().resolve(
                "examples/solutions/wealth-facts-kit/solution.yaml"),
                Map.of("wealth-facts-adapter", provider));
        var enhanced = assembler.withCapabilityDescriptions(root(), prepared, List.of(root().resolve(
                "routing/capability-descriptions/wealth-facts-description.v0.1.json")), List.of(root().resolve(
                "routing/terminology/demo-bank-zh-cn.v0.1.json")));
        assertNotNull(enhanced.capabilityDescriptionSnapshot());
        assertEquals(Set.of("wealth.facts.describe"), enhanced.capabilityDescriptionSnapshot().capabilities()
                .stream().map(CapabilityDescriptionCatalogV01.SnapshotEntry::capabilityId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(enhanced.snapshot().containsKey("capabilityDescriptions"));
        assertTrue(enhanced.snapshot().containsKey("taskCollaboration"));
        @SuppressWarnings("unchecked")
        var capability = (Map<String, Object>) ((Map<String, Object>) ((List<?>) enhanced.snapshot()
                .get("capabilities")).getFirst()).get("definition");
        assertTrue(String.valueOf(capability.get("description")).contains("典型问法"));
        @SuppressWarnings("unchecked")
        var prompts = (List<String>) enhanced.snapshot().get("capabilityPrompts");
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("逐项核对用户当前请求")));
        assertTrue(prompts.stream().anyMatch(prompt -> prompt.contains("机构术语仅用于理解")));
        try (var installed = assembler.install(enhanced, cleanAgent("described"), customerWealthDefaults(), true,
                new TaskCheckpointStore.Memory(), "described-session")) {
            assertNotNull(installed.lock().capabilityDescriptions());
            assertEquals(2, installed.lock().assets().stream().filter(asset ->
                    asset.kind().equals("CAPABILITY_DESCRIPTIONS") || asset.kind().equals("TERMINOLOGY_PACK")).count());
        }
    }

    @Test void componentDisplayUsesPackAndProviderWithoutChangingLegacyPlanOrCallingBusiness() throws Exception {
        var source = new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel(
                "custom-source", "2.0", List.of(new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.OperationLabel(
                "facts", "GET", "/facts", "Custom source facts", "Explicit adapter-owned description")));
        var provider = new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() {
                return OpenJiuwenSolutionAssemblerTest.descriptor("non-ai-banking-reference", "wealth.customer-context.query", CapabilityRequestV1.Operation.QUERY);
            }
            public List<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel> observationLabels() { return List.of(source); }
            public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError("No business call for display"); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new AssertionError("No task call for display"); }
        };
        var bound = new io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider(provider,
                () -> { throw new AssertionError("Display must not request grants"); }, Map.of());
        var observed = new io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider(bound,
                "non-ai-banking-reference", () -> { throw new AssertionError("Display must not emit business events"); });
        assertEquals(List.of(source), bound.observationLabels());
        assertEquals(List.of(source), observed.observationLabels());
        var prepared = new OpenJiuwenSolutionAssembler().prepare(root(), root().resolve(
                "examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json"), Map.of("non-ai-banking-reference", observed));
        var before = prepared.snapshot();
        var labels = prepared.displayMetadata();
        assertEquals(prepared.capabilityPacks().getFirst().capabilities().getFirst().name(), labels.capabilities().getFirst().title());
        assertEquals(source, labels.sources().getFirst());
        assertEquals(before, prepared.snapshot());
        assertFalse(before.containsKey("displayMetadata"));
    }

    @Test
    void observationSidecarIsExplicitDigestLockedAndCheckedBeforeRegistration() throws Exception {
        var captured = new AtomicReference<CapabilityRequestV1>();
        var providers = Map.<String, FinanceProvider>of("non-ai-banking-reference",
                describedProvider("non-ai-banking-reference", "wealth.customer-context.query",
                        CapabilityRequestV1.Operation.QUERY, captured, Map.of()));
        var assembler = new OpenJiuwenSolutionAssembler();
        Path input = root().resolve("examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json");
        String source = "contracts/fixtures/observation/readonly-requirements.v0.1.json";
        String digest = "sha256:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(root().resolve(source))));
        var ref = new io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.Reference(
                "readonly-observation", "0.1.0", "repo://" + source, digest);
        var legacy = assembler.prepare(root(), input, providers);
        assertEquals("finance-installation-plan.v0.1", legacy.snapshot().get("schemaVersion"));
        assertFalse(legacy.snapshot().containsKey("observationAssessment"));
        assertEquals("LEGACY_UNASSESSED", legacy.observationAssessment().status().name());
        assertThrows(IllegalStateException.class, () -> assembler.prepare(root(), input, providers, ref, List.of()));
        var descriptor = new io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01(
                "finance-observation-capabilities.v0.1", "test-archive", "0.1", List.of(
                new io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.Capability(
                        "execution.history", "0.1",
                        io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.Boundary.HOST,
                        Set.of("requestId", "outcome"),
                        io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.Support.SUPPORTED,
                        io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.Verification.VERIFIED,
                        List.of("fixture:assembler-only-not-host-acceptance"),
                        io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.Availability.ENABLED,
                        io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.Authorization.NOT_REQUIRED)));
        var prepared = assembler.prepare(root(), input, providers, ref, List.of(descriptor));
        assertEquals("finance-installation-plan.v0.2", prepared.snapshot().get("schemaVersion"));
        assertEquals("READY", prepared.observationAssessment().status().name());
        var assets = new ObjectMapper().valueToTree(prepared.snapshot().get("assets"));
        assertTrue(assets.toString().contains(digest));
        assertTrue(assets.toString().contains("OBSERVATION_REQUIREMENTS"));
        assertNull(captured.get(), "Observation preparation must not read any bank data");
        assertThrows(IllegalArgumentException.class, () -> assembler.prepare(root(), input, providers,
                new io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.Reference(
                        "readonly-observation", "0.1.0", "repo://" + source, "sha256:" + "0".repeat(64)), List.of(descriptor)));
    }

    @Test
    void preparedQueryUsesFrozenDefinitionsAndSameBindingWithoutJavaAgent() throws Exception {
        var captured = new AtomicReference<CapabilityRequestV1>();
        String capability = "wealth.customer-context.query";
        var provider = describedProvider("non-ai-banking-reference", capability, CapabilityRequestV1.Operation.QUERY,
                captured, Map.of("amount", "90071992547409.91", "currency", "CNY"));
        var providers = new java.util.HashMap<String, FinanceProvider>();
        providers.put("non-ai-banking-reference", provider);
        var assembler = new OpenJiuwenSolutionAssembler();
        var prepared = assembler.prepare(root(), root().resolve(
                "examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json"), providers);
        assertNull(captured.get());
        assertNull(Runner.resourceMgr().getTool("wealth_customer_context_query"));
        assertThrows(UnsupportedOperationException.class, () -> prepared.capabilityPacks().clear());
        assertThrows(UnsupportedOperationException.class, () -> prepared.capabilityPacks().getFirst()
                .capabilities().getFirst().inputSchema().put("additionalProperties", true));
        assertThrows(UnsupportedOperationException.class, () -> prepared.domainPacks().getFirst().skills().clear());
        providers.clear();
        var queries = prepared.openQueries(customerWealthDefaults(), true, "frozen-session");
        assertThrows(SecurityException.class, () -> queries.query("account.balance.query", Map.of(), "not-installed"));
        assertThrows(IllegalArgumentException.class, () -> queries.query(capability, Map.of("principal", "fake"), "invalid"));
        assertNull(captured.get());
        var result = queries.query(capability, Map.of(), "frozen-query");
        assertEquals("90071992547409.91", result.data().get("amount"));
        assertEquals("frozen-session", captured.get().context().sessionRef());
        var direct = assembler.install(prepared, cleanAgent("prepared"), customerWealthDefaults(), true,
                new TaskCheckpointStore.Memory(), "direct-session");
        try {
            assertEquals(new ObjectMapper().valueToTree(direct.lock().assets()),
                    new ObjectMapper().valueToTree(prepared.snapshot().get("assets")));
        } finally { direct.close(); }
    }

    @Test
    void readOnlyPlanRejectsTasksAndInsufficientGrants() throws Exception {
        var assembler = new OpenJiuwenSolutionAssembler();
        var taskPlan = assembler.prepare(root(), root().resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml"),
                Map.of("non-ai-banking-reference", provider()));
        assertThrows(IllegalArgumentException.class, () -> taskPlan.openQueries(defaults(), true, "task-session"));
        var captured = new AtomicReference<CapabilityRequestV1>();
        var plan = assembler.prepare(root(), root().resolve("examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json"),
                Map.of("non-ai-banking-reference", describedProvider("non-ai-banking-reference", "wealth.customer-context.query",
                        CapabilityRequestV1.Operation.QUERY, captured, Map.of())));
        var denied = plan.openQueries(defaults(), true, "no-wealth-grant").query("wealth.customer-context.query", Map.of(), "denied");
        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, denied.status());
        assertNull(captured.get(), "A balance grant cannot authorize wealth");
    }

    @Test
    void prepareRejectsDuplicateCapabilityAndToolAcrossPacks() throws Exception {
        var mapper = new ObjectMapper();
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        Path original = root().resolve("examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json");
        for (boolean sameId : List.of(true, false)) {
            Path assets = temporaryDirectory.resolve("duplicates-" + sameId); Files.createDirectories(assets);
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(original.toFile());
            input.remove("profileRef"); input.remove("evaluationPackRef"); input.putArray("domainPackRefs");
            ((com.fasterxml.jackson.databind.node.ObjectNode) input.path("providerRefs").get(0))
                    .put("source", "maven://io.github.bitlhk.finance:finance-provider-non-ai-banking:0.1.0-SNAPSHOT");
            var refs = input.putArray("capabilityPackRefs");
            for (String packId : List.of("first-pack", "second-pack")) {
                var pack = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(root().resolve("capability-packs/wealth-customer-context/pack.yaml").toFile());
                pack.put("packId", packId);
                if (!sameId && packId.equals("second-pack"))
                    ((com.fasterxml.jackson.databind.node.ObjectNode) pack.path("capabilities").get(0)).put("capabilityId", "other.facts.query");
                yaml.writeValue(assets.resolve(packId + ".yaml").toFile(), pack);
                refs.addObject().put("id", packId).put("version", "0.1.0").put("source", "repo://" + packId + ".yaml");
            }
            mapper.writeValue(assets.resolve("solution.json").toFile(), input);
            var provider = new DescribedFinanceProvider() {
                public ProviderDescriptorV1 descriptor() { return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,
                    "non-ai-banking-reference", "0.1.0-SNAPSHOT", List.of(
                    new ProviderDescriptorV1.CapabilitySupport("wealth.customer-context.query", CapabilityRequestV1.Operation.QUERY),
                    new ProviderDescriptorV1.CapabilitySupport("other.facts.query", CapabilityRequestV1.Operation.QUERY))); }
                public CapabilityResultV1 query(CapabilityRequestV1 r) { throw new AssertionError("no business call"); }
                public CapabilityResultV1 executeTask(CapabilityRequestV1 r) { throw new AssertionError("no task"); }
            };
            var error = assertThrows(IllegalArgumentException.class, () -> new OpenJiuwenSolutionAssembler().prepare(
                    assets, assets.resolve("solution.json"), Map.of("non-ai-banking-reference", provider)));
            assertTrue(error.getMessage().contains(sameId ? "more than one installed pack" : "Duplicate installed toolName"), error.getMessage());
        }
    }

    @org.junit.jupiter.api.TestFactory
    List<org.junit.jupiter.api.DynamicTest> existingEmployeeBuilderInputsInstallWithoutBusinessCalls() {
        return List.of("insurance-advisor-previsit", "wealth-manager-previsit").stream().map(scenario ->
                org.junit.jupiter.api.DynamicTest.dynamicTest(scenario, () -> {
            Path inputFile = root().resolve("examples/solutions/" + scenario + "/solution-build-input.v1.json");
            JsonNode input = new ObjectMapper().readTree(inputFile.toFile());
            String providerId = input.path("providerRefs").get(0).path("id").asText();
            boolean insurance = scenario.startsWith("insurance");
            String capability = insurance ? "insurance.customer-context.query" : "wealth.previsit-context.query";
            String tool = insurance ? "insurance_customer_context_query" : "wealth_previsit_context_query";
            var captured = new AtomicReference<CapabilityRequestV1>();
            var agent = cleanAgent("builder-input-" + scenario);
            try (var assembled = new OpenJiuwenSolutionAssembler().assemble(root(), inputFile, agent,
                    Map.of(providerId, describedProvider(providerId, capability, CapabilityRequestV1.Operation.QUERY, captured, Map.of())),
                    insurance ? insuranceDefaults() : employeeDefaults(), true)) {
                assertNotNull(agent.getAbilityManager().get(tool));
                assertEquals(input.path("runtime").path("templateRef").asText(), assembled.lock().runtime().templateRef());
                assertEquals(input.path("runtime").path("integrationRef").asText(), assembled.lock().runtime().integrationRef());
                var lockFile = temporaryDirectory.resolve(scenario + "-lock.json");
                assembled.writeLock(lockFile);
                var lock = new ObjectMapper().readTree(lockFile.toFile());
                assertEquals(input.path("runtime").path("resolvedArtifacts"), lock.path("runtime").path("resolvedArtifacts"));
                assertTrue(assembled.lock().assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_TEMPLATE")
                        && a.id().equals(lock.at("/runtime/resolvedArtifacts/templateRef").asText())));
                assertTrue(assembled.lock().assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_INTEGRATION")
                        && a.id().equals(lock.at("/runtime/resolvedArtifacts/integrationRef").asText())));
                assertEquals(insurance ? "repo://integrations/openjiuwen-java" : "repo://integrations/employee-agent-java",
                        lock.path("runtime").path("integrationRef").asText());
                if (!insurance) assertTrue(lock.path("runtime").path("templateRef").asText().contains("#agents/domain-runtime"));
                assertNull(captured.get(), "Assembly must not query the source");
            }
            assertNull(agent.getAbilityManager().get(tool));
        })).toList();
    }

    @Test
    void customerWealthBuilderInputInstallsRequiredCapabilityWithoutBusinessCalls() throws Exception {
        Path inputFile = root().resolve(
                "examples/solutions/wealth-customer-diagnosis/solution-build-input.v1.json");
        JsonNode input = new ObjectMapper().readTree(inputFile.toFile());
        String providerId = input.path("providerRefs").get(0).path("id").asText();
        var captured = new AtomicReference<CapabilityRequestV1>();
        var agent = cleanAgent("builder-input-wealth-customer-diagnosis");

        try (var assembled = new OpenJiuwenSolutionAssembler().assemble(
                root(),
                inputFile,
                agent,
                Map.of(providerId, describedProvider(
                        providerId,
                        "wealth.customer-context.query",
                        CapabilityRequestV1.Operation.QUERY,
                        captured,
                        Map.of())),
                customerWealthDefaults(),
                true)) {
            assertNotNull(agent.getAbilityManager().get("wealth_customer_context_query"));
            assertTrue(assembled.lock().assets().stream().anyMatch(asset ->
                    asset.kind().equals("CAPABILITY_PACK")
                            && asset.id().equals("wealth-customer-context")));
            assertNull(captured.get(), "Assembly must not query the source");
        }

        assertNull(agent.getAbilityManager().get("wealth_customer_context_query"));
    }

    @Test
    void explicitResolutionCannotConcealWrongVersionsOrBeInferredFromASource() throws Exception {
        var json = new ObjectMapper();
        var original = root().resolve("examples/solutions/insurance-advisor-previsit/solution-build-input.v1.json");
        for (String change : List.of("missingResolution", "templateRef", "integrationRef", "declaredMaven", "unknownField")) {
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.toFile());
            var runtime = (com.fasterxml.jackson.databind.node.ObjectNode) input.get("runtime");
            if (change.equals("missingResolution")) runtime.remove("resolvedArtifacts");
            else if (change.equals("declaredMaven")) runtime.put("templateRef", "maven://com.openjiuwen:agent-core-java:999.999-does-not-exist");
            else if (change.equals("unknownField")) ((com.fasterxml.jackson.databind.node.ObjectNode)runtime.get("resolvedArtifacts")).put("trusted", true);
            else ((com.fasterxml.jackson.databind.node.ObjectNode)runtime.get("resolvedArtifacts"))
                        .put(change, "maven://wrong:artifact:999.999-does-not-exist");
            var file = temporaryDirectory.resolve(change + ".json"); json.writeValue(file.toFile(), input);
            var agent = cleanAgent("bad-resolution-" + change);
            var error = assertThrows(Exception.class, () -> new OpenJiuwenSolutionAssembler().assemble(root(), file, agent,
                    Map.of(), insuranceDefaults(), true));
            assertTrue(error.getMessage().contains(change.equals("missingResolution") ? "RUNTIME_ARTIFACT_RESOLUTION_REQUIRED"
                    : change.equals("unknownField") ? "trusted" : "RUNTIME_REFERENCE_MISMATCH"), error.toString());
            assertNull(agent.getAbilityManager().get("insurance_customer_context_query"));
        }
    }

    @Test
    void nonexistentRuntimeReferencesAreRejectedBeforeInstallingTools() throws Exception {
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = root().resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml");
        for (String field : List.of("templateRef", "integrationRef")) {
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(original.toFile());
            var runtime = (com.fasterxml.jackson.databind.node.ObjectNode) input.get("runtime");
            String ref = runtime.get(field).asText();
            runtime.put(field, ref.substring(0, ref.lastIndexOf(':') + 1) + "999.999-does-not-exist");
            Path candidate = temporaryDirectory.resolve(field + ".yaml");
            yaml.writeValue(candidate.toFile(), input);
            var agent = cleanAgent("wrong-runtime-" + field);
            try (var ignored = new OpenJiuwenSolutionAssembler().assemble(root(), candidate, agent,
                    Map.of("non-ai-banking-reference", provider()), defaults(), true)) {
                org.junit.jupiter.api.Assertions.fail("Invalid runtime was installed: " + field);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("RUNTIME_REFERENCE_MISMATCH"), expected.getMessage());
            }
            assertNull(agent.getAbilityManager().get("account_balance_query"));
        }
    }

    @Test
    void explicitWrongDigestsFailBeforeRegisteringAnyTools() throws Exception {
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        Path original = root().resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml");
        for (String ref : List.of("profileRef", "evaluationPackRef", "capabilityPackRefs", "providerRefs", "domainPackRefs")) {
            var input = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(original.toFile());
            com.fasterxml.jackson.databind.node.ObjectNode asset;
            if (ref.equals("domainPackRefs")) {
                asset = input.putArray(ref).addObject();
                asset.put("id", "wealth-employee-assisted");
                asset.put("version", "0.1.0");
                asset.put("source", "repo://domain-packs/wealth/employee-assisted");
            } else {
                asset = (com.fasterxml.jackson.databind.node.ObjectNode)
                        (input.get(ref).isArray() ? input.get(ref).get(0) : input.get(ref));
            }
            asset.put("digest", "sha256:" + "0".repeat(64));
            Path candidate = temporaryDirectory.resolve(ref + ".yaml");
            yaml.writeValue(candidate.toFile(), input);
            ReActAgent agent = new ReActAgent(AgentCard.builder().id("bad-digest-" + ref).name("Digest test").build());
            var error = assertThrows(IllegalArgumentException.class, () -> new OpenJiuwenSolutionAssembler().assemble(
                    root(), candidate, agent, Map.of("non-ai-banking-reference", provider()), defaults(), true));
            assertTrue(error.getMessage().contains("digest"), ref + ": " + error.getMessage());
            assertNull(agent.getAbilityManager().get("account_balance_query"));
        }
    }

    @Test
    void matchingDeclaredDigestIsAcceptedAndRuntimeBytesAreLocked() throws Exception {
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var source = root().resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml");
        var agent = new ReActAgent(AgentCard.builder().id("matching-digest").name("Digest test").build());
        String digest;
        try (var first = new OpenJiuwenSolutionAssembler().assemble(root(), source, agent,
                Map.of("non-ai-banking-reference", provider()), defaults(), true)) {
            digest = first.lock().assets().stream().filter(a -> a.kind().equals("CAPABILITY_PACK")).findFirst().orElseThrow().digest();
            assertTrue(first.lock().assets().stream().anyMatch(a -> a.kind().equals("PROVIDER_RUNTIME")));
        }
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) yaml.readTree(source.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) input.get("capabilityPackRefs").get(0)).put("digest", digest);
        Path candidate = temporaryDirectory.resolve("matching.yaml");
        yaml.writeValue(candidate.toFile(), input);
        try (var second = new OpenJiuwenSolutionAssembler().assemble(root(), candidate, agent,
                Map.of("non-ai-banking-reference", provider()), defaults(), true)) {
            assertTrue(second.lock().assets().stream().anyMatch(a -> a.digest().equals(digest)));
        }
    }

    @Test
    void solutionYamlInstallsPackAndWritesReproducibleAssetLock() throws Exception {
        Path root = root();
        ReActAgent cleanAgent = new ReActAgent(AgentCard.builder()
                .id("solution-assembly-agent")
                .name("Solution Assembly Agent")
                .description("Clean OpenJiuwen Agent assembled from solution.yaml")
                .build());
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        root.resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml"),
                        cleanAgent,
                        Map.of("non-ai-banking-reference", provider()),
                        defaults(),
                        true);
        try {
            assertEquals("openjiuwen-retail-banking-demo", solution.solutionId());
            assertNotNull(cleanAgent.getAbilityManager().get("account_balance_query"));
            assertNotNull(cleanAgent.getAbilityManager().get("transfer_internal_create"));
            assertEquals("openjiuwen", solution.lock().runtime().family());
            assertEquals(7, solution.lock().assets().size());
            assertTrue(solution.lock().assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_TEMPLATE")
                    && a.id().equals(solution.lock().runtime().templateRef()) && a.version().equals("0.1.15")));
            assertTrue(solution.lock().assets().stream().anyMatch(a -> a.kind().equals("RUNTIME_INTEGRATION")
                    && a.id().equals(solution.lock().runtime().integrationRef()) && a.version().equals("0.1.0-SNAPSHOT")));
            assertTrue(solution.lock().assets().getFirst().digest().matches("sha256:[a-f0-9]{64}"));
            assertEquals("retail-banking-basics",
                    solution.lock().installations().getFirst().packId());

            Path lockFile = temporaryDirectory.resolve("solution-lock.json");
            solution.writeLock(lockFile);
            JsonNode lock = new ObjectMapper().readTree(lockFile.toFile());
            assertEquals("finance-solution-lock.v1", lock.path("schemaVersion").asText());
            assertFalse(lock.path("runtime").has("resolvedArtifacts"), "Maven-only lock shape remains unchanged");
            assertEquals(4, lock.path("runtime").size());
            assertEquals("retail-banking-basics",
                    lock.path("installations").path(0).path("packId").asText());
            assertEquals("account.balance.query",
                    lock.path("installations").path(0).path("capabilities").path(0)
                            .path("capabilityId").asText());
        } finally {
            solution.close();
        }

        assertNull(cleanAgent.getAbilityManager().get("account_balance_query"));
        assertNull(cleanAgent.getAbilityManager().get("transfer_internal_create"));
    }

    @Test
    void wealthSolutionInstallsProfessionalSkillAndRequiredCapabilityTogether() throws Exception {
        Path root = root();
        AtomicReference<CapabilityRequestV1> captured = new AtomicReference<>();
        FinanceProvider wealthProvider = new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return OpenJiuwenSolutionAssemblerTest.descriptor(
                        "employee-agent-reference",
                        "wealth.previsit-context.query",
                        CapabilityRequestV1.Operation.QUERY);
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                captured.set(request);
                return completed(request, Map.of("previsitContext", Map.of(
                        "customerRef", "C005",
                        "readiness", Map.of("status", "READY"))));
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("wealth previsit is read-only");
            }
        };
        ReActAgent cleanAgent = new ReActAgent(AgentCard.builder()
                .id("wealth-solution-assembly-agent")
                .name("Wealth Solution Assembly Agent")
                .description("Clean Agent assembled with wealth assets")
                .build());
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        root.resolve("examples/solutions/openjiuwen-wealth-manager-previsit/solution.yaml"),
                        cleanAgent,
                        Map.of("employee-agent-reference", wealthProvider),
                        employeeDefaults(),
                        true);
        try {
            assertNotNull(cleanAgent.getAbilityManager().get("wealth_previsit_context_query"));
            assertTrue(cleanAgent.getSystemPromptBuilder().hasSection(
                    "finance-domain-pack:wealth-employee-assisted:wealth-previsit-diagnosis"));
            assertEquals(7, solution.lock().assets().size());
            assertEquals("wealth-employee-assisted",
                    solution.lock().domainInstallations().getFirst().packId());
            assertEquals("wealth-manager-previsit",
                    solution.lock().installations().getFirst().packId());

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) ((Tool) Runner.resourceMgr()
                    .getTool("wealth_previsit_context_query"))
                    .invoke(Map.of("customerRef", "C005"));
            assertEquals("COMPLETED", output.get("status"));
            assertNotNull(captured.get());
            assertEquals(FinanceContextV1.PrincipalType.EMPLOYEE,
                    captured.get().context().principalType());
            assertEquals("C005", captured.get().parameters().get("customerRef"));
        } finally {
            solution.close();
        }
        assertNull(cleanAgent.getAbilityManager().get("wealth_previsit_context_query"));
        assertFalse(cleanAgent.getSystemPromptBuilder().hasSection(
                "finance-domain-pack:wealth-employee-assisted:wealth-previsit-diagnosis"));
    }

    @Test
    void insuranceSolutionReusesAssemblerWithDifferentDomainCapabilityAndProvider() throws Exception {
        Path root = root();
        AtomicReference<CapabilityRequestV1> captured = new AtomicReference<>();
        FinanceProvider insuranceProvider = new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return OpenJiuwenSolutionAssemblerTest.descriptor(
                        "employee-agent-insurance-reference",
                        "insurance.customer-context.query",
                        CapabilityRequestV1.Operation.QUERY);
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                captured.set(request);
                return completed(request, Map.of("customerContext", Map.of(
                        "subjectId", "C-INS-001",
                        "status", "READY")));
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("insurance previsit is read-only");
            }
        };
        ReActAgent cleanAgent = cleanAgent("insurance-solution-assembly-agent");
        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        root.resolve("examples/solutions/openjiuwen-insurance-advisor-previsit/solution.yaml"),
                        cleanAgent,
                        Map.of("employee-agent-insurance-reference", insuranceProvider),
                        insuranceDefaults(),
                        true);
        try {
            assertNotNull(cleanAgent.getAbilityManager().get("insurance_customer_context_query"));
            assertTrue(cleanAgent.getSystemPromptBuilder().hasSection(
                    "finance-domain-pack:insurance-employee-assisted:insurance-previsit-diagnosis"));
            assertEquals("insurance-employee-assisted",
                    solution.lock().domainInstallations().getFirst().packId());

            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) ((Tool) Runner.resourceMgr()
                    .getTool("insurance_customer_context_query"))
                    .invoke(Map.of("customerRef", "C-INS-001"));
            assertEquals("COMPLETED", output.get("status"));
            assertEquals("C-INS-001", captured.get().parameters().get("customerRef"));
            assertEquals("INSURANCE_ADVISORY", captured.get().context().purpose());
        } finally {
            solution.close();
        }
        assertNull(cleanAgent.getAbilityManager().get("insurance_customer_context_query"));
        assertFalse(cleanAgent.getSystemPromptBuilder().hasSection(
                "finance-domain-pack:insurance-employee-assisted:insurance-previsit-diagnosis"));
    }

    @Test
    void riskReviewSolutionBindsEachCapabilityToItsDeclaredProvider() throws Exception {
        Path root = root();
        Path catalogResolvedInput = catalogResolvedRiskReviewInput();
        AtomicReference<CapabilityRequestV1> accountRequest = new AtomicReference<>();
        AtomicReference<CapabilityRequestV1> fraudRequest = new AtomicReference<>();
        FinanceProvider accountProvider = describedProvider(
                "non-ai-banking-reference",
                "account.balance.query",
                CapabilityRequestV1.Operation.QUERY,
                accountRequest,
                Map.of("accounts", List.of(Map.of("accountId", "acct-001"))));
        FinanceProvider fraudProvider = describedProvider(
                "fraud-mining-reference",
                "fraud.transfer-risk.assess",
                CapabilityRequestV1.Operation.QUERY,
                fraudRequest,
                Map.of("decision", "BLOCK", "reasonCode", "KNOWN_FRAUD_INCIDENT_MATCH"));
        ReActAgent cleanAgent = cleanAgent("risk-review-solution-assembly-agent");

        OpenJiuwenSolutionAssembler.AssembledSolution solution =
                new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        catalogResolvedInput,
                        cleanAgent,
                        Map.of(
                                "non-ai-banking-reference", accountProvider,
                                "fraud-mining-reference", fraudProvider),
                        riskReviewDefaults(),
                        true);
        try {
            assertNotNull(cleanAgent.getAbilityManager().get("account_balance_query"));
            assertNotNull(cleanAgent.getAbilityManager().get("fraud_transfer_risk_assess"));
            assertTrue(cleanAgent.getSystemPromptBuilder().hasSection(
                    "finance-domain-pack:risk-review-internal-control:transfer-risk-review"));

            @SuppressWarnings("unchecked")
            Map<String, Object> accountOutput = (Map<String, Object>) ((Tool) Runner.resourceMgr()
                    .getTool("account_balance_query"))
                    .invoke(Map.of("accountId", "acct-001"));
            @SuppressWarnings("unchecked")
            Map<String, Object> fraudOutput = (Map<String, Object>) ((Tool) Runner.resourceMgr()
                    .getTool("fraud_transfer_risk_assess"))
                    .invoke(Map.of("accountRef", "acct-001"));

            assertEquals("COMPLETED", accountOutput.get("status"));
            assertEquals("COMPLETED", fraudOutput.get("status"));
            assertEquals("account.balance.query", accountRequest.get().capabilityId());
            assertEquals("fraud.transfer-risk.assess", fraudRequest.get().capabilityId());
            assertEquals("multiple", solution.lock().installations().getFirst().providerId());
            Map<String, String> lockedBindings = solution.lock().installations().getFirst()
                    .capabilities().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            OpenJiuwenCapabilityInstaller.CapabilityBinding::capabilityId,
                            OpenJiuwenCapabilityInstaller.CapabilityBinding::providerId));
            assertEquals("non-ai-banking-reference", lockedBindings.get("account.balance.query"));
            assertEquals("fraud-mining-reference", lockedBindings.get("fraud.transfer-risk.assess"));
        } finally {
            solution.close();
        }

        assertNull(cleanAgent.getAbilityManager().get("account_balance_query"));
        assertNull(cleanAgent.getAbilityManager().get("fraud_transfer_risk_assess"));
    }

    private Path catalogResolvedRiskReviewInput() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(root().resolve(
                "examples/solutions/risk-review/solution-build-input.v1.json").toFile());
        JsonNode catalog = json.readTree(root().resolve("catalog/finance-assets.v1.json").toFile());
        JsonNode domain = java.util.stream.StreamSupport.stream(
                        catalog.path("domainPacks").spliterator(), false)
                .filter(asset -> asset.path("id").asText().equals("risk-review-internal-control"))
                .findFirst()
                .orElseThrow();
        var capabilityPackRefs = input.putArray("capabilityPackRefs");
        for (JsonNode dependency : domain.path("dependencies")) {
            if (!dependency.path("kind").asText().equals("capability-pack")) {
                continue;
            }
            JsonNode capabilityPack = java.util.stream.StreamSupport.stream(
                            catalog.path("capabilityPacks").spliterator(), false)
                    .filter(asset -> asset.path("id").asText().equals(dependency.path("id").asText())
                            && asset.path("version").asText().equals(dependency.path("version").asText()))
                    .findFirst()
                    .orElseThrow();
            capabilityPackRefs.addObject()
                    .put("id", capabilityPack.path("id").asText())
                    .put("version", capabilityPack.path("version").asText())
                    .put("source", capabilityPack.path("source").asText());
        }
        Path candidate = temporaryDirectory.resolve("catalog-resolved-risk-review.json");
        json.writeValue(candidate.toFile(), input);
        return candidate;
    }

    @Test
    void multipleProvidersRequireACompleteExplicitCapabilityBinding() throws Exception {
        Path root = root();
        String source = Files.readString(
                root.resolve("examples/solutions/openjiuwen-risk-review/solution.yaml"));
        Path solutionFile = temporaryDirectory.resolve("missing-capability-binding.yaml");
        Files.writeString(solutionFile, source.replace(
                "  fraud.transfer-risk.assess: fraud-mining-reference\n", ""));
        FinanceProvider accountProvider = describedProvider(
                "non-ai-banking-reference",
                "account.balance.query",
                CapabilityRequestV1.Operation.QUERY,
                new AtomicReference<>(),
                Map.of());
        FinanceProvider fraudProvider = describedProvider(
                "fraud-mining-reference",
                "fraud.transfer-risk.assess",
                CapabilityRequestV1.Operation.QUERY,
                new AtomicReference<>(),
                Map.of());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        solutionFile,
                        cleanAgent("risk-review-incomplete-binding"),
                        Map.of(
                                "non-ai-banking-reference", accountProvider,
                                "fraud-mining-reference", fraudProvider),
                        riskReviewDefaults(),
                        true));

        assertTrue(error.getMessage().contains("explicit binding"));
        assertTrue(error.getMessage().contains("fraud.transfer-risk.assess"));
    }

    @Test
    void rejectsProviderWithoutDescriptorBeforeInstallingTools() {
        Path root = root();
        ReActAgent cleanAgent = cleanAgent("undescribed-provider");
        FinanceProvider undescribed = new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return completed(request, Map.of());
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                return completed(request, Map.of());
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        root.resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml"),
                        cleanAgent,
                        Map.of("non-ai-banking-reference", undescribed),
                        defaults(),
                        true));

        assertTrue(error.getMessage().contains("described Provider"));
        assertNull(cleanAgent.getAbilityManager().get("account_balance_query"));
    }

    @Test
    void rejectsProviderThatDoesNotImplementEveryInstalledCapability() {
        Path root = root();
        ReActAgent cleanAgent = cleanAgent("incomplete-provider");
        FinanceProvider incomplete = new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return OpenJiuwenSolutionAssemblerTest.descriptor(
                        "non-ai-banking-reference",
                        "account.balance.query",
                        CapabilityRequestV1.Operation.QUERY);
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return completed(request, Map.of());
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                return completed(request, Map.of());
            }
        };

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new OpenJiuwenSolutionAssembler().assemble(
                        root,
                        root.resolve("examples/solutions/openjiuwen-retail-banking/solution.yaml"),
                        cleanAgent,
                        Map.of("non-ai-banking-reference", incomplete),
                        defaults(),
                        true));

        assertTrue(error.getMessage().contains("transfer.internal.create:TASK"));
        assertNull(cleanAgent.getAbilityManager().get("account_balance_query"));
    }

    private static FinanceProvider provider() {
        return new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return new ProviderDescriptorV1(
                        ProviderDescriptorV1.SCHEMA_VERSION,
                        "non-ai-banking-reference",
                        "0.1.0-SNAPSHOT",
                        List.of(
                                new ProviderDescriptorV1.CapabilitySupport(
                                        "account.balance.query", CapabilityRequestV1.Operation.QUERY),
                                new ProviderDescriptorV1.CapabilitySupport(
                                        "transfer.internal.create", CapabilityRequestV1.Operation.TASK)));
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return completed(request, Map.of("availableBalance", 1000.00));
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                return completed(request, Map.of("transactionId", "txn-001"));
            }
        };
    }

    private static ReActAgent cleanAgent(String id) {
        return new ReActAgent(AgentCard.builder()
                .id(id)
                .name(id)
                .description("Assembler validation test")
                .build());
    }

    private static ProviderDescriptorV1 descriptor(
            String providerId,
            String capabilityId,
            CapabilityRequestV1.Operation operation) {
        return new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION,
                providerId,
                "0.1.0-SNAPSHOT",
                List.of(new ProviderDescriptorV1.CapabilitySupport(capabilityId, operation)));
    }

    private static CapabilityResultV1 completed(
            CapabilityRequestV1 request,
            Map<String, Object> data) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                "操作完成。",
                data,
                List.of(),
                List.of(),
                "reference-bank",
                Instant.parse("2026-09-03T02:00:00Z"),
                null,
                null);
    }

    private static FinanceProvider describedProvider(
            String providerId,
            String capabilityId,
            CapabilityRequestV1.Operation operation,
            AtomicReference<CapabilityRequestV1> captured,
            Map<String, Object> data) {
        return new DescribedFinanceProvider() {
            @Override
            public ProviderDescriptorV1 descriptor() {
                return OpenJiuwenSolutionAssemblerTest.descriptor(providerId, capabilityId, operation);
            }

            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                captured.set(request);
                return completed(request, data);
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("test Provider is read-only");
            }
        };
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults defaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "customer-001",
                "customer-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "openjiuwen-clean-example",
                "RETAIL_BANKING",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("account:read", "transfer:create"));
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults customerWealthDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "customer-wealth-001",
                "customer-wealth-001",
                FinanceContextV1.PrincipalType.CUSTOMER,
                "openjiuwen-wealth-customer-example",
                "WEALTH_DIAGNOSIS",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:customer:read"));
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults employeeDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "employee-liwenhua",
                "customer-005",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-wealth-example",
                "WEALTH_PREVISIT",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:previsit:read"));
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults insuranceDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "employee-insurance-001",
                "customer-insurance-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-insurance-example",
                "INSURANCE_ADVISORY",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("insurance:customer:read"));
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults riskReviewDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "risk-reviewer-001",
                "customer-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-risk-review-example",
                "TRANSFER_RISK_REVIEW",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("account:read", "fraud-risk:read"));
    }

    private static Path root() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("capability-packs"))
                    && Files.isDirectory(current.resolve("integrations/openjiuwen-java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repository root not found");
    }
}
