package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Map;

/** A fresh SDK session per invocation: no bank tokens, cross-user pools or local response fallback. */
final class CuratedMcpTransport {
    private record ClientLifetime(McpSyncClient client, BoundedHttpClient http) implements AutoCloseable {
        @Override public void close() {
            // SDK 0.17.2 close() only subscribes to asynchronous transport cleanup.
            // Its synchronous graceful close waits (at most 10s) and reports failure.
            boolean interrupted = Thread.interrupted();
            try {
                http.stopReads();
                if (!client.closeGracefully()) {
                    client.close();
                    throw new IllegalStateException("CURATED_MCP_CLOSE_INCOMPLETE");
                }
                http.checkHealthy();
                if (interrupted) throw new java.util.concurrent.CancellationException("CURATED_CALL_INTERRUPTED");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    McpSchema.CallToolResult call(CuratedSourceConfig config, Duration timeout, CuratedTools.Tool tool,
            Map<String, Object> arguments, Runnable authority) {
        return call(config, timeout, tool, arguments, authority, null);
    }
    McpSchema.CallToolResult call(CuratedSourceConfig config, Duration timeout, CuratedTools.Tool tool,
            Map<String, Object> arguments, Runnable authority, McpHttpObservation observation) {
        authority.run();
        var endpoint = config.endpoint();
        try (var http = new BoundedHttpClient.Builder(timeout, config.runtimePolicy() != null,
                config.runtimePolicy() == null ? null : config.runtimePolicy().sourceIdentitySha256()).observations(observation)) {
            var transport = HttpClientStreamableHttpTransport.builder("http://" + endpoint.getRawAuthority())
                    .endpoint(endpoint.getRawPath()).connectTimeout(timeout)
                    .clientBuilder(http)
                    .jsonMapper(new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(CuratedTools.responseMapper()))
                    .resumableStreams(false).openConnectionOnStartup(false)
                    .httpRequestCustomizer((request, method, uri, body, context) -> {
                        if (!uri.equals(endpoint)) throw new SecurityException("CURATED_ENDPOINT_CHANGED");
                        // Only SDK-owned DELETE can bypass authority refresh, never endpoint pinning.
                        if (!"DELETE".equals(method)) {
                            authority.run();
                            http.client().checkHealthy();
                        }
                        request.timeout(timeout);
                    }).build();
            var client = McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout)
                    .enableCallToolSchemaCaching(false).build();
            try (var lifetime = new ClientLifetime(client, http.client())) {
                client.initialize();
                http.client().checkHealthy();
                var listed = client.listTools();
                http.client().checkHealthy();
                if (listed.nextCursor() != null) throw new IllegalArgumentException("CURATED_TOOL_LIST_PAGINATED");
                var matches = listed.tools().stream().filter(t -> t.name().equals(tool.name())).toList();
                if (matches.size() != 1 || !CuratedTools.schemaPin(matches.getFirst().inputSchema()).equals(config.toolSchemaPins().get(tool.name())))
                    throw new IllegalArgumentException("CURATED_TOOL_SCHEMA_MISMATCH");
                authority.run();
                var result = client.callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
                http.client().checkHealthy();
                return result;
            } catch (RuntimeException failure) {
                http.client().checkHealthy();
                throw failure;
            }
        }
    }
}
