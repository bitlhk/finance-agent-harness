package io.github.bitlhk.finance.harness.providers.knowledge;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Fixed test data and service-side authority, never a real bank knowledge service. */
final class SyntheticKnowledgeService implements AutoCloseable {
    static final String MANIFEST = manifestDigest();
    static final String DATE = "2026-10-30";
    final HttpServer server;
    final String token = UUID.randomUUID().toString();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicReference<String> fault = new AtomicReference<>("");
    volatile Runnable afterRead = () -> { };
    static String manifestDigest() {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of("fixtures/manifest.json"))));
        } catch (Exception failure) { throw new IllegalStateException("SYNTHETIC_MANIFEST_MISSING"); }
    }
    SyntheticKnowledgeService() throws Exception {
        var manifest = KnowledgeBinding.JSON.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("fixtures/manifest.json")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/query", exchange -> {
            calls.incrementAndGet();
            Map<String,Object> result;
            try {
                byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                var q = KnowledgeBinding.JSON.readTree(bytes);
                boolean authorized = bytes.length <= 8192 && "POST".equals(exchange.getRequestMethod())
                    && ("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && "probe-principal".equals(q.path("principalId").asText())
                    && "probe-subject".equals(q.path("subjectRef").asText())
                    && "probe-session".equals(q.path("sessionRef").asText())
                    && "knowledge-probe".equals(q.path("purpose").asText())
                    && DATE.equals(q.path("businessDate").asText())
                    && MANIFEST.equals(q.path("manifest").asText())
                    && Set.of("synthetic-policy","synthetic-faq").contains(q.path("productId").asText())
                    && KnowledgeProvider.SEARCH.equals(q.path("capabilityId").asText());
                if (!authorized || Set.of("revoked","business403").contains(fault.get())) {
                    result = Map.of("code",403,"message","SOURCE_ACCESS_DENIED");
                } else {
                    String decision = "EVIDENCE_FOUND";
                    Map<String,Object> receipt = new LinkedHashMap<>();
                    receipt.put("schemaVersion","knowledge-receipt.v0.1");
                    receipt.put("receiptId","kr-"+UUID.randomUUID());
                    for (String key : List.of("requestId","traceId","productId","manifest","businessDate"))
                        receipt.put(key,q.path(key).asText());
                    if ("manifest".equals(fault.get())) receipt.put("manifest","b".repeat(64));
                    if ("request".equals(fault.get())) receipt.put("requestId","other-run");
                    result = Map.of("code",0,"decision",decision,"receipt",receipt,"evidence",List.of(Map.of(
                        "sourceId","synthetic-source-1","sourceVersion","1","locator","$.clauses[0]",
                        "quote",manifest.path("source").path("clauses").get(0).asText(), "synthetic",true)));
                }
            } catch (Exception failure) { result = Map.of("code",400); }
            afterRead.run();
            if ("oversize".equals(fault.get())) result=Map.of("code",0,"padding","x".repeat(262145));
            byte[] output = KnowledgeBinding.JSON.writeValueAsBytes(result);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(200,output.length);
            exchange.getResponseBody().write(output); exchange.close();
        });
        server.start();
    }
    URI endpoint() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/query"); }
    public void close() { server.stop(0); }
}
