package io.github.bitlhk.finance.harness.examples.wealthkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import io.github.bitlhk.finance.harness.wealth.WealthJson;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only fixtures, including a SCRIPTED (not intelligent) model endpoint. */
final class ControlledEndpoints implements AutoCloseable {
    final HttpServer server;
    final ObjectNode mobile;
    final ObjectNode ea;
    final WealthKitApplication.Scenario scenario;
    final AtomicInteger sourceReads = new AtomicInteger();
    final AtomicInteger sourceWrites = new AtomicInteger();
    final AtomicInteger modelRequests = new AtomicInteger();
    final AtomicInteger modelCompletions = new AtomicInteger();
    final List<JsonNode> modelInputs = Collections.synchronizedList(new ArrayList<>());
    final List<String> toolMessages = Collections.synchronizedList(new ArrayList<>());

    ControlledEndpoints(Path root, WealthKitApplication.Scenario scenario) throws IOException {
        this.scenario = scenario;
        Path fixtures = root.resolve("examples/solutions/wealth-facts-kit/fixtures");
        mobile = (ObjectNode) WealthJson.mapper().readTree(fixtures.resolve("mobile.json").toFile());
        ea = (ObjectNode) WealthJson.mapper().readTree(fixtures.resolve("ea.json").toFile());
        if (scenario == WealthKitApplication.Scenario.ZERO) {
            ((ObjectNode)mobile.at("/wealthAggregate/overview")).put("totalAssets", 0);
            ((ObjectNode)ea.at("/previsitContext/customer/portfolio/summary")).put("totalAssets", 0);
        }
        if (scenario == WealthKitApplication.Scenario.MISSING_RISK) {
            mobile.putObject("riskAssessment");
            ((ObjectNode)ea.at("/previsitContext/customer")).remove("riskLevel");
        }
        if (scenario == WealthKitApplication.Scenario.WRONG_SUBJECT)
            ((ObjectNode)ea.at("/previsitContext/customer")).put("customerId", "other-customer");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle); server.start();
    }
    URI base() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/chat/completions")) { model(exchange); return; }
            if (path.equals("/api/v1/sessions")) {
                bank(exchange, Map.of("sessionId", "fixture-session", "customerRef",
                        scenario == WealthKitApplication.Scenario.WRONG_SUBJECT ? "other-customer" : "customer-001")); return;
            }
            if (path.equals("/api/v1/wealth/aggregate") || path.equals("/api/v1/finance/risk-assessment")) {
                sourceReads.incrementAndGet();
                if (scenario == WealthKitApplication.Scenario.BAD_RESPONSE) { send(exchange, 200, "not-json"); return; }
                bank(exchange, mobile.get(path.endsWith("aggregate") ? "wealthAggregate" : "riskAssessment")); return;
            }
            if (path.equals("/api/internal/platform-tools/mcp")) {
                sourceReads.incrementAndGet();
                var input = WealthJson.mapper().readTree(exchange.getRequestBody());
                if (scenario == WealthKitApplication.Scenario.BAD_RESPONSE) { send(exchange, 200, "not-json"); return; }
                var content = Map.of("type", "text", "text", "EA_WEALTH_PREVISIT_CONTEXT:" + ea.get("previsitContext"));
                sendJson(exchange, Map.of("jsonrpc", "2.0", "id", input.path("id").asText(), "result",
                        Map.of("isError", false, "content", List.of(content)))); return;
            }
            if (!exchange.getRequestMethod().equals("GET")) sourceWrites.incrementAndGet();
            send(exchange, 405, "No business writes are supported by the fixture server");
        } catch (Exception failure) { send(exchange, 500, "Controlled endpoint failed"); }
    }
    private void bank(HttpExchange exchange, Object data) throws IOException {
        sendJson(exchange, Map.of("status", "SUCCESS", "requestId", "fixture-source-request",
                "traceId", "fixture-source-trace", "data", data, "sourceSystem", "controlled-bank",
                "asOf", "2026-09-01T00:00:00Z"));
    }
    private void model(HttpExchange exchange) throws IOException {
        modelRequests.incrementAndGet();
        JsonNode input = WealthJson.mapper().readTree(exchange.getRequestBody()); modelInputs.add(input);
        JsonNode lastTool = null;
        for (JsonNode message : input.path("messages")) if (message.path("role").asText().equals("tool")) lastTool = message;
        Map<String, Object> message;
        String finish;
        if (lastTool == null) {
            String tool = input.path("tools").path(0).path("function").path("name").asText();
            if (tool.isBlank()) throw new IllegalStateException("No installed tool in native model request");
            message = Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "fixture-call-1",
                    "type", "function", "function", Map.of("name", tool, "arguments",
                            scenario == WealthKitApplication.Scenario.MODEL_SELECTOR ? "{\"customerRef\":\"other\"}" : "{}"))));
            finish = "tool_calls";
        } else {
            String text = lastTool.path("content").asText();
            toolMessages.add(text);
            modelCompletions.incrementAndGet();
            message = Map.of("role", "assistant", "content", "CONTROLLED TOOL RESULT\n" + text);
            finish = "stop";
        }
        sendJson(exchange, Map.of("id", "fixture-completion", "object", "chat.completion", "created", 1788566400,
                "model", "controlled-scripted-model", "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", finish))));
    }
    private static void sendJson(HttpExchange exchange, Object body) throws IOException {
        send(exchange, 200, WealthJson.mapper().writeValueAsString(body));
    }
    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }
    public void close() { server.stop(0); }
}
