package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class SavedReportExplanationTest {
    @Test void projectionExcludesIdentityAndFailedModelText() {
        var original=HarnessJson.mapper().createObjectNode().put("ownerRef","private-owner")
                .put("authorityRef","private-authority").put("status","PARTIAL").put("explanationStatus","FAILED");
        original.putObject("modelSuggestion").put("text","UNVALIDATED_SYNTHETIC_TEXT");
        var value=OpenJiuwenSavedReportExplainer.project(original);
        assertFalse(value.has("ownerRef"));assertFalse(value.has("authorityRef"));
        assertFalse(value.has("modelSuggestion"));assertEquals("PARTIAL",value.path("status").asText());
        original.put("explanationStatus","READY");
        assertTrue(OpenJiuwenSavedReportExplainer.project(original).has("modelSuggestion"));
    }
    @Test void onlyExistingLeafReferencesAreAccepted() {
        var report=HarnessJson.mapper().createObjectNode().put("status","FAILED");
        report.putObject("facts").put("amount",70);
        String valid="{\"schema\":\"finance-saved-report-answer.v1\",\"answer\":\"上次执行失败，未重新查询。\",\"refs\":[\"/status\"]}";
        assertDoesNotThrow(()->OpenJiuwenSavedReportExplainer.validate(valid,report));
        for(String pointer:new String[]{"/missing","/facts","/ownerRef","not-a-pointer"}) {
            assertThrows(IllegalArgumentException.class,()->OpenJiuwenSavedReportExplainer.validate(valid.replace("/status",pointer),report));
        }
        assertThrows(IllegalArgumentException.class,()->OpenJiuwenSavedReportExplainer.validate(valid.replace("\"refs\":[\"/status\"]","\"refs\":[]"),report));
        assertThrows(IllegalArgumentException.class,()->OpenJiuwenSavedReportExplainer.validate(valid.replace("\"schema\":","\"owner\":\"forged\",\"schema\":"),report));
    }
    @Test void duplicateAndTrailingPayloadDoNotBecomePublicParserErrors() {
        var report=HarnessJson.mapper().createObjectNode().put("status","FAILED");
        String bad="{\"schema\":\"finance-saved-report-answer.v1\",\"answer\":\"PRIVATE_SYNTHETIC\",\"answer\":\"duplicate\",\"refs\":[\"/status\"]}";
        var error=assertThrows(IllegalArgumentException.class,()->OpenJiuwenSavedReportExplainer.validate(bad,report));
        assertEquals("CONVERSATION_ANSWER_INVALID",error.getMessage());
    }
}
