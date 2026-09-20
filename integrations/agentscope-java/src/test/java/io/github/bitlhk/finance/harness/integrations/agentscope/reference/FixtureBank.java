package io.github.bitlhk.finance.harness.integrations.agentscope.reference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Test-only loopback source; business mapping remains in the unchanged NonAiBankingProvider. */
public final class FixtureBank implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Path root;
    public final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    public volatile Consumer<String> beforeResponse = path -> {};
    public volatile boolean analysisUnavailable;
    public volatile int accountStatus = 200;
    public FixtureBank(Path repositoryRoot) throws IOException {
        root = repositoryRoot;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(workers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.add(Map.of("method", exchange.getRequestMethod(), "uri", exchange.getRequestURI().toString(),
                    "requestId", Objects.toString(exchange.getRequestHeaders().getFirst("X-Request-Id"), ""),
                    "authorization", Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "")));
            int status = 200; Object body;
            if (!"GET".equals(exchange.getRequestMethod())) { status = 405; body = Map.of("error", "READ_ONLY"); }
            else if (analysisUnavailable && path.endsWith("spending-analysis")) {
                status = 503; body = Map.of("error", Map.of("failureClass", "TEMPORARY_FAILURE", "sourceCode", "CONTROLLED_UNAVAILABLE", "message", "Analysis source unavailable"));
            } else if (path.startsWith("/api/v1/accounts")) {
                status = accountStatus;
                body = status == 200 ? envelope(Map.of("accounts", List.of(Map.of("accountId", "fixture-account", "currency", "CNY", "availableBalance", "12345.67"))))
                        : Map.of("error", Map.of("code", "CONTROLLED_BANK_AUTH_FAILURE", "message", "Bank session rejected"));
            } else if (path.startsWith("/api/v1/finance/")) {
                String type = path.contains("spending-analysis") ? "analysis" : "summary";
                boolean policy = path.endsWith("/readiness");
                ObjectNode data = (ObjectNode) HarnessJson.mapper().readTree(Files.readAllBytes(root.resolve("conformance/fixtures/cashflow/source/" + type + (policy ? "-policy" : "") + ".json")));
                if (!policy) {
                    String query = exchange.getRequestURI().getRawQuery();
                    String period = query == null ? "2026-08" : Arrays.stream(query.split("&")).filter(v -> v.startsWith("period="))
                            .map(v -> URLDecoder.decode(v.substring(7), StandardCharsets.UTF_8)).findFirst().orElse("2026-08");
                    data.put("period", period);
                    if (data.has("trend")) for (int i = 0; i < data.path("trend").size(); i++)
                        ((ObjectNode) data.path("trend").get(i)).put("period", YearMonth.parse(period).minusMonths(5 - i).toString());
                }
                body = policy ? data : envelope(data);
            } else { status = 404; body = Map.of("error", "UNAVAILABLE"); }
            beforeResponse.accept(path);
            byte[] bytes = HarnessJson.mapper().writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        }); server.start();
    }
    private static Map<String, Object> envelope(Object value) { return Map.of("status", "SUCCESS", "data", value, "sourceSystem", "controlled-cashflow", "asOf", "2026-09-01T01:00:00Z"); }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public long customerReads() { return requests.stream().filter(r -> !r.get("uri").toString().contains("readiness")).count(); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }
}
