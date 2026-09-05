package io.github.bitlhk.finance.harness.examples.wealthkit;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import io.github.bitlhk.finance.harness.providers.employeeagent.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import io.github.bitlhk.finance.harness.wealth.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Reproducible native Agent -> Tool -> grant -> source -> mapping -> evidence path. */
public final class WealthKitApplication {
    public enum Binding { BANK_HTTP, EA_HTTP, SNAPSHOT }
    public enum Scenario { NORMAL, DENIED, EXPIRED_GRANT, WRONG_SUBJECT, MODEL_SELECTOR, MISSING_METADATA, MISSING_RISK, ZERO, BAD_RESPONSE }
    public record Configuration(String mode, FinanceContextV1.PrincipalType profile, Binding binding, Scenario scenario) {
        public Configuration {
            if (!"CONTROLLED".equals(mode)) throw new IllegalArgumentException("CLI supports CONTROLLED only; live sources require a trusted host binding adapter");
            Objects.requireNonNull(profile); Objects.requireNonNull(binding); Objects.requireNonNull(scenario);
            if ((binding == Binding.EA_HTTP) != (profile == FinanceContextV1.PrincipalType.EMPLOYEE)
                    || (profile != FinanceContextV1.PrincipalType.CUSTOMER && profile != FinanceContextV1.PrincipalType.EMPLOYEE))
                throw new IllegalArgumentException("Source profile mismatch; do not relax permissions for substitution tests");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root = root();
        Path config = args.length == 0 ? root.resolve("examples/solutions/wealth-facts-kit/customer-bank.json") : Path.of(args[0]);
        Configuration settings = WealthJson.mapper().readValue(config.toFile(), Configuration.class);
        Path output = root.resolve("examples/solutions/wealth-facts-kit/target/runs/" + settings.binding() + "-" + settings.scenario());
        Map<String, Object> report = run(root, settings, output);
        System.out.println(WealthJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Report: " + output.resolve("report.json"));
    }

    public static synchronized Map<String, Object> run(Path root, Configuration settings, Path output) throws Exception {
        long startedNanos = System.nanoTime(); Instant startedAt = Instant.now();
        Files.createDirectories(output);
        try (ControlledEndpoints endpoints = new ControlledEndpoints(root, settings.scenario())) {
            String session = "kit-session-" + UUID.randomUUID();
            String principal = settings.profile() == FinanceContextV1.PrincipalType.EMPLOYEE ? "employee-001" : "customer-login-001";
            String sourceCapability = settings.binding() == Binding.EA_HTTP ? EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY
                    : NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY;
            String sourceScope = settings.binding() == Binding.EA_HTTP ? "wealth:previsit:read" : "wealth:customer:read";
            Set<String> grants = settings.scenario() == Scenario.DENIED ? Set.of() : Set.of(WealthFactsProvider.SCOPE, sourceScope);
            Supplier<BoundFinanceProvider.Grant> grantSource = () -> new BoundFinanceProvider.Grant(principal, settings.profile(),
                    Set.of("customer-001"), Set.of("WEALTH_FACTS_DESCRIPTION"), grants, session, "fixture:host-authority",
                    settings.scenario() == Scenario.EXPIRED_GRANT ? Instant.now().minusSeconds(1) : Instant.now().plusSeconds(300));
            DescribedFinanceProvider raw = source(settings, endpoints, principal);
            var boundSource = new BoundFinanceProvider(raw, grantSource, Map.of(sourceCapability, Set.of(sourceScope)));
            boolean known = settings.scenario() != Scenario.MISSING_METADATA;
            var semantics = new WealthSourceSemantics("fixture-bank", "fixture:upstream-code-shaped-v1", "fixture:dictionary-v1",
                    known ? "FIXTURE_BANK_ASSETS" : null, known ? "CNY" : null, known ? "fixture-scale" : null,
                    known ? "assessment-2" : null, known ? ZoneId.of("Asia/Shanghai") : null, known);
            var mapped = new WealthFactsProvider(boundSource, sourceCapability, Set.of(sourceScope),
                    settings.binding() == Binding.EA_HTTP ? WealthFactsMapper.Format.EA : WealthFactsMapper.Format.MOBILE,
                    semantics, WealthFactsV1.SourceKind.CONTROLLED_FIXTURE, Clock.systemUTC());
            var provider = new BoundFinanceProvider(mapped, grantSource, Map.of(WealthFactsProvider.CAPABILITY, Set.of(WealthFactsProvider.SCOPE)));
            String agentId = "wealth-facts-kit-" + UUID.randomUUID();
            var agent = new ReActAgent(AgentCard.builder().id(agentId).name("Wealth facts kit").build());
            List<Object> nativeResults = new ArrayList<>();
            List<Map<String,Object>> nativeEvents = new ArrayList<>();
            agent.registerCallback(AgentCallbackEvent.AFTER_TOOL_CALL, context -> {
                if (context.getInputs() instanceof ToolCallInputs tool) {
                    nativeResults.add(tool.getToolResult());
                    nativeEvents.add(Map.of("hook", "AFTER_TOOL_CALL", "contextEvent", context.getEvent().name(), "agentId", agent.getCard().getId(),
                            "toolName", tool.getToolName(), "arguments", tool.getToolArgs(), "observedAt", Instant.now().toString()));
                }
            }, 0);
            agent.registerCallback(AgentCallbackEvent.AFTER_INVOKE, context -> nativeEvents.add(Map.of(
                    "hook", "AFTER_INVOKE", "contextEvent", context.getEvent().name(), "agentId", agent.getCard().getId(), "observedAt", Instant.now().toString())), 0);
            var modelConfig = ReActAgentConfig.builder().maxIterations(3)
                    .promptTemplate(List.of(Map.of("role", "system", "content", "Use the installed read-only wealth fact tool.")))
                    .build().configureModelClient("OpenAI", "fixture-not-a-secret", endpoints.base() + "/v1",
                            "controlled-scripted-model", true);
            agent.configure(modelConfig);
            var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults(principal, "customer-001", settings.profile(),
                    "wealth-kit", "WEALTH_FACTS_DESCRIPTION", FinanceContextV1.AuthenticationLevel.STRONG, grants, () -> grants);
            var assembler = new OpenJiuwenSolutionAssembler();
            try (var assembled = assembler.assemble(root, root.resolve("examples/solutions/wealth-facts-kit/solution.yaml"),
                    agent, Map.of(WealthFactsProvider.PROVIDER, provider), defaults, true, new TaskCheckpointStore.Memory(), session)) {
                assembled.writeLock(output.resolve("solution-lock.json"));
                if (!Runner.start()) throw new IllegalStateException("Native runtime failed to start");
                Object response;
                try { response = Runner.runAgent(agent, Map.of("query", "Describe my authorized assets, risk status and gaps.",
                        "conversation_id", session), session, null); }
                finally { Runner.release(session); Runner.stop(); }
                if (response == null || endpoints.modelCompletions.get() != 1 || nativeResults.size() != 1
                        || endpoints.toolMessages.size() != 1
                        || !String.valueOf(nativeResults.getFirst()).equals(endpoints.toolMessages.getFirst())
                        || nativeEvents.stream().noneMatch(e -> e.get("hook").equals("AFTER_INVOKE")))
                    throw new IllegalStateException("Native AgentLoop roundtrip incomplete: completions=" + endpoints.modelCompletions
                            + ", results=" + nativeResults.size() + ", messages=" + endpoints.toolMessages.size()
                            + ", events=" + nativeEvents.stream().map(e -> e.get("hook")).toList()
                            + ", resultType=" + (nativeResults.isEmpty() || nativeResults.getFirst() == null ? "none" : nativeResults.getFirst().getClass().getName()));
                boolean leaked = endpoints.modelInputs.toString().contains("PRIVATE_");
                if (leaked || endpoints.sourceWrites.get() != 0) throw new IllegalStateException("Read-only projection gate failed");
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("schemaVersion", "wealth-kit-run.v0.1"); report.put("configuration", settings);
                report.put("evidenceKind", "CONTROLLED_HTTP_AND_SCRIPTED_MODEL");
                report.put("agentId", agent.getCard().getId()); report.put("sessionRef", session);
                report.put("installedCapabilities", assembled.lock().installations());
                report.put("sourceProvider", raw.descriptor());
                report.put("sourceSemantics", semantics);
                report.put("buildInputSha256", digest(Files.readAllBytes(root.resolve("examples/solutions/wealth-facts-kit/solution.yaml"))));
                report.put("assetPinning", "CONTENT_OBSERVED_NOT_PINNED_CONTROLLED_TEMPLATE");
                report.put("lockSha256", digest(Files.readAllBytes(output.resolve("solution-lock.json"))));
                report.put("modelRequestCount", endpoints.modelRequests.get()); report.put("modelCompletionCount", endpoints.modelCompletions.get());
                report.put("sourceReadCount", endpoints.sourceReads.get()); report.put("sourceWriteCount", endpoints.sourceWrites.get());
                report.put("privateDataObservedByModel", leaked); report.put("toolResults", List.copyOf(nativeResults));
                report.put("nativeEvents", nativeEvents);
                report.put("streamCheck", "NOT_APPLICABLE_NON_STREAMING"); report.put("modelQuality", "NOT_EVALUATED_SCRIPTED_MODEL");
                report.put("liveAuthorization", "NOT_RUN"); report.put("realProviderSubstitution", "PENDING");
                report.put("independentHumanOnboarding", "PENDING");
                report.put("startedAt", startedAt); report.put("elapsedMillis", (System.nanoTime() - startedNanos) / 1_000_000);
                byte[] trace = WealthJson.mapper().writeValueAsBytes(endpoints.modelInputs);
                Files.write(output.resolve("controlled-model-trace.json"), trace);
                report.put("traceSha256", digest(trace));
                WealthJson.mapper().writerWithDefaultPrettyPrinter().writeValue(output.resolve("report.json").toFile(), report);
                return Map.copyOf(report);
            }
        }
    }

    private static DescribedFinanceProvider source(Configuration config, ControlledEndpoints endpoints, String principal) {
        if (config.binding() == Binding.BANK_HTTP) return new NonAiBankingProvider(
                NonAiBankingProvider.Config.simulator(endpoints.base(), "fixture-login", "fixture-not-a-secret"));
        if (config.binding() == Binding.EA_HTTP) {
            var identity = new EmployeeAgentRuntimeIdentity("fixture-runtime", "fixture-agent", "fixture-adoption");
            return new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(endpoints.base(), identity,
                    context -> "fixture-token", Duration.ofSeconds(5), () -> new EmployeeAgentIdentityBinding(principal,
                    identity, "fixture:adoption-owner", Instant.now().plusSeconds(300))));
        }
        return new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() { return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,
                    "controlled-snapshot", "0.1.0", List.of(new ProviderDescriptorV1.CapabilitySupport(
                    NonAiBankingProvider.WEALTH_CUSTOMER_CONTEXT_QUERY, CapabilityRequestV1.Operation.QUERY))); }
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                endpoints.sourceReads.incrementAndGet();
                return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                        CapabilityResultV1.Status.COMPLETED, "Controlled snapshot", WealthJson.mapper().convertValue(endpoints.mobile,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}), List.of(), List.of(),
                        "controlled-snapshot", Instant.parse("2026-09-01T00:00:00Z"), null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new UnsupportedOperationException("Read only"); }
        };
    }
    static Path root() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("capability-packs"))) root = root.getParent();
        return Objects.requireNonNull(root, "Run inside the Harness Git checkout");
    }
    static String digest(byte[] bytes) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
