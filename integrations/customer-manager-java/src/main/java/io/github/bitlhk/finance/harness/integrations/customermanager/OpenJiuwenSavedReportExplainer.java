package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation.SavedReportExplainer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Manager projection only; the tool-free interpreter is shared with stopped-history access. */
final class OpenJiuwenSavedReportExplainer {
    private final SavedReportExplainer interpreter;
    private static final List<String> FIELDS=List.of("status","explanationStatus","reviewStatus",
            "facts","taskContext","modelSuggestion","missingInputs","failureCode","reviewOpinion",
            "fallbackSuggestion","factAssessment","productReview",
            "startedAt","finishedAt","reviewedAt");
    OpenJiuwenSavedReportExplainer(ManagedScenarioExtensionV01.ModelConnection model) {
        interpreter=new SavedReportExplainer(model);
    }
    ObjectNode explain(String question,JsonNode report,BooleanSupplier cancelled) throws Exception {
        ObjectNode projection=project(report);
        try {
            return interpreter.explain(question,projection,cancelled);
        } catch(IllegalArgumentException failure) {
            if(!"CONVERSATION_ANSWER_INVALID".equals(failure.getMessage()))throw failure;
            return archivedFallback(question,projection);
        } catch(CancellationException failure) {
            throw failure;
        } catch(Exception failure) {
            return archivedFallback(question,projection);
        }
    }
    static ObjectNode project(JsonNode report) {
        ObjectNode value=HarnessJson.mapper().createObjectNode();
        for(String field:FIELDS)if(report.has(field))value.set(field,report.get(field).deepCopy());
        if(!"READY".equals(report.path("explanationStatus").asText()))value.remove("modelSuggestion");
        JsonNode review=value.path("productReview");
        if(review.isObject()) {
            ObjectNode summary=value.putObject("productReviewSummary");
            summary.put("eligibleProductCount",review.path("eligibleProducts").size());
            summary.put("sourceProductCount",review.path("sourceProducts").size());
        }
        return value;
    }
    static ObjectNode validate(String raw,JsonNode projection) {
        return SavedReportExplainer.validate(raw,projection);
    }
    static ObjectNode archivedFallback(String question,JsonNode projection) {
        boolean talkTrack=question!=null&&(question.contains("话术")||question.contains("怎么说")||question.contains("沟通提纲"));
        JsonNode review=projection.path("productReviewSummary");
        if(review.isObject()&&review.path("eligibleProductCount").asInt(-1)==0)
            return noEligibleProductsFallback(talkTrack,projection);
        JsonNode suggestion=projection.has("modelSuggestion")
                ?projection.path("modelSuggestion"):projection.path("fallbackSuggestion");
        JsonNode focus=suggestion.path("communicationFocus");
        JsonNode advice=suggestion.path("preparationAdvice");
        JsonNode pending=suggestion.path("pendingItems");
        JsonNode source=talkTrack?focus:advice;
        String sourceField=talkTrack?"communicationFocus":"preparationAdvice";
        if(!source.isArray()||source.isEmpty())throw new IllegalArgumentException("CONVERSATION_ANSWER_INVALID");
        StringBuilder answer=new StringBuilder(talkTrack?"可按以下提纲与客户沟通：":"");
        ArrayNode refs=HarnessJson.mapper().createArrayNode();
        int sourceCount=Math.min(talkTrack?3:1,source.size());
        for(int index=0;index<sourceCount;index++) {
            if(answer.length()>0)answer.append("\n");
            answer.append(talkTrack?(index+1)+". ":"").append(source.path(index).path("text").asText());
            refs.add("/modelSuggestion/"+sourceField+"/"+index+"/text");
        }
        if(!talkTrack&&pending.isArray()&&!pending.isEmpty()) {
            answer.append("\n\n仍需核实：");
            int count=Math.min(3,pending.size());
            for(int index=0;index<count;index++) {
                answer.append("\n- ").append(pending.path(index).asText());
                refs.add("/modelSuggestion/pendingItems/"+index);
            }
        }
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("schema","finance-saved-report-answer.v1");
        result.put("answer",answer.length()>4000?answer.substring(0,4000):answer.toString());
        result.set("refs",refs);
        return SavedReportExplainer.validate(result.toString(),projection);
    }
    private static ObjectNode noEligibleProductsFallback(boolean talkTrack,JsonNode projection) {
        String answer=talkTrack
                ?"本轮没有形成可确认的产品候选，不能生成具体产品推介话术。可先向客户确认资金期限、流动性和办理渠道，并说明销售资格、实时状态及适当性仍需由业务系统核验。"
                :"本轮没有形成可确认的产品候选。当前产品来源的销售资格、业务日期、期限或渠道资料不完整，不能据此推荐；需先由业务系统核验。";
        ArrayNode refs=HarnessJson.mapper().createArrayNode().add("/productReviewSummary/eligibleProductCount");
        JsonNode missing=projection.path("factAssessment").path("missing");
        if(missing.isArray()&&!missing.isEmpty()&&missing.path(0).path("label").isTextual())
            refs.add("/factAssessment/missing/0/label");
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("schema","finance-saved-report-answer.v1").put("answer",answer).set("refs",refs);
        return SavedReportExplainer.validate(result.toString(),projection);
    }
}
