package io.github.bitlhk.finance.harness.integrations.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.session.stream.StreamMode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import java.util.*;

/** Model wording over immutable evidence; it has no tools and cannot query or decide transactions. */
final class OpenJiuwenFraudReportGenerator implements FraudVerificationTask.ExplanationGenerator {
    private final ReActAgent agent;
    OpenJiuwenFraudReportGenerator(String provider,String key,String base,String model){
        agent=new ReActAgent(AgentCard.builder().id("fraud-risk-verification-report").name("电诈风险核验辅助报告")
                .description("依据安装方法和只读风险证据生成待人工复核解释").build());
        String prompt="你只能依据输入中的 installedMethod、purpose、factIds 和 facts 生成只读核验辅助报告。"
                +"只输出一个 JSON 对象，字段严格为 summary、evidenceInterpretation、pendingItems、reviewChecklist。"
                +"summary 与 evidenceInterpretation 是对象数组，每项只有 text 和 factRefs；factRefs 必须来自输入 factIds 且非空。"
                +"summary 最多2项，evidenceInterpretation 最多2项，pendingItems 最多3项，reviewChecklist 最多4项；"
                +"每项 text 或字符串不超过120个中文字符，避免逐字段复述。pendingItems 与 reviewChecklist 是字符串数组。"
                +"不得补造事实、身份、账户或交易结果。"
                +"核验对象是获得授权的合成对象，sourceSystemMeaning描述的是被查询的已知涉诈记录源；"
                +"不得把核验对象称为已知涉诈记录、涉诈来源记录或已知风险对象。摘要应表述为对授权对象在已知涉诈记录源中查询。"
                +"未命中只表示在记录的完整查询范围内未命中已知记录，不得描述为安全、低风险或允许转账。"
                +"命中不等于认定对象实施诈骗；查询不完整时必须明确不能形成完整未命中结论。"
                +"分数只能称为来源信号，不得称为诈骗概率。报告仅供有权人员复核，不得建议自动放行或阻断。";
        var config=ReActAgentConfig.builder().promptTemplate(List.of(Map.of("role","system","content",prompt)))
                .maxIterations(1).build().configureModelClient(required(provider),required(key),required(base),required(model),true);
        config.getModelConfigObj().setTemperature(0.1);config.getModelConfigObj().setMaxTokens(1200);
        if(model.toLowerCase(Locale.ROOT).contains("deepseek"))config.getModelConfigObj().setExtraField("thinking",Map.of("type","disabled"));
        agent.configure(config);if(!Runner.start())throw new IllegalStateException("RUNTIME_START_FAILED");
    }
    @Override public ObjectNode generate(InstalledPreparationMethod method,String purpose,List<String> refs,JsonNode facts)throws Exception{
        String session=UUID.randomUUID().toString();
        try{
            String input=HarnessJson.mapper().writeValueAsString(Map.of("installedMethod",Map.of("id",method.id(),"version",method.version(),"digest",method.digest(),"lockDigest",method.lockDigest(),"content",method.content()),"purpose",purpose,"factIds",refs,"facts",facts));
            var chunks=Runner.runAgentStreaming(agent,Map.of("query",input),session,null,List.of(StreamMode.OUTPUT));
            var received=new StringBuilder();
            while(chunks.hasNext()){
                Object chunk=chunks.next();
                if(!(chunk instanceof com.openjiuwen.core.session.stream.OutputSchema output)
                        ||!"llm_output".equals(output.getType()))continue;
                String content=render(chunk);
                if(content.isBlank())continue;
                if(content.startsWith(received.toString())){received.setLength(0);received.append(content);}
                else received.append(content);
            }
            String text=received.toString().trim();
            if(text.startsWith("```json"))text=text.substring(7);else if(text.startsWith("```"))text=text.substring(3);
            if(text.endsWith("```"))text=text.substring(0,text.length()-3);
            JsonNode value=HarnessJson.mapper().readTree(text.trim());if(!value.isObject())throw new IllegalArgumentException("MODEL_REPORT_INVALID");
            return (ObjectNode)value;
        }finally{Runner.release(session);}
    }
    private static String render(Object value){
        if(value instanceof com.openjiuwen.core.session.stream.OutputSchema output)return render(output.getPayload());
        if(value instanceof com.openjiuwen.core.workflow.WorkflowOutput output)return render(output.getResult());
        if(value instanceof Map<?,?> map)for(String key:List.of("content","output","response","result"))if(map.get(key)!=null)return render(map.get(key));
        return String.valueOf(value);
    }
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("MODEL_CONFIGURATION_REQUIRED");return value;}
}
