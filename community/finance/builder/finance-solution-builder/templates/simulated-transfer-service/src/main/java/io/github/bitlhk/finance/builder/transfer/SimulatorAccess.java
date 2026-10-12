package io.github.bitlhk.finance.builder.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.providers.nonaibanking.NonAiBankingProvider;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

final class SimulatorAccess {
    static final String PURPOSE = "SIMULATED_TRANSFER";
    static final Set<String> SCOPES = Set.of("account:read", "transfer:create");
    final URI base;
    final Path policy;
    final String instance;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    record Session(String id, String csrf, String bankSession, String customer, Instant expiresAt) { }

    SimulatorAccess(URI base, Path policy, String instance) throws Exception {
        if (!"http".equals(base.getScheme()) || !Set.of("127.0.0.1", "localhost", "[::1]").contains(base.getHost())
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("LOCAL_SIMULATOR_ONLY");
        this.base = base; this.policy = policy; this.instance = instance;
        var health = raw("GET", "/health", null, null);
        if (!health.path("providerMode").asText().equals("simulator")
                || !health.path("service").asText().equals("non-ai-banking")) throw new SecurityException("NOT_SIMULATOR");
        verifyInstance();
    }

    void verifyInstance() throws Exception {
        if (!instance.equals(raw("GET", "/api/v1/sessions/runtime", null, null).path("instanceId").asText()))
            throw new SecurityException("SIMULATOR_INSTANCE_CHANGED");
    }

    Session open(String login, String secret) throws Exception {
        String id = UUID.randomUUID().toString();
        var source = call("POST", "/api/v1/sessions", null, Map.of("loginName", login,
                "loginSecret", secret, "deviceId", id, "channel", "M"));
        var session = new Session(id, UUID.randomUUID().toString(), text(source, "sessionId"),
                text(source, "customerRef"), Instant.parse(text(source, "expiresAt")));
        current(session);
        return session;
    }

    BoundFinanceProvider.Grant current(Session session) {
        try {
            if (!session.expiresAt().isAfter(Instant.now())) throw new SecurityException();
            var rules = HostFiles.JSON.readTree(policy.toFile());
            if (!"simulated-transfer-access.v0.1".equals(rules.path("schemaVersion").asText())
                    || !base.toString().equals(rules.path("bankUrl").asText())
                    || !instance.equals(rules.path("simulatorInstance").asText())) throw new SecurityException();
            var subject = rules.path("subjects").path(session.customer());
            var expiry = Instant.parse(subject.path("expiresAt").asText());
            Set<String> granted = new HashSet<>(); subject.path("scopes").forEach(s -> granted.add(s.asText()));
            if (!subject.path("enabled").asBoolean() || !expiry.isAfter(Instant.now())
                    || !SCOPES.containsAll(granted) || granted.isEmpty()) throw new SecurityException();
            verifyInstance();
            var identity = call("GET", "/api/v1/sessions/current", session.bankSession(), null);
            if (!session.customer().equals(text(identity, "customerRef"))
                    || !session.bankSession().equals(text(identity, "sessionId"))) throw new SecurityException();
            var actual = Instant.parse(text(identity, "expiresAt"));
            if (!actual.isAfter(Instant.now())) throw new SecurityException();
            return new BoundFinanceProvider.Grant("simulator:" + session.customer(),
                    FinanceContextV1.PrincipalType.CUSTOMER, Set.of(session.customer()), Set.of(PURPOSE), granted,
                    session.id(), "isolated-simulator-session-and-host-policy",
                    Collections.min(List.of(actual, expiry, session.expiresAt())));
        } catch (Exception rejected) { throw new SecurityException("AUTHORIZATION_REJECTED"); }
    }

    void require(Session session, Set<String> scopes) {
        if (!current(session).scopes().containsAll(scopes)) throw new SecurityException("INSUFFICIENT_SCOPES");
    }

    NonAiBankingProvider.HostSession binding(Session session) {
        var grant = current(session);
        return new NonAiBankingProvider.HostSession(session.bankSession(), session.customer(), grant.principalId(),
                session.id(), grant.expiresAt());
    }

    void requireStrong(Session session) throws Exception {
        require(session, SCOPES);
        var identity = call("GET", "/api/v1/sessions/current", session.bankSession(), null);
        if (!Set.of("I2", "I3").contains(identity.path("identityStrength").asText()))
            throw new SecurityException("STEP_UP_REQUIRED");
    }

    Map<String, Object> choices(Session session) throws Exception {
        require(session, SCOPES);
        var accounts = call("GET", "/api/v1/accounts?page=1&pageSize=100", session.bankSession(), null);
        var beneficiaries = call("GET", "/api/v1/beneficiaries", session.bankSession(), null);
        require(session, SCOPES);
        return Map.of("accounts", accounts, "beneficiaries", beneficiaries, "source", "non-ai-banking", "asOf", Instant.now());
    }

    void validateTransfer(Session session, Map<String, Object> parameters) {
        try {
            require(session, SCOPES);
            if (!Set.of("fromAccountId", "beneficiaryId", "amount", "purpose").containsAll(parameters.keySet()))
                throw new IllegalArgumentException();
            var amount = new BigDecimal(String.valueOf(parameters.get("amount")));
            var rules = HostFiles.JSON.readTree(policy.toFile());
            var max = new BigDecimal(rules.path("maxAmountCny").asText());
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2 || amount.compareTo(max) > 0
                    || max.signum() <= 0 || max.compareTo(new BigDecimal("100.00")) > 0) throw new IllegalArgumentException();
            var choices = choices(session);
            JsonNode accounts = (JsonNode) choices.get("accounts");
            JsonNode items = accounts.isArray() ? accounts : accounts.path("accounts");
            boolean account = false, beneficiary = false;
            for (var item : items) if (item.path("accountId").asText().equals(parameters.get("fromAccountId"))
                    && item.path("currency").asText().equals("CNY") && item.path("status").asText().equals("NORMAL")) account = true;
            for (var item : (JsonNode) choices.get("beneficiaries"))
                if (item.path("beneficiaryId").asText().equals(parameters.get("beneficiaryId"))
                        && item.path("internal").asBoolean() && item.path("status").asText().equals("ACTIVE")) beneficiary = true;
            if (!account || !beneficiary) throw new IllegalArgumentException();
        } catch (SecurityException denied) { throw denied; }
        catch (Exception invalid) { throw new IllegalArgumentException("TRANSFER_INPUT_NOT_ALLOWED"); }
    }

