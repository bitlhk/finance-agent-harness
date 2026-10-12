package io.github.bitlhk.finance.builder.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** AG-UI SSE boundary. No browser tools, state, history or identity can override the Host. */
final class AgUiTransport {
    static void validate(JsonNode body, String sessionId, String instanceId) {
        var keys = new HashSet<String>(); body.fieldNames().forEachRemaining(keys::add);
        if (!keys.equals(Set.of("threadId", "runId", "messages", "tools", "context", "state", "forwardedProps"))
                || !body.path("threadId").asText().equals(sessionId)
                || !body.path("runId").isTextual() || !body.path("runId").asText().matches("[A-Za-z0-9_-]{1,128}")
                || !body.path("tools").isArray() || !body.path("tools").isEmpty()
                || !body.path("context").isArray() || !body.path("context").isEmpty()
                || !(body.path("state").isNull() || body.path("state").isObject() && body.path("state").isEmpty())
                || !body.path("forwardedProps").isObject() || body.path("forwardedProps").size() != 1
                || !body.path("forwardedProps").path("instanceId").asText().equals(instanceId)
                || !body.path("messages").isArray() || body.path("messages").size() != 1)
            throw new IllegalArgumentException("AG_UI_INPUT_REJECTED");
        JsonNode message = body.path("messages").get(0);
        if (!message.isObject() || message.size() != 3 || !"user".equals(message.path("role").asText())
                || !message.path("id").isTextual() || !message.path("id").asText().matches("[A-Za-z0-9_-]{1,128}")
                || !message.path("content").isTextual() || message.path("content").asText().isBlank()
                || message.path("content").asText().length() > 4000)
            throw new IllegalArgumentException("AG_UI_INPUT_REJECTED");
    }

