package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.conversation.SavedReportExplainer;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Manager projection only; the tool-free interpreter is shared with stopped-history access. */
final class OpenJiuwenSavedReportExplainer {
    private final SavedReportExplainer interpreter;
    private static final List<String> FIELDS=List.of("status","explanationStatus","reviewStatus",
            "facts","taskContext","modelSuggestion","missingInputs","failureCode","reviewOpinion",
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
        }
    }
    static ObjectNode project(JsonNode report) {
        ObjectNode value=HarnessJson.mapper().createObjectNode();
        for(String field:FIELDS)if(report.has(field))value.set(field,report.get(field).deepCopy());
        if(!"READY".equals(report.path("explanationStatus").asText()))value.remove("modelSuggestion");
        return value;
    }
    static ObjectNode validate(String raw,JsonNode projection) {
        return SavedReportExplainer.validate(raw,projection);
    }
    static ObjectNode archivedFallback(String question,JsonNode projection) {
        JsonNode suggestion=projection.path("modelSuggestion");
        JsonNode focus=suggestion.path("communicationFocus");
        JsonNode advice=suggestion.path("preparationAdvice");
        JsonNode pending=suggestion.path("pendingItems");
        boolean talkTrack=question!=null&&(question.contains("话术")||question.contains("怎么说")||question.contains("沟通提纲"));
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
}
