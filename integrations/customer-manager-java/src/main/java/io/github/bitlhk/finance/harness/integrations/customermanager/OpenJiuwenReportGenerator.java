package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.examples.wealth.ServicePreparation;
import java.util.*;

/** Model-backed wording over Host-authorized facts and the installed method. */
final class OpenJiuwenReportGenerator implements ServicePreparation.ReportGenerator {
    private final ReActAgent agent;

    OpenJiuwenReportGenerator(String provider, String key, String base, String model) {
        agent = new ReActAgent(AgentCard.builder().id("service-preparation-report")
                .name("客户经理服务准备报告").description("依据已安装方法和已核实事实生成待复核建议").build());
        var config = ReActAgentConfig.builder().promptTemplate(List.of(Map.of("role", "system", "content",
                "你只能依据输入中的installedMethod、goal、factIds和facts生成待经理复核的服务准备建议。"
                + "只输出JSON对象，字段严格为communicationFocus、preparationAdvice、pendingItems、reviewChecklist。"
                + "前两项是对象数组，每项只能含text和factRefs，factRefs必须来自输入；后两项是字符串数组。"
                + "不得新增金额、产品、业务完成状态，不得声称写入CRM或已获经理复核。")))
                .maxIterations(1).build().configureModelClient(required(provider), required(key), required(base), required(model), true);
        config.getModelConfigObj().setTemperature(0.1); agent.configure(config);
        if (!Runner.start()) throw new IllegalStateException("RUNTIME_START_FAILED");
    }

    @Override public ObjectNode generate(io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod method,
            String goal, List<String> ids, JsonNode context) throws Exception {
        String sid = UUID.randomUUID().toString();
        try {
            Object value = Runner.runAgent(agent, Map.of("query", HarnessJson.mapper().writeValueAsString(Map.of(
                    "installedMethod", Map.of("id", method.id(), "version", method.version(), "digest", method.digest(),
                            "lockDigest", method.lockDigest(), "content", method.content()),
                    "goal", goal, "factIds", ids, "facts", context))), sid, null);
            JsonNode generated = HarnessJson.mapper().readTree(render(value));
            if (!generated.isObject()) throw new IllegalArgumentException("INVALID_REPORT_OUTPUT");
            return ((ObjectNode) generated).deepCopy();
        } finally { Runner.release(sid); }
    }

    private static String render(Object value) {
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
