package io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost;

import io.github.bitlhk.finance.harness.assembly.AssetDigests;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.reference.ControlledModel;
import io.github.bitlhk.finance.harness.providers.curatedmcp.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Packaged-byte probe. No test authority constructor or production configuration is used. */
public final class CuratedQualifiedHost {
    public static void main(String[] args) throws Exception {
        if (args.length != 9) throw new IllegalArgumentException("ROOT SOLUTION SUPPORT CONFIG SHA QUALIFICATION SHA FACTS OUT");
        Path root = Path.of(args[0]).toRealPath(), out = Path.of(args[8]);
        if (Files.exists(out)) throw new IllegalArgumentException("OUTPUT_EXISTS");
        Files.createDirectories(out);
        var config = CuratedSourceConfig.load(Path.of(args[3]), args[4]);
        var qualification = RuntimeQualification.load(Path.of(args[5]), args[6]);
        var expected = HarnessJson.mapper().readTree(Path.of(args[7]).toFile());
        var spec = HarnessJson.mapper().readTree(Path.of(args[1]).toFile());
        String packSource = spec.path("capabilityPackRefs").get(0).path("source").asText();
        if (!packSource.startsWith("repo://")) throw new IllegalArgumentException("PACK_SOURCE_INVALID");
        var pack = HarnessJson.mapper().readTree(root.resolve(packSource.substring(7)).toFile());
        var tools = new ArrayList<CuratedTools.Tool>();
        pack.path("capabilities").forEach(c -> tools.add(CuratedTools.named(c.path("toolName").asText().substring(8))));
        if (config.subjects().size() != 1) throw new IllegalArgumentException("EXPLICIT_QUALIFICATION_SUBJECT_REQUIRED");
        String principal = "qualified-actor", subject = config.subjects().keySet().iterator().next(), sessionId = UUID.randomUUID().toString();
        var sourceSession = new ManagedProviderFactoryV01.SourceSession("synthetic-not-forwarded", subject, principal, sessionId, Instant.now().plusSeconds(300));
        var events = new CopyOnWriteArrayList<ObservationEventV01>();
        var http = new CopyOnWriteArrayList<ManagedProviderFactoryV01.HttpAttempt>();
        var provider = new CuratedMcpProvider(new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(5), () -> sourceSession, AgentScopeObservationCollector::currentRun, http::add), config);
        var prepared = new AgentScopeQueryAssembler().prepare(root, Path.of(args[1]), Path.of(args[2]), Map.of(CuratedMcpProvider.ID, provider), qualification);
        var grant = new BoundFinanceProvider.Grant(principal, FinanceContextV1.PrincipalType.CUSTOMER, Set.of(subject), Set.of("synthetic-evaluation"),
                tools.stream().map(CuratedTools.Tool::scope).collect(java.util.stream.Collectors.toSet()), sessionId, "isolated-test-authority", Instant.now().plusSeconds(300));
        var identity = new AgentScopeQuerySession.Identity(principal, FinanceContextV1.PrincipalType.CUSTOMER, subject, sessionId,
                "synthetic-evaluation", "isolated-cli", FinanceContextV1.AuthenticationLevel.STRONG, 1);
        var replies = new ArrayList<AgentScopeQuerySession.Reply>();
        try (var model = new ControlledModel(); var session = prepared.openSession(identity, () -> grant,
                new ModelConnection(model.uri(), "controlled", "fixture-key", Duration.ofSeconds(10)))) {
            IsolatedHost.write(out.resolve("installation-lock.json"), session.lock());
            for (var tool : tools) {
                String target = expected.path(tool.name()).at(tool.identityPointer()).asText();
                Map<String, Object> parameters;
                if (tool.selector().equals("customer_id")) parameters = "BOUND_SUBJECT".equals(config.subjects().get(subject).customerSelection())
                        ? Map.of("customer_id", subject) : Map.of("customer_id", subject, "synthetic_customer_id", target);
                else parameters = tool.selector().isEmpty() ? Map.of() : Map.of(tool.selector(), target);
                model.enqueue(ControlledModel.tool(tool.toolName(), parameters)); model.enqueue(ControlledModel.text("Source read; not business approval."));
                String id = UUID.randomUUID().toString();
                var run = new ObservationRun(id, "isolated-boot", sessionId, event -> { events.add(event); return true; });
                try (var span = run.start(null, "host", "request", Map.of("harnessRequestId", id))) {
                    var reply = session.invoke(new AgentScopeQuerySession.Input("Read the declared synthetic resource.", id, "trace-" + id,
                            new TrustedBusinessTimeV01(LocalDate.of(2026, 9, 5), ZoneId.of("Asia/Shanghai"), "fixed-synthetic-fixture"), run, span.id()))
                            .block(Duration.ofSeconds(30));
                    replies.add(reply);
                    if (reply.executions().size() != 1 || reply.droppedObservationEvents() != 0) throw new AssertionError("EXECUTION_OR_OBSERVATION_MISMATCH");
                    var result = reply.executions().getFirst().result();
                    if (result.status() != CapabilityResultV1.Status.COMPLETED || !expected.path(tool.name()).equals(HarnessJson.mapper().valueToTree(result.data().get("sourceResult"))))
                        throw new AssertionError("SOURCE_FACT_MISMATCH:" + tool.name());
                    span.finish(ObservationEventV01.Outcome.RETURNED, Map.of());
                }
            }
            if (model.requests.size() != tools.size() * 2 || http.isEmpty()) throw new AssertionError("HTTP_EVIDENCE_MISSING");
            var starts = events.stream().filter(e -> e.phase() == ObservationEventV01.Phase.START).map(ObservationEventV01::spanId).collect(java.util.stream.Collectors.toSet());
            if (events.stream().anyMatch(e -> e.parentSpanId() != null && !starts.contains(e.parentSpanId()))) throw new AssertionError("ORPHAN_EVENT");
            var ends = events.stream().filter(e -> e.phase() == ObservationEventV01.Phase.END).map(ObservationEventV01::spanId).collect(java.util.stream.Collectors.toSet());
            if (!starts.equals(ends)) throw new AssertionError("UNCLOSED_OBSERVATION");
            IsolatedHost.write(out.resolve("result.json"), Map.of("status", "PASS", "scope", "PACKAGED_QUERY_QUALIFICATION_NOT_BUILDER_HOST_ADMISSION",
                    "tools", tools.stream().map(CuratedTools.Tool::toolName).toList(), "replies", replies,
                    "runtimeArtifacts", RuntimeInventory.inspect(List.of(provider)), "sourceConfigDigest", AssetDigests.sha256(Path.of(args[3])),
                    "qualificationDigest", args[6], "realModel", false, "skillAcceptance", "NOT_RUN"));
            IsolatedHost.write(out.resolve("model-http.json"), model.requests);
        } finally {
            IsolatedHost.write(out.resolve("observations.json"), events);
            IsolatedHost.write(out.resolve("source-http.json"), http);
        }
    }
}
