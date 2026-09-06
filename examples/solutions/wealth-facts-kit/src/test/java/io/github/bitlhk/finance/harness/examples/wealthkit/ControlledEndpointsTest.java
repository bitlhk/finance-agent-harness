package io.github.bitlhk.finance.harness.examples.wealthkit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ControlledEndpointsTest {
    static final String MCP = "/api/internal/platform-tools/mcp";
    static String rpc(String method, String tool, String args) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"probe\",\"method\":\"" + method
                + "\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + args + "}}";
    }
    record Rejection(String method, String path, String body, int status, String reason, int mutations) { }
    @TestFactory List<DynamicTest> rejectsUnexpectedProtocolArgumentsAndMethodsWithEvidence() {
        String tool = "prepare_wealth_previsit_context";
        String customer = "{\"customer_id\":\"customer-001\"}";
        return List.of(
                new Rejection("GET", MCP, "", 405, "MCP_POST_REQUIRED", 0),
                new Rejection("POST", MCP, rpc("tools/list", tool, customer), 400, "RPC_METHOD_REJECTED", 1),
                new Rejection("POST", MCP, rpc("tools/call", "unknown_tool", customer), 400, "TOOL_NOT_ALLOWLISTED", 1),
                new Rejection("POST", MCP, rpc("tools/call", tool, "{\"customer_id\":\"other-customer\"}"), 403, "CUSTOMER_ARGUMENTS_REJECTED", 0),
                new Rejection("POST", MCP, rpc("tools/call", tool, "{\"customer_id\":\"customer-001\",\"update\":true}"), 403, "CUSTOMER_ARGUMENTS_REJECTED", 0),
                new Rejection("POST", MCP, rpc("tools/call", tool, customer), 403, "FIXTURE_IDENTITY_REQUIRED", 0),
                new Rejection("GET", "/api/v1/wealth/aggregate?customerRef=other", "", 400, "UNEXPECTED_QUERY_PARAMETERS", 0),
                new Rejection("GET", "/api/v1/wealth/aggregate", "", 403, "FIXTURE_SESSION_REQUIRED", 0),
                new Rejection("POST", MCP, "not-json", 400, "MALFORMED_REQUEST_OR_FIXTURE_FAILURE", 1)
        ).stream().map(c -> DynamicTest.dynamicTest(c.reason(), () -> {
            try (var server = new ControlledEndpoints(root(), WealthKitApplication.Scenario.NORMAL)) {
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.base().resolve(c.path()))
                        .method(c.method(), HttpRequest.BodyPublishers.ofString(c.body())).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(c.status(), response.statusCode());
                assertTrue(response.body().contains(c.reason()));
                assertEquals(0, server.sourceReads.get()); assertEquals(c.mutations(), server.sourceWrites.get());
                assertEquals(1, server.sourceAttempts.size());
                assertEquals("REJECTED", server.sourceAttempts.getFirst().decision());
                assertEquals(c.reason(), server.sourceAttempts.getFirst().reason());
                assertFalse(response.body().contains("PRIVATE_"));
                assertFalse(response.body().contains("EA_WEALTH_PREVISIT_CONTEXT"));
            }
        })).toList();
    }
    static Path root() {
        Path p = Path.of("").toAbsolutePath();
        while (!Files.isDirectory(p.resolve("capability-packs"))) p = p.getParent();
        return p;
    }
    @Test void modifyingToolCannotMasqueradeAsRead() throws Exception {
        try (var server = new ControlledEndpoints(root(), WealthKitApplication.Scenario.NORMAL)) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.base().resolve("/api/internal/platform-tools/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString("""
                    {"jsonrpc":"2.0","id":"probe","method":"tools/call","params":{"name":"update_customer","arguments":{"customer_id":"other-customer"}}}
                    """)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() >= 400);
            assertEquals(0, server.sourceReads.get());
            assertEquals(1, server.sourceWrites.get());
            assertFalse(response.body().contains("previsitContext"));
        }
    }
    @Test void bankQueryRejectsPost() throws Exception {
        try (var server = new ControlledEndpoints(root(), WealthKitApplication.Scenario.NORMAL)) {
            var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(server.base().resolve("/api/v1/wealth/aggregate"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, response.statusCode());
            assertEquals(0, server.sourceReads.get()); assertEquals(1, server.sourceWrites.get());
        }
    }
}
