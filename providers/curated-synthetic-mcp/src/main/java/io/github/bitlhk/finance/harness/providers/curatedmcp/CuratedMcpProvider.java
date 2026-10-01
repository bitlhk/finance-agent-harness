package io.github.bitlhk.finance.harness.providers.curatedmcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.*;
import java.util.*;

/** Read-only synthetic source. Host authority and MCP execution are separate boundaries. */
public final class CuratedMcpProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String ID = "curated-synthetic-mcp";
    public static final String VERSION = "0.1.0-SNAPSHOT";
    private final ManagedProviderFactoryV01.Connection authority;
    private final CuratedSourceConfig source;
    private final CuratedMcpTransport transport = new CuratedMcpTransport();
    public CuratedMcpProvider(ManagedProviderFactoryV01.Connection authority, CuratedSourceConfig source) {
        if (!"synthetic-bank-http.v1".equals(authority.kind())) throw new IllegalArgumentException("CURATED_AUTHORITY_KIND");
        this.authority = authority;
        this.source = Objects.requireNonNull(source);
    }
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, ID, VERSION,
                CuratedTools.ALL.stream().map(t -> new ProviderDescriptorV1.CapabilitySupport(t.capabilityId(), CapabilityRequestV1.Operation.QUERY)).toList());
    }
    public void checkAccess(CapabilityRequestV1 request) { authorizedSession(request); }
    private ManagedProviderFactoryV01.SourceSession authorizedSession(CapabilityRequestV1 request) {
        var tool = CuratedTools.capability(request.capabilityId());
        var context = request.context();
        var session = authority.currentSession().get();
        if (request.operation() != CapabilityRequestV1.Operation.QUERY || session == null
                || !session.expiresAt().isAfter(Instant.now()) || !session.principalId().equals(context.principalId())
                || !session.hostSessionRef().equals(context.sessionRef()) || !session.subjectRef().equals(context.subjectRef())
                || !context.scopes().contains(tool.scope()) || !source.subjects().containsKey(context.subjectRef()))
            throw new SecurityException("CURATED_AUTHORITY_REJECTED");
        return session;
    }
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        boolean sourcePhase = false;
        try {
            var initial = authorizedSession(request);
            var tool = CuratedTools.capability(request.capabilityId());
            var args = CuratedTools.arguments(tool, request.parameters(), request.context().subjectRef(), source.subjects().get(request.context().subjectRef()));
            Runnable guard = () -> {
                var current = authorizedSession(request);
                if (!current.equals(initial)) throw new SecurityException("CURATED_AUTHORITY_CHANGED");
            };
            sourcePhase = true;
            var result = transport.call(source, authority.timeout(), tool, args, guard);
            guard.run();
            if (Boolean.TRUE.equals(result.isError())) return failure(request, "UPSTREAM_FAILURE", "MCP_IS_ERROR", Map.of("mcpIsError", true));
            var mapper = HarnessJson.mapper();
            JsonNode data;
            if (result.structuredContent() != null) data = mapper.valueToTree(result.structuredContent());
            else {
                if (result.content() == null || result.content().size() != 1 || !(result.content().getFirst() instanceof McpSchema.TextContent text))
                    throw new IllegalArgumentException("CURATED_RESULT_CONTENT_INVALID");
                if (text.text().length() > 262144) throw new IllegalArgumentException("CURATED_RESULT_TOO_LARGE");
                data = mapper.readTree(text.text());
            }
            byte[] bytes = mapper.writeValueAsBytes(data);
            if (bytes.length > 262144) throw new IllegalArgumentException("CURATED_RESULT_TOO_LARGE");
            CuratedTools.validateResult(tool, args, data);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("sourceResult", mapper.convertValue(data, Map.class));
            payload.put("mcpExecution", Map.of("transport", "streamable-http", "tool", tool.name(),
                    "contractVersion", source.contractVersion(), "inputSha256", CuratedTools.schemaPin(args),
                    "outputSha256", CuratedSourceConfig.sha(bytes), "sourceConfigSha256", source.canonicalDigest(),
                    "schemaSha256", source.toolSchemaPins().get(tool.name()), "synthetic", true));
            // COMPLETED describes this read, not a positive business decision or source readiness.
            var states = new LinkedHashMap<String, Object>();
            for (String key : List.of("decision", "status", "readiness")) if (data.has(key)) states.put(key, mapper.convertValue(data.get(key), Object.class));
            payload.put("sourceState", states);
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                    CapabilityResultV1.Status.COMPLETED, "Synthetic MCP read completed; preserve sourceState, which is not a success verdict. Not live financial data.", payload,
                    List.of(), List.of(), "curated-synthetic-mcp", Instant.now(), null, null);
        } catch (SecurityException denied) {
            return failure(request, "NOT_AUTHORIZED", "CURATED_ACCESS_DENIED", Map.of());
        } catch (IllegalArgumentException invalid) {
            return failure(request, sourcePhase ? "UPSTREAM_FAILURE" : "INVALID_INPUT",
                    sourcePhase ? "MCP_CONTRACT_REJECTED" : "CURATED_CONTRACT_REJECTED", Map.of());
        } catch (Exception failed) {
            return failure(request, "UPSTREAM_FAILURE", "CURATED_MCP_UNAVAILABLE", Map.of());
        }
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        return failure(request, "PERMANENT_FAILURE", "TASK_NOT_SUPPORTED", Map.of());
    }
    private static CapabilityResultV1 failure(CapabilityRequestV1 request, String type, String code, Map<String, Object> data) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, request.context().requestId(), request.context().traceId(),
                CapabilityResultV1.Status.FAILED, code, data, List.of(), List.of(), "curated-synthetic-mcp", Instant.now(), null,
                new CapabilityResultV1.Failure(type, code, false, code, List.of()));
    }
}
