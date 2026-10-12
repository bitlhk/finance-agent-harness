package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
import io.github.bitlhk.finance.harness.assembly.FinanceCapabilityPackV1;
import io.github.bitlhk.finance.harness.execution.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** Host session adapter only. AgentScope owns reasoning, tools and private model memory. */
final class NativeConversation {
    final BankAccess.Login login;
    final TerminalSession lifecycle;
    final ModelConnections.Connection model;
    volatile ExecutionArchive.Request observation;
    private final Path root;
    private final BankAccess bank;
    private final ModelConnections models;
    private final Set<String> capabilities, required;
    private final AgentScopeQueryAssembler.Prepared prepared;
    private final HostTaskState taskState;
    private final Map<String, FinanceCapabilityPackV1.Capability> definitions;
    private final List<Object> history = new ArrayList<>();
    private final List<Object> sourceCalls = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> activeModelSpan = new AtomicReference<>();
    private final ThreadLocal<Boolean> hostTaskAction = new ThreadLocal<>();
    private volatile AgentScopeQuerySession nativeSession;
    private long generation;
    private int turns;

    NativeConversation(Path root, BankAccess bank, BankAccess.Login login, Set<String> capabilities,
                       ModelConnections models, ModelConnections.Connection model, HostTaskScope tasks) throws Exception {
        this.root = root; this.bank = bank; this.login = login; this.models = models; this.model = model;
        this.capabilities = Set.copyOf(capabilities);
        var ui = Files.exists(root.resolve("ui-config.json")) ? HarnessJson.mapper().readTree(root.resolve("ui-config.json").toFile())
                : HarnessJson.mapper().createObjectNode();
        lifecycle = new TerminalSession(ui, Clock.systemUTC());
        var scopes = new HashSet<>(ComponentAdmission.requiredScopes(root));
        scopes.addAll(ManagedProviderFactoriesV01.installed().sourceScopes(ComponentAdmission.selectedProviders(root)));
        required = Set.copyOf(scopes);
        var connection = new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", bank.base, Duration.ofSeconds(10), () -> {
            var source = bank.bankSession(login);
            return new ManagedProviderFactoryV01.SourceSession(source.bankSessionId(), source.customerRef(), source.principalId(),
                    source.hostSessionRef(), source.expiresAt());
        }, AgentScopeObservationCollector::currentRun, sourceCalls::add);
        prepared = AgentScopeAdmission.prepare(root, AgentScopeAdmission.providers(root, connection));
        if (!prepared.capabilityIds().equals(capabilities)) throw new SecurityException("HOST_CAPABILITIES_MISMATCH");
        definitions = ComponentAdmission.definitions(root);
        boolean hasTasks = definitions.values().stream().anyMatch(c -> c.operation() == FinanceCapabilityPackV1.Operation.TASK);
        if (hasTasks && tasks == null) throw new SecurityException("TRUSTED_TASK_SCOPE_REQUIRED");
        if (tasks != null) {
            tasks.bindIdentity(bank.current(login).principalId(), login.customer);
            taskState = new HostTaskState(tasks.root.resolve("tasks"), login.hostSession, Clock.systemUTC(),
                    this::authorize, () -> { authorize(); bank.requireStrong(login); });
        } else taskState = null;
        openGeneration();
    }
    private void openGeneration() {
        var grant = currentGrant(false);
        var parameters = models.generation(model.ref());
        var connection = new ModelConnection(java.net.URI.create(model.url()), model.name(), model.key(),
                Duration.ofSeconds(Long.parseLong(System.getenv().getOrDefault("FH_MODEL_TIMEOUT_SECONDS", "45"))),
                parameters == null ? null : new ModelConnection.Generation(parameters.temperature(), parameters.topP(), parameters.maxTokens()));
        nativeSession = prepared.openSession(new AgentScopeQuerySession.Identity(grant.principalId(), grant.principalType(),
                login.customer, login.hostSession, bank.purpose, "local-preview", FinanceContextV1.AuthenticationLevel.BASIC, ++generation),
                () -> currentGrant(true), connection,
                taskState == null ? null : new AgentScopeQuerySession.TaskHost(login.hostSession, taskState));
    }
    private BoundFinanceProvider.Grant currentGrant(boolean processing) {
        if (processing && !Boolean.TRUE.equals(hostTaskAction.get())) lifecycle.processing(); else lifecycle.check();
        models.current(model.ref());
        var grant = bank.current(login);
        if (!grant.scopes().containsAll(required)) throw new SecurityException("SCOPE_NOT_GRANTED");
        return grant;
    }
    void observe(ExecutionArchive.Request request) {
        observation = request; login.observation = request;
        request.observeEvents(event -> trackModelSpan(activeModelSpan, event));
        request.labels(prepared.displayMetadata());
    }
    static void trackModelSpan(AtomicReference<String> active, ObservationEventV01 event) {
        if (!event.component().equals("model") || !event.operation().equals("client-prepared-call")) return;
        if (event.phase() == Phase.START) {
            if (!active.compareAndSet(null, event.spanId())) active.set(null);
        } else if (event.phase() == Phase.END) active.compareAndSet(event.spanId(), null);
    }
    void stopObserving() { observation = null; login.observation = null; activeModelSpan.set(null); }
    void authorize() { authorize(observation); }
    void authorize(ExecutionArchive.Request request) {
        try (var span = request == null ? null : request.run.start(request.root.id(), "host", "authorization", Map.of())) {
            try {
                lifecycle.check(); models.current(model.ref());
                if (!bank.current(login, request).scopes().containsAll(required)) throw new SecurityException("SCOPE_NOT_GRANTED");
                if (span != null) span.finish(Outcome.COMPLETED, Map.of());
            } catch (RuntimeException denied) {
                invalidate(); if (span != null) span.finish(Outcome.DENIED, Map.of()); throw denied;
            }
        }
    }
    Object install() throws Exception {
        authorize();
        var lock = nativeSession.lock();
        Path directory = runtimeEvidenceDirectory(root, login.hostSession);
        Files.createDirectories(directory);
        ExecutionArchive.writeImmutable(directory.resolve("runtime-lock.json"), HarnessJson.mapper().writeValueAsBytes(lock),
                ExecutionArchive.MANIFEST_HARD_BYTES);
        return lock.get("installations");
    }
    static Path runtimeEvidenceDirectory(Path root, String session) throws Exception {
        UUID.fromString(session);
        var instance = HarnessJson.mapper().readTree(ExecutionArchive.readManifest(root.resolve("instance.json"), 65_536));
        String id = instance.path("instanceId").asText();
        if (!UUID.fromString(id).toString().equals(id)) throw new SecurityException("HOST_INSTANCE_INVALID");
        return root.resolve("runtime-evidence").resolve(session).resolve(id);
    }
    Map<String, Object> chat(String text, String runId) throws Exception {
        if (observation == null) throw new IllegalStateException("HOST_OBSERVATION_REQUIRED");
        if (turns >= 20) throw new SecurityException("SESSION_TURN_LIMIT");
        authorize(); lifecycle.begin(runId);
        sourceCalls.clear();
        if (nativeSession == null) openGeneration();
        var session = nativeSession;
        var request = observation;
        request.modelContext(model, runId); request.installation(session.lock());
        long started = System.nanoTime();
        try {
            ZoneId zone = ZoneId.of(System.getenv().getOrDefault("FH_BUSINESS_TIME_ZONE", "Asia/Shanghai"));
            LocalDate date = System.getenv("FH_BUSINESS_DATE") == null ? LocalDate.now(zone) : LocalDate.parse(System.getenv("FH_BUSINESS_DATE"));
            var snapshots = new ModelSnapshots(request, () -> {
                String span = activeModelSpan.get();
                if (span == null) throw new IllegalStateException("SNAPSHOT_MODEL_SPAN_UNAVAILABLE");
                return span;
            }, List.of(model.key(), login.bankSession, login.hostSession, login.customer,
                    System.getenv().getOrDefault("FH_BANK_SECRET", "")));
            var diagnostics = new ModelDiagnostics(ModelSnapshots.allowed(), () -> {
                lifecycle.processing(); return ModelSnapshots.allowed();
            }, snapshots);
            var input = new AgentScopeQuerySession.Input(text, request.id, "host-" + request.id,
                    new TrustedBusinessTimeV01(date, zone, "host-managed-business-clock"), request.run, request.root.id(), diagnostics);
            var reply = session.invoke(input).block(Duration.ofSeconds(180));
            lifecycle.processing(); authorize(); models.current(model.ref());
            if (reply == null || !session.valid() || session != nativeSession) throw new SecurityException("SESSION_GENERATION_INVALID");
            List<Object> tools = new ArrayList<>(reply.executions().stream().filter(e -> e.request().operation() == CapabilityRequestV1.Operation.QUERY).map(e -> {
                var data = new LinkedHashMap<String,Object>(e.result().data());
                data.put("capabilityId", e.request().capabilityId()); data.put("resultStatus", e.result().status().name());
                data.put("sourceSystem", e.result().sourceSystem()); data.put("asOf", e.result().asOf().toString());
                return (Object)Map.of("data", data, "businessResult", e.result(), "receipt", e.receipt());
            }).toList());
            tools.addAll(reply.taskResults());
            var output = new LinkedHashMap<String, Object>();
            output.put("response", reply.text()); output.put("toolResults", tools); output.put("executions", reply.executions());
            output.put("events", List.of()); output.put("turn", ++turns); output.put("generation", reply.generation());
            output.put("elapsedMillis", (System.nanoTime() - started) / 1_000_000);
            output.put("runtimeLock", session.lock()); output.put("identityChecks", bank.identityCalls());
            output.put("sourceCalls", List.copyOf(sourceCalls)); output.put("diagnosticFailures", reply.diagnosticFailures());
            output.put("evidenceKind", "CONTROLLED_TEST".equals(System.getenv("FH_MODEL_MODE"))
                    ? "EXPLICIT_TEST_MODEL" : "CONFIGURED_MODEL_WITH_LOCAL_BANK_SIMULATOR");
            output.put("modelName", model.name()); output.put("modelRef", model.ref()); output.put("modelConfigVersion", model.version());
            models.generationEvidence(model.ref(), output);
            output.put("sessionId", login.hostSession); output.put("executionObservation", request.receipt()); output.put("at", Instant.now().toString());
            String messageId = "message-" + UUID.randomUUID();
            var cards = new ArrayList<Map<String,Object>>();
            for (Object item : DisplayProjection.cards(tools, capabilities)) {
                @SuppressWarnings("unchecked") var card = new LinkedHashMap<>((Map<String,Object>)item);
                card.put("cardId", messageId + "-" + cards.size()); cards.add(card);
            }
            var display = Map.of("schemaVersion", "finance-display.v0.1", "runId", runId, "messageId", messageId,
                    "cards", cards, "at", output.get("at"), "modelName", model.name());
            output.put("display", display);
            output.put("evidenceSha256", ExecutionArchive.hash(HarnessJson.mapper().writeValueAsBytes(output)));
            Path evidence = runtimeEvidenceDirectory(root, login.hostSession);
            ExecutionArchive.writeImmutable(evidence.resolve("turn-" + turns + ".json"), HarnessJson.mapper().writeValueAsBytes(Map.of(
                    "turn", turns, "generation", generation, "evidenceSha256", output.get("evidenceSha256"), "at", output.get("at"),
                    "toolCount", tools.size(), "modelRef", model.ref(), "modelConfigVersion", model.version())), 65_536);
            lifecycle.complete(() -> {
                authorize();
                synchronized (history) {
                    history.add(Map.of("role", "user", "text", text, "runId", runId));
                    history.add(Map.of("role", "assistant", "text", reply.text(), "display", display));
                }
            });
            return output;
        } catch (Exception failure) {
            boolean cancelled = lifecycle.cancelled();
            lifecycle.finish("FAILED"); clear();
            if (cancelled) throw new RunCancelled();
            throw failure;
        }
    }
    Map<String, Object> cancel(String runId) {
        var result = lifecycle.cancel(runId);
        if ("CANCEL_REQUESTED".equals(result.get("status"))) {
            var current = nativeSession; if (current != null) current.cancel();
        }
        return result;
    }
    void invalidate() { var current = nativeSession; if (current != null) current.invalidate(); }
    Map<String, Object> snapshot() {
        authorize(null);
        synchronized (history) { return Map.of("sessionId", login.hostSession, "messages", List.copyOf(history), "lifecycle", lifecycle.status()); }
    }
    void clear() {
        var current = nativeSession; nativeSession = null;
        if (current != null) current.close();
        sourceCalls.clear(); synchronized (history) { history.clear(); }
    }
    private Object nativeTask(String action, String handle, TaskCoordination.Approval approval) {
        // Host actions are synchronous and retain all authorization checks, but are not the cancelled chat.
        hostTaskAction.set(true);
        try { return authorizedTaskAction(action, handle, approval); }
        finally { hostTaskAction.remove(); }
    }
    private Object authorizedTaskAction(String action, String handle, TaskCoordination.Approval approval) {
        if (observation == null) throw new IllegalStateException("HOST_OBSERVATION_REQUIRED");
        if (nativeSession == null || !nativeSession.valid()) {
            if (nativeSession != null) nativeSession.close();
            openGeneration();
        }
        observation.installation(nativeSession.lock());
        ZoneId zone = ZoneId.of(System.getenv().getOrDefault("FH_BUSINESS_TIME_ZONE", "Asia/Shanghai"));
        LocalDate date = System.getenv("FH_BUSINESS_DATE") == null ? LocalDate.now(zone) : LocalDate.parse(System.getenv("FH_BUSINESS_DATE"));
        var input = new AgentScopeQuerySession.Input("Trusted host task action", observation.id, "host-" + observation.id,
                new TrustedBusinessTimeV01(date, zone, "host-managed-business-clock"), observation.run, observation.root.id());
        return nativeSession.taskAction(input, action, handle, approval);
    }
    private HostTaskState.InstalledTask installedTask(String handle) {
        if (taskState == null) throw new IllegalArgumentException("TASK_NOT_INSTALLED");
        var saved = taskState.task(handle);
        var definition = definitions.get(saved.capabilityId());
        if (definition == null || definition.operation() != FinanceCapabilityPackV1.Operation.TASK)
            throw new SecurityException("TASK_NOT_INSTALLED");
        return new HostTaskState.InstalledTask() {
            public String binding(String value) { return (String) nativeTask("binding", value, null); }
            public Object confirm(String value, TaskCoordination.Approval approval) { return nativeTask("confirm", value, approval); }
            public Object resume(String value) { return nativeTask("resume", value, null); }
            public Object decline(String value) { return nativeTask("decline", value, null); }
        };
    }
    List<Object> tasks() throws Exception {
        authorize();
        if (taskState == null) return List.of();
        List<Object> values = new ArrayList<>();
        for (String handle : taskState.handles()) {
            var saved = taskState.task(handle);
            boolean awaiting = false;
            try { installedTask(handle).binding(handle); awaiting = true; }
            catch (IllegalStateException notAwaiting) { }
            values.add(Map.of("handle", handle, "capabilityId", saved.capabilityId(), "name", definitions.get(saved.capabilityId()).name(),
                    "parameters", saved.arguments(), "taskId", saved.taskId(), "awaitingConfirmation", awaiting,
                    "sourceStatus", saved.terminalResponse() == null ? Objects.toString(saved.checkpoint().get(TaskCheckpointPolicy.CK_STATUS), "NOT_OBSERVED")
                            : saved.terminalResponse().get("status"),
                    "terminal", saved.terminalResponse() == null ? Map.of() : saved.terminalResponse()));
        }
        authorize(); return List.copyOf(values);
    }
    Object taskAction(String action, String handle, String nonce) throws Exception {
        authorize();
        var installed = installedTask(handle);
        Object result = switch (action) {
            case "task-challenge" -> taskState.challenge(handle, installed);
            case "task-confirm" -> taskState.confirm(handle, nonce, installed);
            case "task-decline" -> taskState.decline(handle, installed);
            case "task-resume" -> installed.resume(handle);
            default -> throw new IllegalArgumentException("TASK_ACTION_UNSUPPORTED");
        };
        authorize(); return result;
    }
    static final class RunCancelled extends RuntimeException { }
}
