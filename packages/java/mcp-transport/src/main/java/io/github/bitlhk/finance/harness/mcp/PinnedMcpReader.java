package io.github.bitlhk.finance.harness.mcp;

import com.fasterxml.jackson.databind.*;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Official SDK with bounded ingress. A pinned tool is not, by itself, a grant or a trusted result. */
public final class PinnedMcpReader {
    public record Target(URI endpoint, String tool, String inputSchemaSha256, Duration timeout) {
        public Target {
            Objects.requireNonNull(endpoint); Objects.requireNonNull(timeout);
            boolean loopback = "http".equals(endpoint.getScheme())
                    && Set.of("127.0.0.1", "[::1]").contains(endpoint.getHost());
            if ((!"https".equals(endpoint.getScheme()) && !loopback) || endpoint.getHost() == null
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || endpoint.getPath().isEmpty() || !endpoint.normalize().equals(endpoint)
                    || tool == null || !tool.matches("[a-zA-Z][a-zA-Z0-9_-]{0,127}")
                    || inputSchemaSha256 == null || !inputSchemaSha256.matches("[a-f0-9]{64}")
                    || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
                throw new IllegalArgumentException("MCP_TARGET_INVALID");
        }
        @Override public String toString() { return "Target[redacted]"; }
    }

    public static String schemaDigest(Object schema) {
        try {
            var mapper = mapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            byte[] bytes = mapper.writeValueAsBytes(mapper.convertValue(schema, Object.class));
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) { throw new IllegalArgumentException("MCP_SCHEMA_INVALID"); }
    }

    /** Public/isolated source only: no credentials are forwarded. Authenticated sources need a separate reviewed mode. */
    public byte[] read(Target target, Map<String, Object> arguments, Runnable authority,
            BoundedHttpClient.Observation observation) {
        Objects.requireNonNull(authority); authority.run();
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("MCP_INTERRUPTED");
        var endpoint = target.endpoint();
        byte[] output;
        // Only stateless sources are admitted; rejected session IDs never authorize a guessed DELETE.
        try (var http = new BoundedHttpClient.Builder(target.timeout(), true).observations(observation)) {
            var transport = HttpClientStreamableHttpTransport.builder(endpoint.getScheme() + "://" + endpoint.getRawAuthority())
                    .endpoint(endpoint.getRawPath()).connectTimeout(target.timeout()).clientBuilder(http)
                    .jsonMapper(new io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper(mapper()))
                    .resumableStreams(false).openConnectionOnStartup(false)
                    .httpRequestCustomizer((request, method, uri, body, context) -> {
                        if (!endpoint.equals(uri)) throw new SecurityException("MCP_ENDPOINT_CHANGED");
                        authority.run();
                        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("MCP_INTERRUPTED");
                        request.timeout(target.timeout());
                    }).build();
            var client = McpClient.sync(transport).requestTimeout(target.timeout()).initializationTimeout(target.timeout())
                    .enableCallToolSchemaCaching(false).build();
            try (var lifetime = new Lifetime(client, http.client())) {
                client.initialize(); http.client().checkHealthy();
                var listed = client.listTools(); http.client().checkHealthy();
                if (listed.nextCursor() != null) throw new IllegalArgumentException("MCP_TOOLS_PAGINATED");
                var matches = listed.tools().stream().filter(tool -> tool.name().equals(target.tool())).toList();
                if (matches.size() != 1 || !schemaDigest(matches.getFirst().inputSchema()).equals(target.inputSchemaSha256()))
                    throw new IllegalArgumentException("MCP_TOOL_SCHEMA_MISMATCH");
                authority.run();
                var result = client.callTool(new McpSchema.CallToolRequest(target.tool(), Map.copyOf(arguments)));
                http.client().checkHealthy(); authority.run();
                output = payload(result);
            }
        }
        authority.run();
        return output;
    }

    private record Lifetime(McpSyncClient client, BoundedHttpClient http) implements AutoCloseable {
        public void close() {
            boolean interrupted = Thread.interrupted();
            try {
                http.stopReads();
                if (!client.closeGracefully()) { client.close(); throw new IllegalStateException("MCP_CLOSE_INCOMPLETE"); }
                http.checkHealthy();
                if (interrupted) throw new java.util.concurrent.CancellationException("MCP_INTERRUPTED");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private static byte[] payload(McpSchema.CallToolResult result) {
        if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException("MCP_TOOL_FAILED");
        try {
            var mapper = mapper();
            JsonNode value = result.structuredContent() == null ? null : mapper.valueToTree(result.structuredContent());
            var content = result.content() == null ? List.<McpSchema.Content>of() : result.content();
            if (content.size() > 1) throw new IllegalArgumentException("MCP_PAYLOAD_AMBIGUOUS");
            if (!content.isEmpty()) {
                if (!(content.getFirst() instanceof McpSchema.TextContent text))
                    throw new IllegalArgumentException("MCP_PAYLOAD_UNSUPPORTED");
                JsonNode textual = mapper.readTree(text.text());
                if (value != null && !value.equals(textual)) throw new IllegalArgumentException("MCP_PAYLOAD_AMBIGUOUS");
                value = textual;
            }
            if (value == null || !value.isObject()) throw new IllegalArgumentException("MCP_PAYLOAD_INVALID");
            byte[] bytes = mapper.writeValueAsBytes(value);
            if (bytes.length > BoundedHttpClient.MAX_BODY_BYTES) throw new IllegalArgumentException("MCP_PAYLOAD_TOO_LARGE");
            return bytes;
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("MCP_PAYLOAD_INVALID"); }
    }

    private static ObjectMapper mapper() {
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(64).maxStringLength(BoundedHttpClient.MAX_BODY_BYTES).build());
        return mapper;
    }
}