    static void run(HttpExchange exchange, JsonNode body, NativeConversation conversation,
                    String buildId, String instanceId, Runnable clear) throws IOException {
        conversation.authorize();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        String thread = body.path("threadId").asText(), run = body.path("runId").asText();
        try {
            emit(exchange, Map.of("type", "RUN_STARTED", "threadId", thread, "runId", run));
            emit(exchange, Map.of("type", "STEP_STARTED", "stepName", "governed_execution"));
            var progress = new AuditProgressTracker(conversation.capabilities());
            var result = new LinkedHashMap<String, Object>(conversation.chat(body.path("messages").get(0).path("content").asText(), run,
                    (phase, capabilityId, elapsedMillis) -> {
                        progress.update(phase, capabilityId, elapsedMillis);
                        if (!progress.members.isEmpty()) {
                            try {
                                emit(exchange, Map.of("type", "CUSTOM", "name", "finance.subagents", "value", progress.payload()));
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        }
                    }));
            result.put("buildId", buildId); result.put("instanceId", instanceId);
            String answer = text(HarnessJson.mapper().valueToTree(result.get("response")));
            if (answer.isBlank()) throw new IllegalStateException("EMPTY_MODEL_RESPONSE");
            conversation.authorize();
            String id = "message-" + UUID.randomUUID();
            // Financial output is buffered until the native run and authorization checks finish.
            emit(exchange, Map.of("type", "STEP_FINISHED", "stepName", "governed_execution"));
            emit(exchange, Map.of("type", "CUSTOM", "name", "finance.reviewTeam",
                    "value", Map.of("members", reviewTeam(conversation.capabilities(), conversation.tools))));
            if (!progress.members.isEmpty())
                emit(exchange, Map.of("type", "CUSTOM", "name", "finance.subagents", "value", progress.payload()));
            emit(exchange, Map.of("type", "TEXT_MESSAGE_START", "messageId", id, "role", "assistant"));
            for (String chunk : textChunks(answer, 120))
                emit(exchange, Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", id, "delta", chunk));
            emit(exchange, Map.of("type", "TEXT_MESSAGE_END", "messageId", id));
            emit(exchange, Map.of("type", "CUSTOM", "name", "finance.evidence", "value", Map.of(
                    "buildId", buildId, "instanceId", instanceId, "sessionId", thread, "runId", run,
                    "clearConversation", false, "result", result)));
            emit(exchange, Map.of("type", "RUN_FINISHED", "threadId", thread, "runId", run));
            if (conversation.observation != null) conversation.observation.finish(
                    io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.COMPLETED);
        } catch (Exception error) {
            if (conversation.observation != null) conversation.observation.finish(error instanceof SecurityException
                    ? io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.DENIED
                    : io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome.ERROR);
            clear.run();
            if (error instanceof IOException) return;
            String code = error instanceof SecurityException ? "AUTHORIZATION_REJECTED" : "MODEL_OR_RUNTIME_FAILED";
            emit(exchange, Map.of("type", "CUSTOM", "name", "finance.clearConversation", "value", Map.of("clearConversation", true)));
            emit(exchange, Map.of("type", "RUN_ERROR", "code", code, "message", code));
        }
    }

    static String text(JsonNode value) {
        if (value == null) return "";
        if (value.isTextual()) return value.asText();
        for (String key : List.of("output", "answer", "content", "response", "result")) {
            String found = text(value.get(key)); if (!found.isEmpty()) return found;
        }
        return "";
    }
    static List<String> textChunks(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars < 1) return List.of();
        var chunks = new ArrayList<String>();
        for (int offset = 0; offset < value.length(); offset += maxChars) {
            String chunk = value.substring(offset, Math.min(value.length(), offset + maxChars));
            if (!chunk.isBlank()) chunks.add(chunk);
        }
        return List.copyOf(chunks);
    }
    static List<Map<String, Object>> reviewTeam(Set<String> capabilities, List<Object> toolResults) {
        var members = new ArrayList<Map<String, Object>>();
        members.add(Map.of("id", "case-orchestrator", "name", "审核案件编排器", "status", "COMPLETED"));
        var known = Map.ofEntries(
                Map.entry("smartaudit.personal.borrower-qualification.query", "personal-borrower-qualification"),
                Map.entry("smartaudit.personal.income-verification.query", "personal-income-verification"),
                Map.entry("smartaudit.personal.asset-liability.query", "personal-asset-liability"),
                Map.entry("smartaudit.personal.repayment-capacity.query", "personal-repayment-capacity"),
                Map.entry("smartaudit.enterprise.financial-report.query", "enterprise-financial-report"),
                Map.entry("smartaudit.enterprise.financial-summary.query", "enterprise-financial-summary"),
                Map.entry("smartaudit.enterprise.transaction-analysis.query", "enterprise-transaction-analysis"),
                Map.entry("smartaudit.enterprise.transaction-report.query", "enterprise-transaction-report"),
                Map.entry("smartaudit.enterprise.due-diligence.query", "enterprise-due-diligence"));
        for (String capability : capabilities) {
            String id = known.get(capability);
            if (id != null) {
                String status = capabilityStatus(capability, toolResults);
                members.add(Map.of("id", id, "capabilityId", capability, "status", status));
            }
        }
        return List.copyOf(members);
    }
    private static String capabilityStatus(String capability, List<Object> toolResults) {
        for (int index = toolResults.size() - 1; index >= 0; index--) {
            JsonNode receipt = HarnessJson.mapper().valueToTree(toolResults.get(index));
            if (!capability.equals(receipt.path("data").path("capabilityId").asText())) continue;
            String status = receipt.path("data").path("resultStatus").asText();
            if (!status.isBlank()) return status;
            status = receipt.path("status").asText();
            return status.isBlank() ? "NOT_OBSERVED" : status;
        }
        return "READY";
    }
    private static final class AuditProgressTracker {
        private static final Map<String, String> IDS = Map.of(
                "smartaudit.personal.borrower-qualification.query", "personal-borrower-qualification",
                "smartaudit.personal.income-verification.query", "personal-income-verification",
                "smartaudit.personal.asset-liability.query", "personal-asset-liability",
                "smartaudit.personal.repayment-capacity.query", "personal-repayment-capacity");
        private static final Map<String, String> NAMES = Map.of(
                "personal-borrower-qualification", "借款人主体核验专员",
                "personal-income-verification", "收入交叉验证专员",
                "personal-asset-liability", "资产负债核验专员",
                "personal-repayment-capacity", "偿债能力测算专员");
        final LinkedHashMap<String, LinkedHashMap<String, Object>> members = new LinkedHashMap<>();

        AuditProgressTracker(Set<String> capabilities) {
            if (!capabilities.containsAll(IDS.keySet())) return;
            members.put("case-orchestrator", member("case-orchestrator", "审核案件编排器", "READY", "等待材料包校验"));
            for (String capability : List.of("smartaudit.personal.borrower-qualification.query", "smartaudit.personal.income-verification.query",
                    "smartaudit.personal.asset-liability.query", "smartaudit.personal.repayment-capacity.query")) {
                String id = IDS.get(capability);
                members.put(id, member(id, NAMES.get(id), "READY", "等待编排器调度"));
            }
        }
        void update(String phase, String capabilityId, long elapsedMillis) {
            if (members.isEmpty()) return;
            var orchestrator = members.get("case-orchestrator");
            String id = IDS.get(capabilityId);
            if ("AUDIT_STARTED".equals(phase)) {
                orchestrator.put("status", "RUNNING"); orchestrator.put("summary", "正在校验材料并分派审核任务");
            } else if ("CAPABILITY_STARTED".equals(phase) && id != null) {
                members.get(id).put("status", "RUNNING"); members.get(id).put("summary", "正在执行受控核验");
            } else if ("CAPABILITY_COMPLETED".equals(phase) && id != null) {
                members.get(id).put("status", "COMPLETED"); members.get(id).put("summary", "受控核验回执已返回"); members.get(id).put("elapsedMs", elapsedMillis);
            } else if ("NARRATIVE_STARTED".equals(phase)) {
                orchestrator.put("status", "RUNNING"); orchestrator.put("summary", "正在汇总审核发现");
            } else if ("AUDIT_COMPLETED".equals(phase)) {
                orchestrator.put("status", "COMPLETED"); orchestrator.put("summary", "已汇总审核发现，待人工复核交接");
            }
        }
        Map<String, Object> payload() {
            return Map.of("members", List.copyOf(members.values()), "policy", Map.of(
                    "policyId", "PPL-DEMO-2026-01", "version", "1.0.0", "label", "个人贷款受控演示政策（仅测试）"));
        }
        private static LinkedHashMap<String, Object> member(String id, String name, String status, String summary) {
            var value = new LinkedHashMap<String, Object>();
            value.put("id", id); value.put("name", name); value.put("status", status); value.put("summary", summary);
            return value;
        }
    }
    private static String encode(Object event) throws IOException {
        return "data: " + HarnessJson.mapper().writeValueAsString(event) + "\n\n";
    }
    private static void emit(HttpExchange exchange, Object event) throws IOException {
        exchange.getResponseBody().write(encode(event).getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
    }
}
