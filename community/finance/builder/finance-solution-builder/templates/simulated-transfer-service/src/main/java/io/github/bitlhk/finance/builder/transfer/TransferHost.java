package io.github.bitlhk.finance.builder.transfer;

import com.sun.net.httpserver.*;
import com.openjiuwen.core.runner.Runner;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.HostTaskConfirmation;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.security.MessageDigest;

/** Isolated simulator-only Host. No endpoint accepts an arbitrary tool or bank route. */
public final class TransferHost {
    final Path root, state;
    final SimulatorAccess bank;
    final String origin;
    final String cookiePath;
    final Map<String, Object> lock;
    final String startId = System.getenv().getOrDefault("FH_TRANSFER_START_ID", UUID.randomUUID().toString());
    final Object nativeLock = new Object();
    final Map<String, Long> loginAttempts = new HashMap<>();
    record Challenge(String sessionId, String handle, String nonce, String binding,
                     Instant issuedAt, Instant expiresAt, boolean consumed) { }

    @SuppressWarnings("unchecked")
    TransferHost(Path root, Path state, SimulatorAccess bank, String origin) throws Exception {
        this.root = root; this.state = state; this.bank = bank; this.origin = origin;
        cookiePath = System.getenv().getOrDefault("FH_TRANSFER_COOKIE_PATH", "/");
        if (!cookiePath.matches("/[a-zA-Z0-9/_-]*")) throw new IllegalArgumentException("INVALID_COOKIE_PATH");
        HostFiles.directory(state);
        lock = HostFiles.JSON.readValue(root.resolve("service-lock.json").toFile(), Map.class);
        var files = (Map<String, String>) lock.get("files");
        if (!"simulated-transfer-candidate.v0.1".equals(lock.get("schemaVersion")) || files == null || files.isEmpty())
            throw new SecurityException("INVALID_CANDIDATE_LOCK");
        for (var file : files.entrySet()) {
            Path actual = root.resolve(file.getKey()).normalize();
            if (!actual.startsWith(root) || Files.isSymbolicLink(actual)
                    || !HostFiles.digest(Files.readAllBytes(actual)).equals(file.getValue()))
                throw new SecurityException("CANDIDATE_CONTENT_CHANGED");
        }
    }

