package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** A fresh SDK session per invocation: no bank tokens, cross-user pools or local response fallback. */
final class CuratedMcpTransport {
    private record ClientLifetime(McpSyncClient client) implements AutoCloseable {
        @Override public void close() {
            // SDK 0.17.2 close() only subscribes to asynchronous transport cleanup.
            // Its synchronous graceful close waits (at most 10s) and reports failure.
            if (!client.closeGracefully()) {
                client.close();
                throw new IllegalStateException("CURATED_MCP_CLOSE_INCOMPLETE");
            }
        }
    }
    McpSchema.CallToolResult call(CuratedSourceConfig config, Duration timeout, CuratedTools.Tool tool,
            Map<String, Object> arguments, Runnable authority) {
        authority.run();
        var endpoint = config.endpoint();
        var transport = HttpClientStreamableHttpTransport.builder("http://" + endpoint.getRawAuthority())
                .endpoint(endpoint.getRawPath()).connectTimeout(timeout)
                .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout))
                .resumableStreams(false).openConnectionOnStartup(false)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (!uri.equals(endpoint)) throw new SecurityException("CURATED_ENDPOINT_CHANGED");
                    // Only the SDK-owned session DELETE is cleanup, not a new business read.
                    // It must remain possible after revocation; endpoint pinning still applies.
                    if (!"DELETE".equals(method)) authority.run();
                    request.timeout(timeout);
                }).build();
        var client = McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout)
                .enableCallToolSchemaCaching(false).build();
        try (var lifetime = new ClientLifetime(client)) {
            client.initialize();
            var listed = client.listTools();
            if (listed.nextCursor() != null) throw new IllegalArgumentException("CURATED_TOOL_LIST_PAGINATED");
            var matches = listed.tools().stream().filter(t -> t.name().equals(tool.name())).toList();
            if (matches.size() != 1 || !CuratedTools.schemaPin(matches.getFirst().inputSchema()).equals(config.toolSchemaPins().get(tool.name())))
                throw new IllegalArgumentException("CURATED_TOOL_SCHEMA_MISMATCH");
            authority.run();
            return client.callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
        }
    }
}
