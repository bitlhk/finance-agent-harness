package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.execution.CaseFileWorkspaceV1;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OpenJiuwenCaseFileToolTest {
    @ParameterizedTest @ValueSource(booleans = {false, true}) @Timeout(30)
    void nativeLoopReadsFileBeforeItsContentReachesModel(boolean jsonMode) throws Exception {
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var mapper = io.github.bitlhk.finance.harness.core.HarnessJson.mapper();
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            boolean first = requests.size() == 1;
            Map<String, Object> message = first
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "file-call", "type", "function",
                        "function", Map.of("name", "read_case_file", "arguments", "{\"path\":\"case.txt\"}"))))
                    : Map.of("role", "assistant", "content", "{\"fileMarker\":\"FILE_ONLY_93201\"}");
            byte[] response = mapper.writeValueAsBytes(Map.of("id", "case-model", "model", "probe",
                    "choices", List.of(Map.of("index", 0, "message", message, "finish_reason", first ? "tool_calls" : "stop"))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        String session = "case-loop-" + UUID.randomUUID();
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("case.txt\0FILE_ONLY_93201\0".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var agent = new ReActAgent(AgentCard.builder().id(session).name("Case reader").build());
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        var config = com.openjiuwen.core.singleagent.agents.ReActAgentConfig.builder().maxIterations(3)
                .promptTemplate(List.of(Map.of("role", "system", "content", "Synthetic file test")))
                .build().configureModelClient("OpenAI", "synthetic", base, "probe", true);
        config.setModelClientConfig(com.openjiuwen.core.foundation.llm.schema.ModelClientConfig.builder()
                .clientProvider("OpenAI").apiKey("synthetic").apiBase(base).timeout(5).maxRetries(0).verifySsl(true).build());
        if (jsonMode) config.getModelConfigObj().setExtraField("response_format", Map.of("type", "json_object"));
        agent.configure(config);
        try (var files = new CaseFileWorkspaceV1(List.of(Map.of("path", "case.txt", "content", "FILE_ONLY_93201")), digest);
             var binding = new OpenJiuwenCaseFileTool(agent, session, files, () -> {})) {
            Runner.runAgent(agent, Map.of("query", "Read case.txt"), session, null);
            assertEquals(2, requests.size());
            assertFalse(requests.get(0).contains("FILE_ONLY_93201"));
            assertTrue(requests.get(1).contains("FILE_ONLY_93201"));
            assertEquals(1, ((List<?>) files.evidence().get("reads")).size());
            for (String body : requests) {
                var request = mapper.readTree(body);
                assertEquals(jsonMode, request.has("response_format"));
                if (jsonMode) assertEquals("json_object", request.path("response_format").path("type").asText());
                assertTrue(request.path("tools").size() > 0);
            }
        } finally {
            Runner.release(session); server.stop(0);
        }
    }

    @Test void nativeToolReadsDeclaredDataAndPreservesAuthorizationAndCleanup() throws Exception {
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("case.txt\0CASE_ONLY_9182\0".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var allowed = new AtomicBoolean(true);
        var agent = new ReActAgent(AgentCard.builder().id("case-reader").name("Case reader").build());
        try (var files = new CaseFileWorkspaceV1(List.of(Map.of("path", "case.txt", "content", "CASE_ONLY_9182")), digest);
             var binding = new OpenJiuwenCaseFileTool(agent, "case-reader", files, () -> {
                 if (!allowed.get()) throw new SecurityException("AUTHORIZATION_REJECTED");
             })) {
            var tool = (com.openjiuwen.core.foundation.tool.Tool) Runner.resourceMgr().getTool(CaseFileWorkspaceV1.TOOL);
            assertNotNull(agent.getAbilityManager().get(CaseFileWorkspaceV1.TOOL));
            assertEquals("CASE_ONLY_9182", ((Map<?, ?>) tool.invoke(Map.of("path", "case.txt"))).get("content"));
            assertThrows(Exception.class, () -> tool.invoke(Map.of("path", "../secret")));
            allowed.set(false);
            assertThrows(Exception.class, () -> tool.invoke(Map.of("path", "case.txt")));
            assertEquals(1, ((List<?>) files.evidence().get("reads")).size());
        }
        assertNull(agent.getAbilityManager().get(CaseFileWorkspaceV1.TOOL));
        assertNull(Runner.resourceMgr().getTool(CaseFileWorkspaceV1.TOOL));
    }
}
