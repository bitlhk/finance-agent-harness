package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider.HostSession;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Simulator IAM plus a separately provisioned local read-access policy, never capability-derived grants. */
final class BankAccess {
    final URI base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String login, secret;
    private final Path policy;
    static final String PURPOSE = "LOCAL_READONLY_DESCRIPTION";
    static final Set<String> READ_SCOPES = Set.of("wealth:facts:read", "wealth:customer:read", "account:read", "cashflow:read");
    final String purpose;
    private final Set<String> acceptedScopes;
    private long identityCalls;

    BankAccess() {
        this(false, Set.of());
    }
    BankAccess(boolean tasks, Set<String> taskRequirements) {
        this(tasks, taskRequirements, System.getenv());
    }
    BankAccess(boolean tasks, Set<String> taskRequirements, Map<String, String> environment) {
        if (tasks && !"true".equals(environment.get("FH_TASK_SIMULATOR_ENABLED")))
            throw new SecurityException("SIMULATOR_TASK_NOT_ENABLED");
        purpose = tasks ? "LOCAL_SIMULATOR_TASK" : PURPOSE;
        var accepted = new HashSet<>(READ_SCOPES);
        // The host still intersects these requested read scopes with the
        // administrator-provisioned local policy. QUERY-only audit scenarios
        // therefore gain no implicit authority, but can use their declared
        // scopes when the policy explicitly grants them.
        accepted.addAll(taskRequirements);
        acceptedScopes = Set.copyOf(accepted);
        base = URI.create(requiredEnvironment(environment, "FH_BANK_URL"));
        if (!"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null)
            throw new IllegalArgumentException("LOCAL_SIMULATOR_CONNECTION_REQUIRED");
        login = requiredEnvironment(environment, "FH_BANK_LOGIN"); secret = requiredEnvironment(environment, "FH_BANK_SECRET");
        policy = Path.of(requiredEnvironment(environment, "FH_ACCESS_POLICY"));
        try {
            var response = http.send(HttpRequest.newBuilder(base.resolve("/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var health = HarnessJson.mapper().readTree(response.body());
            if (response.statusCode() != 200 || !"non-ai-banking".equals(health.path("service").asText())
                    || !"simulator".equals(health.path("providerMode").asText())) throw new SecurityException();
        } catch (Exception invalid) { throw new IllegalArgumentException("BANK_SIMULATOR_NOT_AVAILABLE"); }
    }

    Login open(String hostSession) throws Exception {
        JsonNode result = call("POST", "/api/v1/sessions", null,
                Map.of("loginName", login, "loginSecret", secret, "deviceId", hostSession, "channel", "M"));
        String bankSession = required(result, "sessionId");
        Login state = new Login(hostSession, bankSession, required(result, "customerRef"),
                Instant.parse(required(result, "expiresAt")));
        try { current(state); return state; }
        catch (Exception failure) { revoke(state); throw failure; }
    }

    Login open(HostTaskScope scope) throws Exception {
        var saved = scope.sourceSession(base.toString());
        if (saved != null) {
            var state = new Login(saved.hostSession(), saved.bankSession(), saved.customer(), saved.expires());
            // Reuse the bank's write-owner session; a fresh login does not inherit task focus.
            current(state);
            return state;
        }
        var state = open(scope.sessionId);
        try { scope.saveSourceSession(state, base.toString()); return state; }
        catch (Exception failure) { revoke(state); throw failure; }
    }

    synchronized BoundFinanceProvider.Grant current(Login state) {
        return current(state, state.observation);
    }

    synchronized BoundFinanceProvider.Grant current(Login state, ExecutionArchive.Request observation) {
        try {
            if (state.revoked || !state.expires.isAfter(Instant.now())) throw new SecurityException();
            JsonNode rules = HarnessJson.mapper().readTree(policy.toFile());
            if (!"local-simulator-access.v0.1".equals(rules.path("schemaVersion").asText())
                    || !base.toString().equals(rules.path("bankUrl").asText())) throw new SecurityException();
            JsonNode rule = rules.path("subjects").path(state.customer);
            Instant expiry = Instant.parse(rule.path("expiresAt").asText());
            Set<String> policyScopes = new HashSet<>(); rule.path("scopes").forEach(s -> policyScopes.add(s.asText()));
            Set<String> scopes = new HashSet<>(policyScopes); scopes.retainAll(acceptedScopes);
            if (!rule.path("enabled").asBoolean() || !expiry.isAfter(Instant.now())
                    || scopes.isEmpty()) throw new SecurityException();
            JsonNode identity = call("GET", "/api/v1/sessions/current", state.bankSession, null, observation);
            if (!state.customer.equals(required(identity, "customerRef"))
                    || !state.bankSession.equals(required(identity, "sessionId"))) throw new SecurityException();
            Instant actualExpiry = Instant.parse(required(identity, "expiresAt"));
            if (!actualExpiry.isAfter(Instant.now())) throw new SecurityException();
            return new BoundFinanceProvider.Grant("simulator:" + state.customer, FinanceContextV1.PrincipalType.CUSTOMER,
                    Set.of(state.customer), Set.of(purpose), scopes, state.hostSession,
                    "simulator-session-and-local-policy", Collections.min(List.of(expiry, state.expires, actualExpiry)));
        } catch (Exception denied) { throw new SecurityException("CURRENT_AUTHORIZATION_REJECTED"); }
    }

    HostSession bankSession(Login state) {
        var grant = current(state);
        return new HostSession(state.bankSession, state.customer, grant.principalId(), state.hostSession, grant.expiresAt());
    }

    void requireStrong(Login state) {
        current(state);
        try {
            var identity = call("GET", "/api/v1/sessions/current", state.bankSession, null, state.observation);
            if (!state.customer.equals(required(identity, "customerRef"))
                    || !state.bankSession.equals(required(identity, "sessionId"))
                    || !Set.of("I2", "I3").contains(identity.path("identityStrength").asText()))
                throw new SecurityException("STEP_UP_REQUIRED");
        } catch (Exception denied) { throw new SecurityException("STEP_UP_REQUIRED"); }
    }
    void stepUp(Login state, String verificationCode) throws Exception {
        current(state);
        if (verificationCode == null || verificationCode.isBlank() || verificationCode.length() > 64)
            throw new IllegalArgumentException("VERIFICATION_CODE_REQUIRED");
        call("POST", "/api/v1/sessions/step-up", state.bankSession, Map.of("verificationCode", verificationCode), state.observation);
        requireStrong(state);
    }

    synchronized void revoke(Login state) {
        state.revoked = true;
        try { call("DELETE", "/api/v1/sessions/current", state.bankSession, null); }
        catch (Exception ignored) { /* Local denial remains effective when the source is unavailable. */ }
    }
    synchronized long identityCalls() { return identityCalls; }

    private JsonNode call(String method, String path, String session, Object body) throws Exception {
        return call(method, path, session, body, null);
    }
    private JsonNode call(String method, String path, String session, Object body, ExecutionArchive.Request observation) throws Exception {
        identityCalls++;
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("X-Channel", "M");
        if (session != null) request.header("X-Session-Id", session);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(HarnessJson.mapper().writeValueAsBytes(body)));
        var built = request.build();
        java.net.http.HttpResponse<String> response;
        try (var span = observation == null ? null : observation.run.start(observation.root.id(), "authorization-http", "attempt",
                Map.of("method", method, "route", path, "providerId",
                        io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider.PROVIDER_ID))) {
            try {
                if (span != null) span.dispatched();
                response = http.send(built, HttpResponse.BodyHandlers.ofString());
                if (span != null) span.finish(io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.RETURNED,
                        Map.of("httpStatus", Integer.toString(response.statusCode())));
            } catch (Exception failure) {
                if (span != null) span.finish(io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.ERROR,
                        Map.of("exceptionType", failure.getClass().getSimpleName()));
                throw failure;
            }
        }
        if (response.statusCode() != 200) throw new SecurityException("SIMULATOR_IDENTITY_REJECTED");
        JsonNode value = HarnessJson.mapper().readTree(response.body());
        if (!Set.of("SUCCESS", "COMPLETED").contains(value.path("status").asText())) throw new SecurityException();
        return value.path("data");
    }

    static String env(String key) {
        return requiredEnvironment(System.getenv(), key);
    }
    private static String requiredEnvironment(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("CONFIGURATION_MISSING: " + key);
        return value;
    }
    private static String required(JsonNode node, String field) {
        if (!node.path(field).isTextual() || node.path(field).asText().isBlank()) throw new SecurityException("INVALID_IDENTITY_RESPONSE");
        return node.path(field).asText();
    }
    static final class Login {
        final String hostSession, bankSession, customer;
        final Instant expires;
        volatile boolean revoked;
        volatile ExecutionArchive.Request observation;
        Login(String hostSession, String bankSession, String customer, Instant expires) {
            this.hostSession = hostSession; this.bankSession = bankSession; this.customer = customer; this.expires = expires;
        }
    }
}
