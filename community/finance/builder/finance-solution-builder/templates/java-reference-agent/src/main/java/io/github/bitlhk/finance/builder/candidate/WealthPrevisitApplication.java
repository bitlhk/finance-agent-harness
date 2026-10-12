package io.github.bitlhk.finance.builder.candidate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EaRuntimeTokenSupplier;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WealthPrevisitApplication {
    private static final String SOLUTION_ID = "@@SOLUTION_ID@@";
    private static final String DISPLAY_NAME = "@@DISPLAY_NAME@@";

    private WealthPrevisitApplication() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", WealthPrevisitApplication::health);
        server.createContext("/api/v1/previsit", WealthPrevisitApplication::previsit);
        server.start();
        System.out.printf("%s started on http://127.0.0.1:%d%n", SOLUTION_ID, port);
    }

    private static void health(HttpExchange exchange) throws IOException {
        boolean configured = requiredEnvironmentPresent();
        respond(exchange, 200, Map.of(
                "status", "UP",
                "solutionId", SOLUTION_ID,
                "displayName", DISPLAY_NAME,
                "providerConfigured", configured,
                "mode", configured ? "EA_LIVE" : "ASSEMBLY_ONLY"));
    }

    private static void previsit(HttpExchange exchange) throws IOException {
        if (!requiredEnvironmentPresent()) {
            respond(exchange, 503, Map.of(
                    "status", "CONFIGURATION_REQUIRED",
                    "message", "EA runtime identity and token secret must be injected by the deployment environment"));
            return;
        }
        String customerRef = queryValue(exchange.getRequestURI(), "customerRef");
        if (customerRef == null || customerRef.isBlank()) {
            respond(exchange, 400, Map.of("status", "INVALID_INPUT", "message", "customerRef is required"));
            return;
        }
        try {
            var identity = new EmployeeAgentWealthProvider.RuntimeIdentity(
                    requiredEnv("EA_RUNTIME_ID"), requiredEnv("EA_AGENT_ID"), requiredEnv("EA_ADOPT_ID"));
            var tokenSupplier = EaRuntimeTokenSupplier.platformToolsFromEnvironment(identity);
            var provider = new EmployeeAgentWealthProvider(new EmployeeAgentWealthProvider.Config(
                    URI.create(requiredEnv("EA_BASE_URI")), identity, tokenSupplier, Duration.ofSeconds(20)));
            String requestId = UUID.randomUUID().toString();
            var context = new FinanceContextV1(
                    FinanceContextV1.SCHEMA_VERSION, requestId, requestId,
                    requiredEnv("EA_AGENT_ID"), FinanceContextV1.PrincipalType.EMPLOYEE,
                    "employee-agent", "wealth-previsit", customerRef, null,
                    FinanceContextV1.AuthenticationLevel.STRONG, Set.of("wealth:read"));
            var request = new CapabilityRequestV1(
                    CapabilityRequestV1.SCHEMA_VERSION,
                    EmployeeAgentWealthProvider.WEALTH_PREVISIT_CONTEXT_QUERY,
                    CapabilityRequestV1.Operation.QUERY,
                    context,
                    Map.of("customerRef", customerRef),
                    null, null, Map.of(), null);
            respond(exchange, 200, provider.query(request));
        } catch (Exception exception) {
            respond(exchange, 502, Map.of("status", "UPSTREAM_FAILURE", "message", exception.getMessage()));
        }
    }

    private static boolean requiredEnvironmentPresent() {
        return System.getenv("EA_BASE_URI") != null
                && System.getenv("EA_RUNTIME_ID") != null
                && System.getenv("EA_AGENT_ID") != null
                && System.getenv("EA_ADOPT_ID") != null
                && System.getenv("INTERNAL_RUNTIME_TOKEN_SECRET") != null;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String queryValue(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair[0].equals(name)) return pair.length == 2 ? pair[1] : "";
        }
        return null;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = HarnessJson.mapper().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
