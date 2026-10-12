package io.github.bitlhk.finance.harness.integrations.postloan;

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
final class OpenJiuwenPostLoanReportGenerator implements PostLoanRiskTask.ExplanationGenerator {
    static final String FACT_REFERENCE_INSTRUCTION = "factRefs 只能逐字选择输入 factIds 中列出的叶字段路径；空容器和父对象路径不得引用。";
    private final ReActAgent agent;
    OpenJiuwenPostLoanReportGenerator(String provider,String key,String base,String model){
        agent=new ReActAgent(AgentCard.builder().id("post-loan-risk-verification-report").name("贷后风险核验辅助报告")
                .description("依据安装方法、风险快照和确定性预警生成待人工复核解释").build());
        String prompt="你只能依据输入中的 installedMethod、purpose、factIds 和 facts 生成只读核验辅助报告。"
                +"只输出一个 JSON 对象，字段严格为 summary、evidenceInterpretation、pendingItems、reviewChecklist。"
                +"summary 与 evidenceInterpretation 是对象数组，每项只有 text 和 factRefs；factRefs 必须来自输入 factIds 且非空。"
                +FACT_REFERENCE_INSTRUCTION
                +"summary 最多2项，evidenceInterpretation 最多2项，pendingItems 最多3项，reviewChecklist 最多4项；"
                +"每项 text 或字符串不超过120个中文字符，避免逐字段复述。pendingItems 与 reviewChecklist 是字符串数组。"
                +"不得补造事实、身份、账户或交易结果。"
                +"预警等级和触发项来自确定性规则，只能解释不能修改。"
                +"担保覆盖率可以按事实展示数值；只有输入同时提供已匹配的适用条件、规则版本、阈值和对应事实时，才能称为不足或充足，否则列为缺少判断依据。"
                +"不得把内部预警称为监管五级分类、授信审批或额度调整结论。"
                +"来源缺失或过期必须列为待核实事项。报告仅供有权人员复核，不得触发写回或自动处置。";
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
            var rendered=new ArrayList<String>();
            while(chunks.hasNext()){
                Object chunk=chunks.next();
                if(!(chunk instanceof com.openjiuwen.core.session.stream.OutputSchema output)
                        ||!"llm_output".equals(output.getType()))continue;
                String content=render(chunk);
                if(content.isBlank())continue;
                rendered.add(content);
            }
            return parseRenderedChunks(rendered);
        }finally{Runner.release(session);}
    }
    static ObjectNode parseRenderedChunks(List<String> chunks)throws Exception{
        if(chunks.isEmpty())throw new IllegalArgumentException("MODEL_REPORT_EMPTY");
        // Some OpenJiuwen providers emit deltas and then a complete final snapshot.
        // Prefer the latest independently valid snapshot so earlier deltas cannot be
        // concatenated in front of it and corrupt an otherwise valid response.
        for(int i=chunks.size()-1;i>=0;i--){
            ObjectNode parsed=tryParse(chunks.get(i));
            if(parsed!=null)return parsed;
        }
        var received=new StringBuilder();
        for(String content:chunks){
            String current=received.toString();
            if(content.startsWith(current)){received.setLength(0);received.append(content);}
            else if(current.startsWith(content))continue;
            else received.append(content);
        }
        ObjectNode parsed=tryParse(received.toString());
        if(parsed==null)throw new IllegalArgumentException("MODEL_REPORT_INVALID");
        return parsed;
    }
    private static ObjectNode tryParse(String value){
        try{
            String text=value.trim();
            if(text.startsWith("```json"))text=text.substring(7);else if(text.startsWith("```"))text=text.substring(3);
            if(text.endsWith("```"))text=text.substring(0,text.length()-3);
            JsonNode parsed=HarnessJson.mapper().readTree(text.trim());
            return parsed instanceof ObjectNode object?object:null;
        }catch(Exception ignored){return null;}
    }
    private static String render(Object value){
        if(value instanceof com.openjiuwen.core.session.stream.OutputSchema output)return render(output.getPayload());
        if(value instanceof com.openjiuwen.core.workflow.WorkflowOutput output)return render(output.getResult());
        if(value instanceof Map<?,?> map)for(String key:List.of("content","output","response","result"))if(map.get(key)!=null)return render(map.get(key));
        return String.valueOf(value);
    }
    private static String required(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("MODEL_CONFIGURATION_REQUIRED");return value;}
}
