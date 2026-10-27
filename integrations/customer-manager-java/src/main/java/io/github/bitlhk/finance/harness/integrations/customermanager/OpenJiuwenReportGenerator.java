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
    static final int REPORT_MAX_TOKENS = 3200;
    static final String DELIVERY_INSTRUCTION =
            "四个section只是证据分类，不是限制业务内容的四句模板。按installedMethod中的交付要求组织正文，"
            + "可在text内使用Markdown小标题和列表，保留每项事实的factRefs；每项text最多500字。"
            + "分段输出：每个观察、每个问题、每个条件式下一步分别占一个JSON行；每段以100至220字为宜。"
            + "同一section可以连续输出多行，前两类各最多8行，后两类各最多12行；不要把整篇建议或多个问题塞入一个text。"
            + "communicationFocus说明本次拜访目标、触发事件与事实支持的主要观察；"
            + "preparationAdvice展开SOP要求的业务分析、具体追问和有条件的下一步，不要重复概括空泛建议。"
            + "有资产、持仓、风险测评、到期信息时使用与本次目标有关的具体事实；缺失时明确缺失，不能用泛泛复核清单代替。"
            + "derivedObservations仅为宿主从同币种同日期快照计算的比例，引用其percent和operandRefs，"
            + "不得把快照占比当成政策底线或当前资产证明；没有该计算结果时不得自行计算比例。"
            + "输入中同时存在分子和分母也不等于已提供计算结果，不要自行相除，不要写算式。"
            + "未提供derivedObservations或其中没有对应计算项时，用原始金额说明观察并提问，不写新占比。"
            + "来源中的流动性偏好比例只描述偏好，不能改写成当前现金占比。";
    static final String BUSINESS_LANGUAGE_INSTRUCTION =
            "text是直接交给客户经理阅读的正文，不是校验过程说明。只写业务结论、具体问题与必要边界。"
            + "所有英文状态码、输入字段名、引用路径、校验术语只留在factRefs和输入中，绝不复述到text。"
            + "例如直接写‘只有历史快照，当前资金去向待核实’，不要解释某字段为某状态；"
            + "没有比例就只写已取得的金额，不解释缺少哪个计算字段。不要复述这些写作指令。";
    static final String FACT_REFERENCE_INSTRUCTION =
            "factRefs只能逐字复制factReferenceCatalog中列出的叶字段路径；"
            + "不得引用父对象、容器路径，不得缩短、改写或自行构造路径。";
    private final ReActAgent agent;

    OpenJiuwenReportGenerator(String provider, String key, String base, String model) {
        agent = new ReActAgent(AgentCard.builder().id("service-preparation-report")
                .name("客户经理服务准备报告").description("依据已安装方法和已授权取得的事实生成待复核建议").build());
        var config = ReActAgentConfig.builder().promptTemplate(List.of(Map.of("role", "system", "content",
                "你只能依据输入中的installedMethod、goal、factReferenceCatalog和facts生成待经理复核的服务准备建议。"
                + "每行只输出一个JSON对象，不要代码围栏或其他文字。字段严格为section、text、factRefs。"
                + "section依次使用communicationFocus、preparationAdvice、pendingItems、reviewChecklist；"
                + "前两类factRefs必须为输入中的非空引用数组，后两类factRefs必须为空数组。"
                + FACT_REFERENCE_INSTRUCTION
                + DELIVERY_INSTRUCTION
                + BUSINESS_LANGUAGE_INSTRUCTION
                + "涉及产品时，只有productReview.eligibleProducts中的产品可以作为候选；excludedProducts只能说明排除原因，不得推荐。"
                + "productReview.request.horizonMonths表示客户可接受期限，产品termMonths小于或等于该值即满足期限条件；"
                + "不得把eligibleProducts描述为不满足请求，也不得声称没有合格候选。"
                + "temporalAssessment是宿主按当前业务时间计算的时态结论；当effectiveStatus为PAST_DUE_UNVERIFIED时，"
                + "只能说明到期日已过且状态待核实，不得写成即将到期、临近到期或快到期。"
                + "taskContext.facts中source.kind=USER_STATEMENT的值才可称为客户口述；"
                + "source.kind=DETERMINISTIC_DERIVATION的值必须称为依据客户口述计算或推导所得，"
                + "并保留verification.status=DERIVED_FROM_UNVERIFIED_INPUT所表达的尚未独立核验边界；不得把两类来源合并称为均由客户口述。"
                + "可以按installedMethod提出有事实依据的问题、待确认假设和条件式建议；必须明确这些是建议，不是已发生事实。"
                + "不得新增输入和installedMethod都未支持的操作策略、配置方式、材料名称或流程步骤，不得编造合规结论；缺少依据时写入pendingItems。"
                + "text直接写自然、简洁、便于客户经理使用的沟通话术或准备建议，不要添加“沟通焦点：”“服务准备建议：”等栏目名前缀，"
                + "不要向用户展示JSON字段名、内部路径或英文集合名。"
                + "金额可作等值单位换算，不得新增金额、产品、业务完成状态，不得声称写入CRM或已获经理复核。")))
                .maxIterations(1).build().configureModelClient(required(provider), required(key), required(base), required(model), true);
        config.getModelConfigObj().setTemperature(0.1);
        config.getModelConfigObj().setMaxTokens(REPORT_MAX_TOKENS);
        configureModelRequest(config.getModelConfigObj(), model);
        agent.configure(config);
        if (!Runner.start()) throw new IllegalStateException("RUNTIME_START_FAILED");
    }

    static void configureModelRequest(com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig config,
            String model) {
        if (model != null && model.toLowerCase(Locale.ROOT).contains("deepseek")) {
            config.setExtraField("thinking", Map.of("type", "disabled"));
        }
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
                    "goal", goal, "factReferenceCatalog", referenceCatalog(ids, context),
                    "facts", context));
            var parser=new ReportLines(sink);
            var chunks=Runner.runAgentStreaming(agent,Map.of("query",input),sid,null,List.of(StreamMode.OUTPUT));
            while(chunks.hasNext()) {
                Object chunk=chunks.next();
                // OpenJiuwen providers do not use one stable OutputSchema type for model
                // deltas. This agent has no tools, so accept textual content from any
                // output envelope and keep the report parser/validator as the trust boundary.
                if (chunk instanceof OutputSchema output && "llm_output".equals(output.getType())) {
                    String content = render(chunk);
                    if (!content.isBlank()) parser.accept(content);
                }
            }
            return parser.finish();
        } finally { Runner.release(sid); }
    }

    static JsonNode referenceCatalog(List<String> ids, JsonNode context) {
        var catalog=HarnessJson.mapper().createArrayNode();
        for(String id:ids){
            if(id==null||id.isBlank())continue;
            JsonNode value=context.at(id);
            if(value.isMissingNode()||value.isContainerNode())continue;
            catalog.add(id);
        }
        return catalog;
    }

    static final class ReportLines {
        private enum Shape { UNKNOWN, LINES, CANONICAL }
        private static final List<String> SECTIONS=List.of(
                "communicationFocus","preparationAdvice","pendingItems","reviewChecklist");
        private final java.util.function.Consumer<ServicePreparation.ReportDelta> sink;
        private final StringBuilder received=new StringBuilder(), pending=new StringBuilder();
        private final ObjectNode result=HarnessJson.mapper().createObjectNode();
        private final Map<String,Integer> emitted=new LinkedHashMap<>();
        private Shape shape=Shape.UNKNOWN;
        ReportLines(java.util.function.Consumer<ServicePreparation.ReportDelta> sink) {
            this.sink=sink;
            SECTIONS.forEach(section->{result.putArray(section);emitted.put(section,0);});
        }
        void accept(String content) throws Exception {
            String delta=content.startsWith(received.toString())?content.substring(received.length()):content;
            if(content.startsWith(received.toString())){received.setLength(0);received.append(content);}else received.append(content);
            if(shape==Shape.UNKNOWN)shape=detect(received.toString());
            if(shape==Shape.CANONICAL){canonicalDeltas();return;}
            pending.append(delta); int newline;
            while(shape==Shape.LINES&&(newline=pending.indexOf("\n"))>=0){
                String line=pending.substring(0,newline);pending.delete(0,newline+1);line(line);
            }
        }
        ObjectNode finish() throws Exception {
            if(shape==Shape.UNKNOWN)shape=detect(received.toString());
            if(shape==Shape.CANONICAL){
                canonicalDeltas();
                JsonNode root=HarnessJson.mapper().readTree(stripFence(received.toString()));
                if(!root.isObject()||root.size()!=SECTIONS.size())throw new IllegalArgumentException("INVALID_REPORT_OBJECT");
                for(String section:SECTIONS)if(!root.path(section).isArray())throw new IllegalArgumentException("INVALID_REPORT_SECTION");
                canonicalDeltas(root);
                for(String section:SECTIONS)if(emitted.get(section)!=root.path(section).size())
                    throw new IllegalArgumentException("INVALID_REPORT_ITEM");
            } else if(!pending.toString().isBlank()) line(pending.toString());
            return result;
        }
        private static Shape detect(String value) {
            String text=stripFence(value).stripLeading();
            var matcher=java.util.regex.Pattern.compile("^\\{\\s*\\\"([^\\\"]+)\\\"\\s*:").matcher(text);
            if(!matcher.find())return Shape.UNKNOWN;
            return SECTIONS.contains(matcher.group(1))?Shape.CANONICAL:Shape.LINES;
        }
        private static String stripFence(String value) {
            String text=value.trim();
            if(text.startsWith("```json"))text=text.substring(7).stripLeading();
            else if(text.startsWith("```"))text=text.substring(3).stripLeading();
            if(text.endsWith("```"))text=text.substring(0,text.length()-3).stripTrailing();
            return text;
        }
        private void canonicalDeltas() throws Exception {
            String text=stripFence(received.toString());
            for(String section:SECTIONS){
                List<String> items=arrayItems(text,section);
                int offset=emitted.get(section);
                for(int i=offset;i<items.size();i++)canonicalItem(section,HarnessJson.mapper().readTree(items.get(i)));
            }
        }
        private void canonicalDeltas(JsonNode root) throws Exception {
            for(String section:SECTIONS){
                int offset=emitted.get(section), index=0;
                for(JsonNode item:root.path(section)){if(index++>=offset)canonicalItem(section,item);}
            }
        }
        private void canonicalItem(String section,JsonNode item) throws Exception {
            if(Set.of("communicationFocus","preparationAdvice").contains(section)){
                if(!item.isObject()||item.size()!=2||!item.path("text").isTextual()||!item.path("factRefs").isArray())
                    throw new IllegalArgumentException("INVALID_REPORT_ITEM");
                var refs=new ArrayList<String>();
                item.path("factRefs").forEach(ref->{if(!ref.isTextual())throw new IllegalArgumentException("INVALID_REPORT_FACT_REF");refs.add(ref.asText());});
                add(new ServicePreparation.ReportDelta(section,item.path("text").asText(),refs));
            }else{
                if(!item.isTextual())throw new IllegalArgumentException("INVALID_REPORT_ITEM");
                add(new ServicePreparation.ReportDelta(section,item.asText(),List.of()));
            }
            emitted.put(section,emitted.get(section)+1);
        }
        private static List<String> arrayItems(String text,String section) {
            int key=text.indexOf("\""+section+"\"");if(key<0)return List.of();
            int start=text.indexOf('[',key);if(start<0)return List.of();
            var items=new ArrayList<String>();int itemStart=-1,objects=0,arrays=0;boolean string=false,escape=false;
            for(int i=start+1;i<text.length();i++){
                char c=text.charAt(i);
                if(string){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='\"')string=false;continue;}
                if(c=='\"'){string=true;if(itemStart<0)itemStart=i;continue;}
                if(itemStart<0){if(Character.isWhitespace(c)||c==',')continue;if(c==']')break;itemStart=i;}
                if(c=='{')objects++;else if(c=='}')objects--;else if(c=='[')arrays++;else if(c==']'){
                    if(arrays>0)arrays--;else if(objects==0){String item=text.substring(itemStart,i).trim();if(!item.isEmpty())items.add(item);break;}
                }
                if(c==','&&objects==0&&arrays==0){items.add(text.substring(itemStart,i).trim());itemStart=-1;}
            }
            return items;
        }
        private void line(String raw) throws Exception {
            String line=raw.trim(); if(line.isEmpty()||line.startsWith("```"))return;
            JsonNode item=HarnessJson.mapper().readTree(line);
            if(!item.isObject()||item.size()!=3||!item.path("section").isTextual()||!item.path("text").isTextual()
                    ||!item.path("factRefs").isArray())throw new IllegalArgumentException("INVALID_REPORT_STREAM_ITEM");
            String section=item.path("section").asText();
            var refs=new ArrayList<String>();item.path("factRefs").forEach(ref->{
                if(!ref.isTextual())throw new IllegalArgumentException("INVALID_REPORT_FACT_REF");refs.add(ref.asText());
            });
            add(new ServicePreparation.ReportDelta(section,item.path("text").asText(),refs));
        }
        private void add(ServicePreparation.ReportDelta delta) {
            String section=delta.section();sink.accept(delta);
            if(Set.of("communicationFocus","preparationAdvice").contains(section)) {
                var target=result.withArray(section).addObject();target.put("text",delta.text());
                target.set("factRefs",HarnessJson.mapper().valueToTree(delta.factRefs()));
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
