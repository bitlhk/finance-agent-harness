package io.github.bitlhk.finance.harness.integrations.agentscope.reference;

import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Test-only scripted HTTP fixture, not an Agent loop or production model replacement. */
public final class ControlledModel implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Queue<Response> script = new ConcurrentLinkedQueue<>();
    public final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    public record Response(int status, Object body, Runnable beforeResponse, Map<String, String> headers) {
        public Response(int status, Object body, Runnable beforeResponse) { this(status, body, beforeResponse, Map.of()); }
    }
    public ControlledModel(Response... responses) throws IOException {
        script.addAll(List.of(responses)); server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var row = new LinkedHashMap<String, Object>(); row.put("rawBody", body); row.put("body", HarnessJson.mapper().readTree(body));
            row.put("receivedAt", Instant.now().toString()); row.put("nanoTime", System.nanoTime()); row.put("uri", exchange.getRequestURI().toString());
            requests.add(row);
            Response next = script.poll(); if (next == null) next = text("UNEXPECTED_EXTRA_MODEL_HTTP");
            row.put("status", next.status()); next.beforeResponse().run();
            byte[] bytes = HarnessJson.mapper().writeValueAsBytes(next.body());
            next.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(next.status(), bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
    }
    public void enqueue(Response response) { script.add(response); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"); }
    public static Response text(String text) { return response(Map.of("role", "assistant", "content", text), "stop"); }
    public static Response tool(String name, Map<String, Object> arguments) {
        try { return response(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "tool-" + UUID.randomUUID(), "type", "function",
                "function", Map.of("name", name, "arguments", HarnessJson.mapper().writeValueAsString(arguments))))), "tool_calls"); }
        catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Response response(Object message, String reason) { return new Response(200, Map.of("id", "controlled", "model", "controlled",
            "object", "chat.completion", "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", reason))), () -> {}); }
    public static Response failure(Runnable before) { return new Response(503, Map.of("error", Map.of("message", "Controlled model failure")), before); }
    public static Response redirect(int status, URI target, Runnable before) { return new Response(status, Map.of("redirect", true), before, Map.of("Location", target.toString())); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
