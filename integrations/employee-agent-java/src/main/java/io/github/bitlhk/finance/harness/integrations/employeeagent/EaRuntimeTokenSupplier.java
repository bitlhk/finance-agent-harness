package io.github.bitlhk.finance.harness.integrations.employeeagent;

import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeIdentity;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentRuntimeTokenSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Issues one short-lived EA runtime identity token for each managed MCP request. */
public final class EaRuntimeTokenSupplier implements EmployeeAgentRuntimeTokenSupplier {
    public static final String PLATFORM_TOOLS_AUDIENCE = "urn:ea:internal-mcp:platform-tools";
    public static final String CUSTOMER_CONTEXT_AUDIENCE = "urn:ea:internal-mcp:customer-context";
    public static final String DEFAULT_SECRET_ENVIRONMENT_VARIABLE = "INTERNAL_RUNTIME_TOKEN_SECRET";
    public static final String DEFAULT_TTL_ENVIRONMENT_VARIABLE = "INTERNAL_RUNTIME_TOKEN_TTL_SECONDS";

    private static final String ISSUER = "employee-agent-internal-runtime";
    private static final String TOKEN_TYPE = "ea-internal-runtime+jwt";
    private static final int MIN_SECRET_BYTES = 32;
    private static final long DEFAULT_TTL_SECONDS = 300;
    private static final Pattern RUNTIME_ID = Pattern.compile("[A-Za-z0-9._:-]{3,128}");
    private static final Pattern AGENT_ID = Pattern.compile("[A-Za-z0-9._:-]{3,160}");
    private static final Pattern ADOPT_ID = Pattern.compile("lgj-[A-Za-z0-9_-]{3,60}");

    private final EmployeeAgentRuntimeIdentity identity;
    private final String audience;
    private final SecretSupplier secretSupplier;
    private final long ttlSeconds;
    private final Clock clock;
    private final Supplier<String> jtiSupplier;

    public EaRuntimeTokenSupplier(
            EmployeeAgentRuntimeIdentity identity,
            String audience,
            SecretSupplier secretSupplier,
            Duration ttl) {
        this(identity, audience, secretSupplier, ttl, Clock.systemUTC(),
                () -> "irt_" + UUID.randomUUID());
    }

    EaRuntimeTokenSupplier(
            EmployeeAgentRuntimeIdentity identity,
            String audience,
            SecretSupplier secretSupplier,
            Duration ttl,
            Clock clock,
            Supplier<String> jtiSupplier) {
        this.identity = validateIdentity(Objects.requireNonNull(identity, "identity"));
        this.audience = requiredText(audience, "audience");
        this.secretSupplier = Objects.requireNonNull(secretSupplier, "secretSupplier");
        this.ttlSeconds = boundedTtl(ttl);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jtiSupplier = Objects.requireNonNull(jtiSupplier, "jtiSupplier");
    }

    public static EaRuntimeTokenSupplier platformToolsFromEnvironment(
            EmployeeAgentRuntimeIdentity identity) {
        return new EaRuntimeTokenSupplier(
                identity,
                PLATFORM_TOOLS_AUDIENCE,
                environmentSecret(DEFAULT_SECRET_ENVIRONMENT_VARIABLE),
                environmentTtl(DEFAULT_TTL_ENVIRONMENT_VARIABLE));
    }

    public static EaRuntimeTokenSupplier customerContextFromEnvironment(
            EmployeeAgentRuntimeIdentity identity) {
        return new EaRuntimeTokenSupplier(
                identity,
                CUSTOMER_CONTEXT_AUDIENCE,
                environmentSecret(DEFAULT_SECRET_ENVIRONMENT_VARIABLE),
                environmentTtl(DEFAULT_TTL_ENVIRONMENT_VARIABLE));
    }

    public static SecretSupplier environmentSecret(String variableName) {
        String normalizedName = requiredText(variableName, "variableName");
        return () -> {
            String value = System.getenv(normalizedName);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(normalizedName + " is required");
            }
            return value.trim().getBytes(StandardCharsets.UTF_8);
        };
    }

    public static Duration environmentTtl(String variableName) {
        String normalizedName = requiredText(variableName, "variableName");
        String raw = System.getenv(normalizedName);
        if (raw == null || raw.isBlank()) {
            return Duration.ofSeconds(DEFAULT_TTL_SECONDS);
        }
        try {
            return Duration.ofSeconds(Long.parseLong(raw.trim()));
        } catch (NumberFormatException ignored) {
            return Duration.ofSeconds(DEFAULT_TTL_SECONDS);
        }
    }

    @Override
    public String tokenFor(FinanceContextV1 context) throws Exception {
        Objects.requireNonNull(context, "context");
        byte[] suppliedSecret = Objects.requireNonNull(secretSupplier.signingSecret(),
                "signing secret");
        byte[] secret = suppliedSecret.clone();
        try {
            if (secret.length < MIN_SECRET_BYTES) {
                throw new IllegalStateException("EA runtime token secret must contain at least 32 bytes");
            }
            long now = clock.instant().getEpochSecond();
            String jti = requiredText(jtiSupplier.get(), "jti");
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "HS256");
            header.put("typ", TOKEN_TYPE);
            header.put("kid", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(secret)).substring(0, 16));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", ISSUER);
            payload.put("sub", "runtime:" + identity.runtimeId());
            payload.put("aud", audience);
            payload.put("iat", now);
            payload.put("nbf", now - 5);
            payload.put("exp", now + ttlSeconds);
            payload.put("jti", jti);
            payload.put("runtime_id", identity.runtimeId());
            payload.put("agent_id", identity.agentId());
            payload.put("adopt_id", identity.adoptId());

            String signingInput = encodeJson(header) + "." + encodeJson(payload);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + signature;
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static String encodeJson(Map<String, Object> value) throws Exception {
        byte[] json = HarnessJson.mapper().writeValueAsBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private static EmployeeAgentRuntimeIdentity validateIdentity(
            EmployeeAgentRuntimeIdentity identity) {
        if (!RUNTIME_ID.matcher(identity.runtimeId()).matches()) {
            throw new IllegalArgumentException("runtimeId does not match the EA runtime identity contract");
        }
        if (!AGENT_ID.matcher(identity.agentId()).matches()) {
            throw new IllegalArgumentException("agentId does not match the EA runtime identity contract");
        }
        if (!ADOPT_ID.matcher(identity.adoptId()).matches()) {
            throw new IllegalArgumentException("adoptId does not match the EA runtime identity contract");
        }
        return identity;
    }

    private static long boundedTtl(Duration ttl) {
        long seconds = ttl == null ? DEFAULT_TTL_SECONDS : ttl.getSeconds();
        return Math.min(300, Math.max(30, seconds));
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    @FunctionalInterface
    public interface SecretSupplier {
        byte[] signingSecret();
    }
}