    void handle(HttpExchange exchange) {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'self'");
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                reply(exchange, 200, Map.of("status", "UP", "buildId", lock.get("buildId"),
                        "instanceId", startId, "simulationOnly", true, "runtime", "openjiuwen-java-0.1.15")); return;
            }
            if (!URI.create(origin).getRawAuthority().equals(exchange.getRequestHeaders().getFirst("Host")))
                throw new SecurityException("HOST_REJECTED");
            if (exchange.getRequestMethod().equals("GET") && Set.of("/", "/app.js", "/markdown.js", "/style.css", "/icons.svg").contains(path)) {
                Path file = root.resolve("ui").resolve(path.equals("/") ? "index.html" : path.substring(1));
                byte[] bytes = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", path.endsWith(".js") ? "text/javascript; charset=utf-8"
                        : path.endsWith(".css") ? "text/css; charset=utf-8" : path.endsWith(".svg") ? "image/svg+xml" : "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); return;
            }
            if (!exchange.getRequestMethod().equals("POST")) { reply(exchange, 405, Map.of("error", "METHOD_NOT_ALLOWED")); return; }
            if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) throw new SecurityException("ORIGIN_REJECTED");
            if (!"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")))
                throw new IllegalArgumentException("JSON_REQUIRED");
            byte[] bytes = exchange.getRequestBody().readNBytes(16_385);
            if (bytes.length > 16_384) throw new IllegalArgumentException("REQUEST_TOO_LARGE");
            var body = HostFiles.JSON.readTree(bytes);
            if (body == null || !body.isObject()) throw new IllegalArgumentException("OBJECT_REQUIRED");
            Set<String> allowed = switch (path) {
                case "/api/login" -> Set.of("loginName", "loginSecret");
                case "/api/step-up" -> Set.of("verificationCode");
                case "/api/prepare" -> Set.of("fromAccountId", "beneficiaryId", "amount", "purpose");
                case "/api/resume", "/api/decline", "/api/challenge" -> Set.of("handle");
                case "/api/confirm" -> Set.of("handle", "nonce");
                case "/api/chat" -> Set.of("query");
                case "/api/choices", "/api/state", "/api/logout" -> Set.of();
                default -> throw new IllegalArgumentException("ENDPOINT_NOT_SUPPORTED");
            };
            var names = body.fieldNames();
            while (names.hasNext()) if (!allowed.contains(names.next())) throw new IllegalArgumentException("UNEXPECTED_FIELD");
            synchronized (nativeLock) {
                if (path.equals("/api/login")) {
                    String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
                    long now = System.nanoTime(), last = loginAttempts.getOrDefault(peer, 0L);
                    if (now - last < 1_000_000_000L) { reply(exchange, 429, Map.of("error", "LOGIN_RATE_LIMIT")); return; }
                    loginAttempts.put(peer, now);
                    var session = bank.open(SimulatorAccess.text(body, "loginName"), SimulatorAccess.text(body, "loginSecret"));
                    HostFiles.save(state.resolve("sessions").resolve(session.id() + ".json"), session);
                    exchange.getResponseHeaders().set("Set-Cookie", "fh_transfer=" + session.id()
                            + "; Path=" + cookiePath + "; HttpOnly; SameSite=Strict" + (origin.startsWith("https:") ? "; Secure" : ""));
                    reply(exchange, 200, Map.of("csrf", session.csrf(), "customer", session.customer(), "expiresAt", session.expiresAt()));
                    return;
                }
                var session = session(exchange);
                if (!constant(session.csrf(), exchange.getRequestHeaders().getFirst("X-CSRF-Token")))
                    throw new SecurityException("CSRF_REJECTED");
                Object result;
                if (path.equals("/api/logout")) {
                    HostFiles.save(state.resolve("revoked").resolve(session.id() + ".json"), Map.of("at", Instant.now()));
                    Runner.release(session.id());
                    exchange.getResponseHeaders().set("Set-Cookie", "fh_transfer=; Path=" + cookiePath + "; HttpOnly; SameSite=Strict; Max-Age=0");
                    reply(exchange, 200, Map.of("closed", true)); return;
                }
                bank.require(session, SimulatorAccess.SCOPES);
                if (path.equals("/api/choices")) result = bank.choices(session);
                else if (path.equals("/api/step-up")) result = bank.stepUp(session, SimulatorAccess.text(body, "verificationCode"));
                else try (var runtime = new TransferRuntime(root, state, bank, session)) {
                    result = switch (path) {
                        case "/api/state" -> Map.of("tasks", runtime.tasks(), "buildId", lock.get("buildId"),
                                "installation", runtime.solution.lock());
                        case "/api/prepare" -> runtime.prepare(HostFiles.JSON.convertValue(body, Map.class));
                        case "/api/resume" -> runtime.resume(SimulatorAccess.text(body, "handle"));
                        case "/api/decline" -> runtime.installation.decline(SimulatorAccess.text(body, "handle"));
                        case "/api/challenge" -> challenge(runtime, SimulatorAccess.text(body, "handle"));
                        case "/api/confirm" -> confirm(runtime, SimulatorAccess.text(body, "handle"), SimulatorAccess.text(body, "nonce"));
                        case "/api/chat" -> Map.of("content", runtime.chat(SimulatorAccess.text(body, "query")), "tasks", runtime.tasks());
                        default -> throw new IllegalArgumentException("ENDPOINT_NOT_SUPPORTED");
                    };
                }
                bank.require(session, SimulatorAccess.SCOPES);
                reply(exchange, 200, result);
            }
        } catch (SecurityException rejected) {
            try { Runner.release(session(exchange).id()); } catch (Exception ignored) { }
            String reason = rejected.getMessage();
            safeReply(exchange, 403, reason != null && Set.of("STEP_UP_REQUIRED", "INSUFFICIENT_SCOPES",
                    "CONFIRMATION_EXPIRED_OR_CHANGED", "CONFIRMATION_NOT_FOUND").contains(reason)
                    ? reason : "AUTHORIZATION_REJECTED", true);
        } catch (IllegalArgumentException rejected) {
            safeReply(exchange, 400, "INVALID_REQUEST_OR_TASK", false);
        } catch (Exception failure) {
            // A failed HTTP observation is not a declaration that a bank transaction failed.
            var location = failure.getStackTrace().length == 0 ? null : failure.getStackTrace()[0];
            System.err.println("HOST_OPERATION_FAILED type=" + failure.getClass().getSimpleName()
                    + " at=" + (location == null ? "unavailable" : location.getClassName() + ":" + location.getLineNumber()));
            safeReply(exchange, 503, "MODEL_NOT_CONFIGURED".equals(failure.getMessage()) ? "MODEL_NOT_CONFIGURED"
                    : "HOST_RESULT_UNAVAILABLE_QUERY_ORIGINAL_TASK", true);
        } finally { exchange.close(); }
    }

    SimulatorAccess.Session session(HttpExchange exchange) throws Exception {
        String cookie = Objects.toString(exchange.getRequestHeaders().getFirst("Cookie"), "");
        String id = Arrays.stream(cookie.split(";")).map(String::trim).filter(s -> s.startsWith("fh_transfer="))
                .map(s -> s.substring("fh_transfer=".length())).findFirst().orElseThrow(SecurityException::new);
        if (!id.matches("[a-f0-9-]{36}")) throw new SecurityException();
        if (Files.exists(state.resolve("revoked").resolve(id + ".json"))) throw new SecurityException();
        SimulatorAccess.Session session;
        try { session = HostFiles.JSON.readValue(state.resolve("sessions").resolve(id + ".json").toFile(), SimulatorAccess.Session.class); }
        catch (java.io.IOException missing) { throw new SecurityException("SESSION_NOT_AVAILABLE"); }
        if (!id.equals(session.id())) throw new SecurityException();
        return session;
    }

    Object challenge(TransferRuntime runtime, String handle) throws Exception {
        bank.requireStrong(runtime.session);
        String binding = runtime.installation.confirmationBinding(handle);
        var nonce = new Challenge(runtime.session.id(), handle, UUID.randomUUID().toString(), binding,
                Instant.now(), Instant.now().plusSeconds(75), false);
        HostFiles.save(state.resolve("confirmations").resolve(nonce.nonce() + ".json"), nonce);
        var saved = runtime.store.locked(a -> a.get(handle));
        return Map.of("handle", handle, "nonce", nonce.nonce(), "binding", binding,
                "parameters", saved.arguments(), "currency", "CNY", "expiresAt", nonce.expiresAt());
    }

    Object confirm(TransferRuntime runtime, String handle, String nonceId) throws Exception {
        HostFiles.identifier(nonceId);
        Path file = state.resolve("confirmations").resolve(nonceId + ".json");
        Challenge nonce;
        try { nonce = HostFiles.JSON.readValue(file.toFile(), Challenge.class); }
        catch (java.io.IOException invalid) { throw new SecurityException("CONFIRMATION_NOT_FOUND"); }
        if (!nonce.sessionId().equals(runtime.session.id()) || !nonce.handle().equals(handle))
            throw new SecurityException("CONFIRMATION_BINDING_MISMATCH");
        var saved = runtime.store.locked(a -> a.get(handle));
        if (saved != null && saved.terminalResult() != null)
            return runtime.resume(handle);
        if (nonce.consumed()) return runtime.resume(handle);
        if (!nonce.expiresAt().isAfter(Instant.now())
                || !constant(nonce.binding(), runtime.installation.confirmationBinding(handle)))
            throw new SecurityException("CONFIRMATION_EXPIRED_OR_CHANGED");
        bank.requireStrong(runtime.session);
        HostFiles.save(file, new Challenge(nonce.sessionId(), handle, nonce.nonce(), nonce.binding(),
                nonce.issuedAt(), nonce.expiresAt(), true));
        return runtime.installation.confirm(handle, new HostTaskConfirmation(nonce.nonce(), nonce.binding(),
                Instant.now(), nonce.expiresAt()));
    }
    static boolean constant(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    static void reply(HttpExchange exchange, int status, Object value) throws Exception {
        byte[] bytes = HostFiles.JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    static void safeReply(HttpExchange exchange, int status, String code, boolean clear) {
        try { reply(exchange, status, Map.of("error", code, "clearConversation", clear)); }
        catch (Exception disconnected) { }
    }
    public static void main(String[] args) throws Exception {
        if (!"true".equals(HostFiles.env("FH_TRANSFER_SIMULATOR_ONLY"))) throw new SecurityException("SIMULATION_REQUIRED");
        Path root = Path.of(args[0]).toRealPath(), state = Path.of(HostFiles.env("FH_TRANSFER_STATE")).toAbsolutePath();
        if (state.startsWith(root)) throw new IllegalArgumentException("PRIVATE_STATE_MUST_BE_OUTSIDE_ARTIFACT");
        HostFiles.directory(state);
        int port = Integer.parseInt(HostFiles.env("FH_TRANSFER_PORT"));
        String origin = System.getenv().getOrDefault("FH_TRANSFER_ORIGIN", "http://127.0.0.1:" + port);
        var access = new SimulatorAccess(URI.create(HostFiles.env("FH_BANK_URL")),
                Path.of(HostFiles.env("FH_TRANSFER_POLICY")), HostFiles.env("FH_BANK_INSTANCE"));
        try (var channel = FileChannel.open(state.resolve("host.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var exclusive = channel.tryLock()) {
            if (exclusive == null) throw new IllegalStateException("HOST_STATE_ALREADY_IN_USE");
            var host = new TransferHost(root, state, access, origin);
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            var executor = Executors.newFixedThreadPool(4);
            server.setExecutor(executor); server.createContext("/", host::handle);
            if (!Runner.start()) throw new IllegalStateException("RUNTIME_START_FAILED");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); executor.shutdownNow(); Runner.stop(); }));
            server.start();
            new java.util.concurrent.CountDownLatch(1).await();
        }
    }
}
