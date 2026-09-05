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
            assertEquals(5, solution.lock().assets().size());
            assertTrue(solution.lock().assets().getFirst().digest().matches("sha256:[a-f0-9]{64}"));
            assertEquals("retail-banking-basics",
                    solution.lock().installations().getFirst().packId());

            Path lockFile = temporaryDirectory.resolve("solution-lock.json");
            solution.writeLock(lockFile);
            JsonNode lock = new ObjectMapper().readTree(lockFile.toFile());
            assertEquals("finance-solution-lock.v1", lock.path("schemaVersion").asText());
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
            assertEquals(5, solution.lock().assets().size());
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
                        root.resolve("examples/solutions/openjiuwen-risk-review/solution.yaml"),
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
                Set.of());
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults employeeDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "employee-liwenhua",
                "customer-005",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-wealth-example",
                "WEALTH_PREVISIT",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults insuranceDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "employee-insurance-001",
                "customer-insurance-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-insurance-example",
                "INSURANCE_ADVISORY",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
    }

    private static OpenJiuwenFinanceBridge.InvocationDefaults riskReviewDefaults() {
        return new OpenJiuwenFinanceBridge.InvocationDefaults(
                "risk-reviewer-001",
                "customer-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "openjiuwen-risk-review-example",
                "TRANSFER_RISK_REVIEW",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of());
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