    JsonNode stepUp(Session session, String code) throws Exception {
        require(session, SCOPES);
        var result = call("POST", "/api/v1/sessions/step-up", session.bankSession(), Map.of("verificationCode", code));
        require(session, SCOPES);
        return MapJson.identity(result);
    }

    JsonNode call(String method, String path, String session, Object body) throws Exception {
        var value = raw(method, path, session, body);
        if (!Set.of("SUCCESS", "COMPLETED").contains(value.path("status").asText()))
            throw new SecurityException("SIMULATOR_REJECTED");
        return value.path("data");
    }
    private JsonNode raw(String method, String path, String session, Object body) throws Exception {
        var request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json").header("X-Channel", "M");
        if (session != null) request.header("X-Session-Id", session);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(HostFiles.JSON.writeValueAsBytes(body)));
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new SecurityException("SIMULATOR_REQUEST_REJECTED");
        return HostFiles.JSON.readTree(response.body());
    }
    static String text(JsonNode value, String key) {
        if (!value.path(key).isTextual() || value.path(key).asText().isBlank()) throw new SecurityException("INVALID_SOURCE_IDENTITY");
        return value.path(key).asText();
    }
    private static class MapJson {
        static JsonNode identity(JsonNode source) {
            return HostFiles.JSON.valueToTree(Map.of("identityStrength", source.path("identityStrength").asText(),
                    "expiresAt", source.path("expiresAt").asText()));
        }
    }
}
