package io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.*;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Isolated reference executable, deliberately compiled outside the integration artifact. */
public final class IsolatedHost {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("inventory ROOT | controlled ROOT QUALIFICATION SHA256 OUT | model ROOT QUALIFICATION SHA256 OUT PROMPT...");
        Path root = Path.of(args[1]).toRealPath();
        if (args[0].equals("inventory")) {
            var provider = provider(URI.create("http://127.0.0.1:9"), "inventory", "inventory", "inventory");
            System.out.println(HarnessJson.mapper().writeValueAsString(Map.of("artifacts", RuntimeInventory.inspect(List.of(provider)),
                    "collector", AgentScopeObservationCollector.declaration(true)))); return;
        }
        if (!Set.of("controlled", "model").contains(args[0]) || args.length < 5) throw new IllegalArgumentException("INVALID_HOST_COMMAND");
        boolean controlled = args[0].equals("controlled");
        Path reference = root.resolve("integrations/agentscope-java/reference");
        Path solution = configuredFile(root, "FH_ISOLATED_SOLUTION", reference.resolve("solution.json"));
        Path support = configuredFile(root, "FH_ISOLATED_SUPPORT", reference.resolve("support.json"));
        Path out = Path.of(args[4]);
        if (Files.exists(out)) throw new IllegalArgumentException("OUTPUT_ALREADY_EXISTS");
        Files.createDirectories(out);
        String sessionId = "isolated-" + UUID.randomUUID(), principal = "fixture-customer", subject = "fixture-subject";
        String fixture = System.getenv().getOrDefault("FH_ISOLATED_FIXTURE", "normal");
        if (!Set.of("normal", "analysis-unavailable", "bank401", "wrong-subject", "revoke-before-return").contains(fixture)) throw new IllegalArgumentException("UNKNOWN_TEST_FIXTURE");
        try (var bank = new FixtureBank(root); var model = controlled ? new ControlledModel(ControlledModel.tool("account_balance_query", Map.of()), ControlledModel.text("12345.67 CNY, controlled source.")) : null) {
            bank.analysisUnavailable = fixture.equals("analysis-unavailable"); bank.accountStatus = fixture.equals("bank401") ? 401 : 200;
            var provider = provider(bank.uri(), principal, fixture.equals("wrong-subject") ? "different-subject" : subject, sessionId);
            var qualification = RuntimeQualification.load(Path.of(args[2]), args[3]);
            var prepared = new AgentScopeQueryAssembler().prepare(root, solution, support, Map.of(provider.descriptor().providerId(), provider), qualification);
            var identity = new AgentScopeQuerySession.Identity(principal, FinanceContextV1.PrincipalType.CUSTOMER, subject, sessionId,
                    "customer-self-service", "isolated-cli", FinanceContextV1.AuthenticationLevel.STRONG, 1);
            var grant = new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.CUSTOMER, Set.of(subject), Set.of("customer-self-service"),
                    Set.of("account:read", "cashflow:read"), sessionId, "isolated-test-authority", Instant.now().plusSeconds(900));
            var currentGrant = new java.util.concurrent.atomic.AtomicReference<>(grant);
            if (fixture.equals("revoke-before-return")) bank.beforeResponse = path -> { if (!path.endsWith("/readiness")) currentGrant.set(null); };
            var connection = new ModelConnection(controlled ? model.uri() : URI.create(required("FH_MODEL_BASE_URL")),
                    controlled ? "controlled" : required("FH_MODEL_NAME"), controlled ? "fixture-key" : required("FH_MODEL_API_KEY"), Duration.ofSeconds(45));
            var events = new CopyOnWriteArrayList<ObservationEventV01>(); var replies = new ArrayList<AgentScopeQuerySession.Reply>();
            try (var session = prepared.openSession(identity, currentGrant::get, connection)) {
                write(out.resolve("solution-lock.json"), session.lock()); write(out.resolve("observation-assessment.json"), prepared.observationAssessment());
                var prompts = controlled ? List.of("Query my authorized account balance.") : Arrays.asList(args).subList(5, args.length);
                if (prompts.isEmpty()) throw new IllegalArgumentException("MODEL_PROMPT_REQUIRED");
                for (String prompt : prompts) {
                    String request = "request-" + UUID.randomUUID();
                    var run = new ObservationRun(request, "isolated-cli-boot", sessionId, event -> { events.add(event); return true; });
                    try (var rootSpan = run.start(null, "host", "request", Map.of("harnessRequestId", request)); var scope = run.bindParent(rootSpan.id())) {
                        try {
                            var input = new AgentScopeQuerySession.Input(prompt, request, "trace-" + UUID.randomUUID(),
                                    new TrustedBusinessTimeV01(LocalDate.of(2026, 9, 20), ZoneId.of("Asia/Shanghai"), "fixed-reference-clock"), run, rootSpan.id());
                            replies.add(session.invoke(input).block(Duration.ofSeconds(120)));
                            rootSpan.finish(ObservationEventV01.Outcome.RETURNED, Map.of());
                        } catch (RuntimeException failure) {
                            rootSpan.finish(isDenied(failure) ? ObservationEventV01.Outcome.DENIED : ObservationEventV01.Outcome.ERROR, Map.of());
                            throw failure;
                        }
                    }
                }
                if (controlled && (model.requests.size() != 2 || bank.customerReads() != 1 || replies.getFirst().executions().size() != 1
                        || replies.getFirst().executions().getFirst().result().status() != CapabilityResultV1.Status.COMPLETED)) throw new AssertionError("CONTROLLED_HOST_RESULT_MISMATCH");
                var spanIds = events.stream().filter(e -> e.phase() == ObservationEventV01.Phase.START).map(ObservationEventV01::spanId).collect(java.util.stream.Collectors.toSet());
                if (events.stream().anyMatch(e -> e.parentSpanId() != null && !spanIds.contains(e.parentSpanId()))) throw new AssertionError("ORPHAN_OBSERVATION_PARENT");
                String status = controlled ? "PASS" : "RUNTIME_COMPLETED";
                write(out.resolve("result.json"), Map.of("status", status, "evaluationStatus", controlled ? "CONTROLLED_BALANCE_CHECKS_PASSED" : "EVALUATION_NOT_SCORED", "mode", args[0], "fixture", fixture, "replies", replies,
                        "controlledModelHttpCount", controlled ? model.requests.size() : 0, "modelClientTransportInvocations", modelInvocations(events), "bankCustomerReadCount", bank.customerReads()));
                System.out.println(HarnessJson.mapper().writeValueAsString(Map.of("status", status, "mode", args[0], "output", out.toAbsolutePath().toString(), "turns", replies.size())));
            } catch (RuntimeException failure) {
                write(out.resolve("result.json"), Map.of("status", isDenied(failure) ? "RUNTIME_REJECTED" : "RUNTIME_FAILED", "evaluationStatus", "EVALUATION_NOT_SCORED",
                        "mode", args[0], "fixture", fixture, "replies", replies, "exceptionType", failure.getClass().getSimpleName(),
                        "modelClientTransportInvocations", modelInvocations(events), "bankCustomerReadCount", bank.customerReads()));
                throw failure;
            } finally {
                write(out.resolve("observations.json"), events); write(out.resolve("bank-http.json"), bank.requests);
                if (controlled) write(out.resolve("model-http.json"), model.requests);
            }
        }
    }
    static Path configuredFile(Path root, String name, Path fallback) throws Exception {
        String configured = System.getenv(name);
        Path file = configured == null ? fallback : root.resolve(configured).normalize();
        Path real = file.toRealPath();
        if (!real.startsWith(root.toRealPath()) || Files.isSymbolicLink(file) || !Files.isRegularFile(real))
            throw new IllegalArgumentException("ISOLATED_REFERENCE_OUTSIDE_ROOT:" + name);
        return real;
    }
    static long modelInvocations(List<ObservationEventV01> events) { return events.stream().filter(e -> e.component().equals("model-http") && e.phase() == ObservationEventV01.Phase.START).count(); }
    static boolean isDenied(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) if (cursor instanceof SecurityException) return true;
        return false;
    }
    static NonAiBankingProvider provider(URI uri, String principal, String subject, String session) {
        return new NonAiBankingProvider(NonAiBankingProvider.Config.hostSession(uri, Duration.ofSeconds(10),
                () -> new NonAiBankingProvider.HostSession("fixture-token", subject, principal, session, Instant.now().plusSeconds(900)))
                .observations(AgentScopeObservationCollector::currentRun));
    }
    static void write(Path file, Object value) throws Exception { Files.write(file, HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value)); }
    static String required(String name) { String value = System.getenv(name); if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " required"); return value; }
}
