package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/** A fresh SDK session per invocation: no bank tokens, cross-user pools or local response fallback. */
final class CuratedMcpTransport {
    McpSchema.CallToolResult call(CuratedSourceConfig config, Duration timeout, CuratedTools.Tool tool,
            Map<String, Object> arguments, Runnable authority) {
        authority.run();
        var endpoint = config.endpoint();
        var transport = HttpClientStreamableHttpTransport.builder("http://" + endpoint.getRawAuthority())
                .endpoint(endpoint.getRawPath()).connectTimeout(timeout)
                .clientBuilder(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout))
                .resumableStreams(false).openConnectionOnStartup(false)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    authority.run();
                    if (!uri.equals(endpoint)) throw new SecurityException("CURATED_ENDPOINT_CHANGED");
                    request.timeout(timeout);
                }).build();
        try (var client = McpClient.sync(transport).requestTimeout(timeout).initializationTimeout(timeout)
                .enableCallToolSchemaCaching(false).build()) {
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
