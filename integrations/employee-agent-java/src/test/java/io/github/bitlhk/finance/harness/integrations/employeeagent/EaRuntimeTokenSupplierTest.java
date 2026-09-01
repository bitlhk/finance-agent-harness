package io.github.bitlhk.finance.harness.integrations.employeeagent;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EaRuntimeTokenSupplierTest {
    private static final String SECRET = "java-ea-runtime-token-conformance-secret-2026";
    private static final Instant NOW = Instant.parse("2026-09-02T02:00:00Z");

    @Test
    void signsEaCompatibleIdentityClaimsAndSignature() throws Exception {
        EaRuntimeTokenSupplier supplier = deterministic(Duration.ofSeconds(90), "irt_java_001");

        String token = supplier.tokenFor(context());
        String[] parts = token.split("\\.");
        JsonNode header = decode(parts[0]);
        JsonNode payload = decode(parts[1]);

        assertEquals(3, parts.length);
        assertEquals("HS256", header.path("alg").asText());
        assertEquals("ea-internal-runtime+jwt", header.path("typ").asText());
        assertEquals("employee-agent-internal-runtime", payload.path("iss").asText());
        assertEquals("runtime:openjiuwen-java", payload.path("sub").asText());
        assertEquals(EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE, payload.path("aud").asText());
        assertEquals("openjiuwen-java", payload.path("runtime_id").asText());
        assertEquals("jiuwen_lgj-wealth", payload.path("agent_id").asText());
        assertEquals("lgj-wealth", payload.path("adopt_id").asText());
        assertEquals(NOW.getEpochSecond(), payload.path("iat").asLong());
        assertEquals(NOW.getEpochSecond() - 5, payload.path("nbf").asLong());
        assertEquals(NOW.getEpochSecond() + 90, payload.path("exp").asLong());
        assertEquals("irt_java_001", payload.path("jti").asText());
        assertEquals(parts[2], sign(parts[0] + "." + parts[1]));
    }

    @Test
    void createsOneJtiPerRequest() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        EaRuntimeTokenSupplier supplier = new EaRuntimeTokenSupplier(
                identity(),
                EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE,
                secret(),
                Duration.ofSeconds(60),
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "irt_java_" + sequence.incrementAndGet());

        String first = supplier.tokenFor(context());
        String second = supplier.tokenFor(context());

        assertNotEquals(first, second);
        assertEquals("irt_java_1", payload(first).path("jti").asText());
        assertEquals("irt_java_2", payload(second).path("jti").asText());
    }

    @Test
    void boundsTtlToEaMinimumAndMaximum() throws Exception {
        String minimum = deterministic(Duration.ofSeconds(1), "irt_min").tokenFor(context());
        String maximum = deterministic(Duration.ofSeconds(900), "irt_max").tokenFor(context());

        assertEquals(30, payload(minimum).path("exp").asLong() - payload(minimum).path("iat").asLong());
        assertEquals(300, payload(maximum).path("exp").asLong() - payload(maximum).path("iat").asLong());
    }

    @Test
    void rejectsWeakSecretAndInvalidEaIdentity() {
        EaRuntimeTokenSupplier weak = new EaRuntimeTokenSupplier(
                identity(),
                EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE,
                () -> "too-short".getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(60));

        assertThrows(IllegalStateException.class, () -> weak.tokenFor(context()));
        assertThrows(IllegalArgumentException.class, () -> new EaRuntimeTokenSupplier(
                new EmployeeAgentWealthProvider.RuntimeIdentity("java-runtime", "agent-wealth", "invalid-adopt"),
                EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE,
                secret(),
                Duration.ofSeconds(60)));
    }

    private static EaRuntimeTokenSupplier deterministic(Duration ttl, String jti) {
        return new EaRuntimeTokenSupplier(
                identity(),
                EaRuntimeTokenSupplier.PLATFORM_TOOLS_AUDIENCE,
                secret(),
                ttl,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> jti);
    }

    private static EaRuntimeTokenSupplier.SecretSupplier secret() {
        return () -> SECRET.getBytes(StandardCharsets.UTF_8);
    }

    private static EmployeeAgentWealthProvider.RuntimeIdentity identity() {
        return new EmployeeAgentWealthProvider.RuntimeIdentity(
                "openjiuwen-java", "jiuwen_lgj-wealth", "lgj-wealth");
    }

    private static FinanceContextV1 context() {
        return new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "request-java-token-001",
                "trace-java-token-001",
                "employee-001",
                FinanceContextV1.PrincipalType.EMPLOYEE,
                "employee-agent",
                "WEALTH_PREVISIT",
                "C-005",
                "session-java-token-001",
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("wealth:customer:read"));
    }

    private static JsonNode payload(String token) throws Exception {
        return decode(token.split("\\.")[1]);
    }

    private static JsonNode decode(String part) throws Exception {
        return HarnessJson.mapper().readTree(Base64.getUrlDecoder().decode(part));
    }

    private static String sign(String signingInput) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
    }
}
