package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.databind.JsonNode;
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
        return interpreter.explain(question,project(report),cancelled);
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
}
