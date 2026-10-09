package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.examples.wealth.ServicePreparation;
import java.util.*;

/** Model-backed wording over Host-authorized facts and the installed method. */
final class OpenJiuwenReportGenerator implements ServicePreparation.ReportGenerator {
    static final int REPORT_MAX_TOKENS = 900;
    private final ReActAgent agent;

    OpenJiuwenReportGenerator(String provider, String key, String base, String model) {
        agent = new ReActAgent(AgentCard.builder().id("service-preparation-report")
                .name("客户经理服务准备报告").description("依据已安装方法和已核实事实生成待复核建议").build());
        var config = ReActAgentConfig.builder().promptTemplate(List.of(Map.of("role", "system", "content",
                "你只能依据输入中的installedMethod、goal、factIds和facts生成待经理复核的服务准备建议。"
                + "每行只输出一个JSON对象，不要代码围栏或其他文字。字段严格为section、text、factRefs。"
                + "section依次使用communicationFocus、preparationAdvice、pendingItems、reviewChecklist；"
                + "前两类factRefs必须为输入中的非空引用数组，后两类factRefs必须为空数组。"
                + "涉及产品时，只有productReview.eligibleProducts中的产品可以作为候选；excludedProducts只能说明排除原因，不得推荐。"
                + "不得新增金额、产品、业务完成状态，不得声称写入CRM或已获经理复核。")))
                .maxIterations(1).build().configureModelClient(required(provider), required(key), required(base), required(model), true);
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(REPORT_MAX_TOKENS);
        agent.configure(config);
        if (!Runner.start()) throw new IllegalStateException("RUNTIME_START_FAILED");
    }

    @Override public ObjectNode generate(io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod method,
            String goal, List<String> ids, JsonNode context) throws Exception {
        return generateStreaming(method, goal, ids, context, ignored -> {});
    }

    @Override public ObjectNode generateStreaming(io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod method,
            String goal, List<String> ids, JsonNode context,
            java.util.function.Consumer<ServicePreparation.ReportDelta> sink) throws Exception {
        String sid = UUID.randomUUID().toString();
        try {
            String input=HarnessJson.mapper().writeValueAsString(Map.of(
                    "installedMethod", Map.of("id", method.id(), "version", method.version(), "digest", method.digest(),
                            "lockDigest", method.lockDigest(), "content", method.content()),
                    "goal", goal, "factIds", ids, "facts", context));
            var parser=new ReportLines(sink);
            var chunks=Runner.runAgentStreaming(agent,Map.of("query",input),sid,null,List.of(StreamMode.OUTPUT));
            while(chunks.hasNext()) {
                Object chunk=chunks.next();
                // OpenJiuwen providers do not use one stable OutputSchema type for model
                // deltas. This agent has no tools, so accept textual content from any
                // output envelope and keep the report parser/validator as the trust boundary.
                if (chunk instanceof OutputSchema || chunk instanceof WorkflowOutput) {
                    String content = render(chunk);
                    if (!content.isBlank()) parser.accept(content);
                }
            }
            return parser.finish();
        } finally { Runner.release(sid); }
    }

    private static final class ReportLines {
        private final java.util.function.Consumer<ServicePreparation.ReportDelta> sink;
        private final StringBuilder received=new StringBuilder(), pending=new StringBuilder();
        private final ObjectNode result=HarnessJson.mapper().createObjectNode();
        ReportLines(java.util.function.Consumer<ServicePreparation.ReportDelta> sink) {
            this.sink=sink;
            result.putArray("communicationFocus"); result.putArray("preparationAdvice");
            result.putArray("pendingItems"); result.putArray("reviewChecklist");
        }
        void accept(String content) throws Exception {
            String delta=content.startsWith(received.toString())?content.substring(received.length()):content;
            if(content.startsWith(received.toString())){received.setLength(0);received.append(content);}else received.append(content);
            pending.append(delta); int newline;
            while((newline=pending.indexOf("\n"))>=0){String line=pending.substring(0,newline);pending.delete(0,newline+1);line(line);}
        }
        ObjectNode finish() throws Exception { if(!pending.toString().isBlank())line(pending.toString());return result; }
        private void line(String raw) throws Exception {
            String line=raw.trim(); if(line.isEmpty()||line.startsWith("```"))return;
            JsonNode item=HarnessJson.mapper().readTree(line);
            if(!item.isObject()||!item.path("section").isTextual()||!item.path("text").isTextual()
                    ||!item.path("factRefs").isArray())throw new IllegalArgumentException("INVALID_REPORT_STREAM_ITEM");
            String section=item.path("section").asText();
            var refs=new ArrayList<String>();item.path("factRefs").forEach(ref->refs.add(ref.asText()));
            var delta=new ServicePreparation.ReportDelta(section,item.path("text").asText(),refs);sink.accept(delta);
            if(Set.of("communicationFocus","preparationAdvice").contains(section)) {
                var target=result.withArray(section).addObject();target.put("text",delta.text());
                target.set("factRefs",HarnessJson.mapper().valueToTree(refs));
            } else if(Set.of("pendingItems","reviewChecklist").contains(section)) result.withArray(section).add(delta.text());
            else throw new IllegalArgumentException("INVALID_REPORT_STREAM_SECTION");
        }
    }

    private static String render(Object value) {
        if (value instanceof OutputSchema output) return render(output.getPayload());
        if (value instanceof WorkflowOutput output) return render(output.getResult());
        if (value instanceof Map<?,?> map) for (String key : List.of("content", "output", "response", "result"))
            if (map.get(key) != null) return render(map.get(key));
        return String.valueOf(value);
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MODEL_CONFIGURATION_REQUIRED");
        return value;
    }
}
