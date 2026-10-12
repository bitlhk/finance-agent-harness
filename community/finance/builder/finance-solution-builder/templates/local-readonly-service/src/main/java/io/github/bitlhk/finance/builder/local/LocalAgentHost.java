package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only generated application host. Business tasks use installed tools, never model-controlled identity. */
public final class LocalAgentHost {
    private final Path root;
    private final String buildId, instanceId = UUID.randomUUID().toString(), token;
    private final Set<String> capabilities;
    private final BankAccess bank;
    private final ModelConnections models;
    private final Map<String, NativeConversation> sessions = new ConcurrentHashMap<>();
    private final Object nativeLock = new Object();
    private final JsonNode manifest;
    private final ExecutionArchive archive;
    private final HostTaskScope taskScope;
    private static final ThreadLocal<Long> executorWait = ThreadLocal.withInitial(() -> 0L);

    private LocalAgentHost(Path root) throws Exception {
        this.root = root;
        token = BankAccess.env("FH_HOST_TOKEN");
        if (token.length() < 32) throw new IllegalArgumentException("HOST_TOKEN_TOO_SHORT");
        manifest = HarnessJson.mapper().readTree(root.resolve("service-lock.json").toFile());
        if (System.getenv("FH_EXPECTED_LOCK") != null) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("service-lock.json"))));
            if (!actual.equals(System.getenv("FH_EXPECTED_LOCK"))) throw new IllegalArgumentException("BUILD_LOCK_MISMATCH");
        }
        buildId = manifest.path("buildId").asText();
        archive = new ExecutionArchive(root, buildId, instanceId);
        capabilities = new HashSet<>(); manifest.path("capabilities").forEach(v -> capabilities.add(v.asText()));
        var definitions = ComponentAdmission.definitions(root);
        if (!capabilities.equals(definitions.keySet())) throw new IllegalArgumentException("HOST_COMPOSITION_REQUIRED");
        boolean tasks = definitions.values().stream().anyMatch(c -> c.operation()
                .name().equals("TASK"));
        verifyFiles();
        bank = new BankAccess(tasks, ComponentAdmission.requiredScopes(root));
        models = new ModelConnections(root, manifest);
        if (!Set.of("LIVE", "CONTROLLED_TEST").contains(BankAccess.env("FH_MODEL_MODE")))
            throw new IllegalArgumentException("MODEL_MODE_REQUIRED");
        if (tasks) {
            Path state = Path.of(BankAccess.env("FH_TASK_STATE_DIR")).toAbsolutePath().normalize();
            if (state.startsWith(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("PRIVATE_STATE_OUTSIDE_ARTIFACT_REQUIRED");
            taskScope = new HostTaskScope(state, BankAccess.env("FH_TASK_OWNER"), buildId);
        } else taskScope = null;
    }

    private void verifyFiles() throws Exception {
        var fields = manifest.path("files").fields();
        if (!fields.hasNext()) throw new IllegalStateException("EMPTY_ARTIFACT_MANIFEST");
        while (fields.hasNext()) {
            var entry = fields.next(); Path file = root.resolve(entry.getKey()).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.isSymbolicLink(file)
                    || !file.toRealPath().startsWith(root.toRealPath()))
                throw new IllegalStateException("ARTIFACT_MISSING");
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            if (!hash.equals(entry.getValue().asText())) throw new IllegalStateException("ARTIFACT_DIGEST_MISMATCH");
        }
        try (var jars = Files.list(root.resolve("target/dependency"))) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList())
                if (!manifest.path("files").has("target/dependency/" + jar.getFileName()))
                    throw new IllegalArgumentException("UNLOCKED_CLASSPATH_ARTIFACT");
        }
    }

    private void handle(HttpExchange exchange) throws java.io.IOException {
        ExecutionArchive.Request observed = null;
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8),
                    ("Bearer " + token).getBytes(StandardCharsets.UTF_8))) { send(exchange, 401, Map.of("error", "HOST_AUTH_REQUIRED")); return; }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("/health".equals(path) && "GET".equals(method)) {
                send(exchange, 200, Map.of("status", "RUNNING", "buildId", buildId, "instanceId", instanceId,
                        "selectedCapabilities", capabilities, "runtime", NativeRuntime.ID, "artifactLock", manifest,
                        "mode", "CONTROLLED_TEST".equals(System.getenv("FH_MODEL_MODE"))
                                ? "EXPLICIT_TEST_MODEL" : "CONFIGURED_MODEL_LOCAL_SIMULATOR",
                        "activeSessions", sessions.size())); return;
            }
            if ("/sessions".equals(path) && "POST".equals(method)) {
                JsonNode request = object(exchange);
                var keys = new HashSet<String>(); request.fieldNames().forEachRemaining(keys::add);
                if (!Set.of("modelRef", "previousSessionId").containsAll(keys)
                        || request.has("modelRef") && !request.path("modelRef").isTextual()
                        || request.has("previousSessionId") && !request.path("previousSessionId").isTextual())
                    throw new IllegalArgumentException("HOST_OWNS_IDENTITY");
                var model = models.current(request.path("modelRef").asText(models.defaultRef()));
                String previousId = request.path("previousSessionId").asText();
                NativeConversation previous = previousId.isEmpty() ? null : sessions.get(previousId);
                if (!previousId.isEmpty() && previous == null) throw new SecurityException("SESSION_UNAVAILABLE");
                if (previous != null) previous.authorize(null);
                if (sessions.size() >= 16) { send(exchange, 429, Map.of("error", "SESSION_LIMIT")); return; }
                synchronized (taskScope == null ? new Object() : taskScope) {
                String id = taskScope == null ? UUID.randomUUID().toString() : taskScope.sessionId;
                if (taskScope != null && sessions.containsKey(id)) throw new IllegalArgumentException("TASK_SESSION_ALREADY_ACTIVE");
                var login = taskScope == null ? bank.open(id) : bank.open(taskScope);
                try {
                    if (previous != null && !previous.login.customer.equals(login.customer))
                        throw new SecurityException("AUTHORIZATION_REJECTED");
                    var conversation = new NativeConversation(root, bank, login, capabilities, models, model, taskScope);
                    synchronized (nativeLock) {
                        if (taskScope != null && sessions.containsKey(id)) throw new IllegalArgumentException("TASK_SESSION_ALREADY_ACTIVE");
                        conversation.authorize();
                        {
                            var installation = conversation.install();
                            if (previous != null) {
                                previous.authorize(); previous.clear(); bank.revoke(previous.login); sessions.remove(previousId);
                            }
                            sessions.put(id, conversation);
                            send(exchange, 200, Map.of("sessionId", id, "buildId", buildId, "instanceId", instanceId,
                                    "installations", installation, "authorization", "LOCAL_SIMULATOR_POLICY",
                                    "modelRef", model.ref(), "modelConfigVersion", model.version())); return;
                        }
                    }
                } catch (Exception failure) { if (taskScope == null) bank.revoke(login); throw failure; }
                }
            }
            String[] parts = path.split("/");
            if (parts.length != 4 || !"sessions".equals(parts[1]) || !"POST".equals(method)) {
                send(exchange, 404, Map.of("error", "ROUTE_NOT_FOUND")); return;
            }
            NativeConversation conversation = sessions.get(parts[2]);
            if (conversation == null) { send(exchange, 403, Map.of("error", "SESSION_UNAVAILABLE", "clearConversation", true)); return; }
            JsonNode body = object(exchange);
            if (Set.of("tasks", "task-challenge", "task-confirm", "task-decline", "task-resume", "step-up").contains(parts[3])) {
                if (taskScope == null) throw new IllegalArgumentException("TASK_NOT_INSTALLED");
                var keys = new HashSet<String>(); body.fieldNames().forEachRemaining(keys::add);
                Set<String> expected = switch (parts[3]) {
                    case "tasks" -> Set.of();
                    case "step-up" -> Set.of("verificationCode");
                    case "task-confirm" -> Set.of("handle", "nonce");
                    default -> Set.of("handle");
                };
                if (!keys.equals(expected) || keys.stream().anyMatch(k -> !body.path(k).isTextual() || body.path(k).asText().isBlank()))
                    throw new IllegalArgumentException("INVALID_TASK_REQUEST");
                String requestType = switch (parts[3]) {
                    case "tasks" -> "TASK_LIST";
                    case "task-challenge" -> "TASK_CHALLENGE";
                    case "task-confirm" -> "TASK_CONFIRM";
                    case "task-decline" -> "TASK_DECLINE";
                    case "task-resume" -> "TASK_RESUME";
                    case "step-up" -> "TASK_STEP_UP";
                    default -> "TASK_OPERATION";
                };
                observed = archive.begin(root, parts[2], conversation.login.customer, executorWait.get(), requestType);
                var waiting = observed.run.start(observed.root.id(), "host", "native-lock-wait", Map.of());
                synchronized (nativeLock) {
                    waiting.finish(Outcome.COMPLETED, Map.of());
                    conversation.observe(observed);
                    try {
                        conversation.authorize();
                        Object value;
                        if ("tasks".equals(parts[3])) value = Map.of("tasks", conversation.tasks());
                        else if ("step-up".equals(parts[3])) {
                            bank.stepUp(conversation.login, body.path("verificationCode").asText());
                            value = Map.of("status", "VERIFIED");
                        } else value = conversation.taskAction(parts[3], body.path("handle").asText(), body.path("nonce").asText());
                        conversation.authorize();
                        // Returned is an observation outcome, not a bank business success declaration.
                        observed.finish(Outcome.RETURNED);
                        send(exchange, 200, value);
                    } finally { conversation.stopObserving(); }
                }
                return;
            }
            if ("cancel".equals(parts[3])) {
                if (body.size() != 1 || !body.path("runId").isTextual()) throw new IllegalArgumentException("INVALID_INPUT");
                conversation.authorize(null);
                var result = conversation.cancel(body.path("runId").asText());
                var active = conversation.observation;
                if (active != null && "CANCEL_REQUESTED".equals(result.get("status"))) try (var signal = active.run.start(active.root.id(), "host", "cancel-request", Map.of())) {
                    signal.finish(Outcome.CANCEL_REQUESTED, Map.of());
                }
                send(exchange, 200, result); return;
            }
            if ("end".equals(parts[3])) {
                if (!body.isEmpty()) throw new IllegalArgumentException("INVALID_INPUT");
                end(parts[2], conversation, "SESSION_ENDED");
                send(exchange, 200, Map.of("status", "ENDED", "clearConversation", true)); return;
            }
            if ("snapshot".equals(parts[3]) || "activity".equals(parts[3])) {
                if (!body.isEmpty()) throw new IllegalArgumentException("INVALID_INPUT");
                conversation.authorize(null);
                if ("activity".equals(parts[3])) conversation.lifecycle.touch();
                send(exchange, 200, conversation.snapshot()); return;
            }
            if ("ag-ui".equals(parts[3])) {
                AgUiTransport.validate(body, parts[2], instanceId);
                observed = archive.begin(root, parts[2], conversation.login.customer, executorWait.get(), "CONVERSATION_TURN_AG_UI");
                var waiting = observed.run.start(observed.root.id(), "host", "native-lock-wait", Map.of());
                synchronized (nativeLock) {
                    waiting.finish(Outcome.COMPLETED, Map.of());
                    conversation.observe(observed);
                    try {
                        AgUiTransport.run(exchange, body, conversation, buildId, instanceId, () -> {
                            conversation.clear(); if (taskScope == null) bank.revoke(conversation.login); sessions.remove(parts[2]);
                        });
                    } finally { conversation.stopObserving(); }
                }
                return;
            }
            if ("status".equals(parts[3])) {
                if (!body.isEmpty()) throw new IllegalArgumentException("INVALID_INPUT");
                // A concurrent status poll must not append to the chat's request archive.
                try { conversation.authorize(null); send(exchange, 200, conversation.lifecycle.status()); }
                catch (SecurityException denied) {
                    bank.revoke(conversation.login);
                    synchronized (nativeLock) { conversation.clear(); sessions.remove(parts[2]); }
                    throw denied;
                }
                return;
            }
            if ("revoke".equals(parts[3])) {
                if (!body.isEmpty()) throw new IllegalArgumentException("INVALID_INPUT");
                conversation.lifecycle.close("AUTHORIZATION_REJECTED"); conversation.invalidate(); bank.revoke(conversation.login);
                synchronized (nativeLock) { conversation.clear(); sessions.remove(parts[2]); }
                send(exchange, 200, Map.of("status", "REVOKED", "clearConversation", true)); return;
            }
            if (!"chat".equals(parts[3]) || (body.size() != 1 && !(body.size() == 2 && body.path("runId").isTextual())) || !body.path("query").isTextual()
                    || body.path("query").asText().isBlank() || body.path("query").asText().length() > 4000)
                throw new IllegalArgumentException("INVALID_CHAT_INPUT");
            observed = archive.begin(root, parts[2], conversation.login.customer, executorWait.get(), "CONVERSATION_TURN");
            var waiting = observed.run.start(observed.root.id(), "host", "native-lock-wait", Map.of());
            synchronized (nativeLock) {
                waiting.finish(Outcome.COMPLETED, Map.of());
                conversation.observe(observed);
                String runId = body.path("runId").asText(UUID.randomUUID().toString());
                try {
                    if (!runId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("RUN_ID_INVALID");
                    Map<String, Object> result = new LinkedHashMap<>(conversation.chat(body.path("query").asText(), runId));
                    result.put("buildId", buildId); result.put("instanceId", instanceId);
                    conversation.authorize();
                    observed.finish("PARTIAL".equals(result.get("status")) ? Outcome.INCOMPLETE : Outcome.COMPLETED);
                    send(exchange, 200, result);
                } catch (NativeConversation.RunCancelled cancelled) {
                    observed.finish(Outcome.CANCELLED);
                    send(exchange, 409, Map.of("error", "RUN_CANCELLED", "clearMessages", true, "clearConversation", false));
                } catch (Exception failure) {
                    if (!(failure instanceof SecurityException)) {
                        var partial = conversation.partialAfterModelFailure(body.path("query").asText(), runId);
                        if (partial != null) {
                            observed.finish(Outcome.INCOMPLETE);
                            send(exchange, 200, partial);
                            return;
                        }
                    }
                    conversation.clear();
                    if (taskScope == null || failure instanceof SecurityException) bank.revoke(conversation.login);
                    sessions.remove(parts[2]);
                    if (failure instanceof SecurityException) throw failure;
                    observed.finish(Outcome.ERROR);
                    send(exchange, 502, Map.of("error", "MODEL_OR_RUNTIME_FAILED", "clearConversation", true));
                } finally { conversation.stopObserving(); }
            }
        } catch (SecurityException denied) {
            if (observed != null) observed.finish(Outcome.DENIED);
            if ("STEP_UP_REQUIRED".equals(denied.getMessage())) {
                send(exchange, 403, Map.of("error", "STEP_UP_REQUIRED", "clearConversation", false)); return;
            }
            if (Set.of("TASK_NOT_AVAILABLE", "CONFIRMATION_NOT_AVAILABLE", "CONFIRMATION_BINDING_MISMATCH",
                    "CONFIRMATION_EXPIRED_OR_CHANGED").contains(String.valueOf(denied.getMessage()))) {
                send(exchange, 409, Map.of("error", denied.getMessage(), "clearConversation", false)); return;
            }
            String code = Set.of("MODEL_NOT_SELECTED", "MODEL_CONNECTION_UNAVAILABLE", "MODEL_CONFIGURATION_CHANGED", "SESSION_EXPIRED", "SESSION_ENDED")
                    .contains(String.valueOf(denied.getMessage())) ? denied.getMessage() : "AUTHORIZATION_REJECTED";
            send(exchange, 403, Map.of("error", code, "clearConversation", true));
        }
        catch (IllegalArgumentException bad) { if (observed != null) observed.finish(Outcome.ERROR); send(exchange, 400, Map.of("error", "INVALID_REQUEST")); }
        catch (Exception failed) {
            if (observed != null) observed.finish(Outcome.ERROR);
            System.err.println("DEPENDENCY_FAILURE_CLASS " + failed.getClass().getSimpleName());
            send(exchange, 503, Map.of("error", "SERVICE_DEPENDENCY_FAILED"));
        }
        finally { if (observed != null) observed.close(); exchange.close(); }
    }
    private void end(String id, NativeConversation conversation, String reason) {
        conversation.lifecycle.close(reason); conversation.invalidate(); sessions.remove(id, conversation);
        // Invalidate immediately; SDK context cleanup waits for the in-flight call to leave its critical section.
        CompletableFuture.runAsync(() -> {
            if (taskScope == null) bank.revoke(conversation.login);
            synchronized (nativeLock) { conversation.clear(); }
        });
    }
    private void expire() {
        sessions.forEach((id, conversation) -> {
            try { conversation.lifecycle.check(); }
            catch (SecurityException expired) { end(id, conversation, "SESSION_EXPIRED"); }
        });
    }
    private static JsonNode object(HttpExchange e) throws Exception {
        byte[] bytes = e.getRequestBody().readNBytes(16_385);
        if (bytes.length > 16_384) throw new IllegalArgumentException("BODY_TOO_LARGE");
        JsonNode value = HarnessJson.mapper().readTree(bytes);
        if (value == null || !value.isObject()) throw new IllegalArgumentException("OBJECT_REQUIRED");
        return value;
    }
    private static void send(HttpExchange e, int status, Object body) throws java.io.IOException {
        byte[] bytes = HarnessJson.mapper().writeValueAsBytes(body);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.getResponseHeaders().set("Cache-Control", "no-store");
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes);
    }
    public static void main(String[] args) throws Exception {
        SnapshotStorage.validateConfiguration(System.getenv());
        Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath().normalize();
        LocalAgentHost app = new LocalAgentHost(root);
        int port = Integer.parseInt(System.getenv().getOrDefault("FH_PORT", "0"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        if (!NativeRuntime.start()) throw new IllegalStateException("NATIVE_RUNTIME_START_FAILED");
        var executor = Executors.newFixedThreadPool(6);
        var reaper = Executors.newSingleThreadScheduledExecutor();
        reaper.scheduleAtFixedRate(app::expire, 1, 1, TimeUnit.SECONDS);
        server.setExecutor(task -> {
            long submitted = System.nanoTime();
            executor.execute(() -> {
                executorWait.set(Math.max(0, System.nanoTime() - submitted));
                try { task.run(); } finally { executorWait.remove(); }
            });
        });
        server.createContext("/", app::handle); server.start();
        HarnessJson.mapper().writeValue(root.resolve("instance.json.tmp").toFile(), Map.of("buildId", app.buildId,
                "instanceId", app.instanceId, "port", server.getAddress().getPort(), "pid", ProcessHandle.current().pid()));
        Files.move(root.resolve("instance.json.tmp"), root.resolve("instance.json"), StandardCopyOption.REPLACE_EXISTING);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0); app.sessions.values().forEach(s -> { s.clear(); if (app.taskScope == null) app.bank.revoke(s.login); });
            reaper.shutdownNow(); executor.shutdownNow(); NativeRuntime.stop();
            if (app.taskScope != null) try { app.taskScope.close(); } catch (java.io.IOException failed) { System.err.println("TASK_SCOPE_CLOSE_FAILED"); }
            System.out.println("SNAPSHOT_FLUSH " + (ModelSnapshots.flush() ? "DRAINED" : "INCOMPLETE"));
        }));
        System.out.println("LOCAL_AGENT_READY " + app.buildId);
    }
}
