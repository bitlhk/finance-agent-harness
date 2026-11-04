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
    @Test void invalidModelAnswerMayFallBackOnlyToArchivedValidatedSuggestion() {
        var report=HarnessJson.mapper().createObjectNode().put("status","PARTIAL").put("explanationStatus","READY");
        var suggestion=report.putObject("modelSuggestion");
        suggestion.putArray("preparationAdvice").addObject().put("text","当前没有通过事实条件筛选的候选产品。");
        suggestion.putArray("pendingItems").add("核实产品销售资格。")
                .add("核实到期资金去向。");
        suggestion.putArray("communicationFocus").addObject().put("text","先确认到期资金去向。");
        var projection=OpenJiuwenSavedReportExplainer.project(report);
        var result=OpenJiuwenSavedReportExplainer.archivedFallback("为什么没有候选？",projection);
        assertTrue(result.path("answer").asText().startsWith("当前没有通过事实条件筛选"));
        assertTrue(result.path("answer").asText().contains("核实产品销售资格"));
        assertEquals("/modelSuggestion/preparationAdvice/0/text",result.path("refs").path(0).asText());
        var talkTrack=OpenJiuwenSavedReportExplainer.archivedFallback("帮我生成沟通话术",projection);
        assertTrue(talkTrack.path("answer").asText().startsWith("可按以下提纲"));
        assertTrue(talkTrack.path("answer").asText().contains("先确认到期资金去向"));
        assertFalse(talkTrack.path("answer").asText().contains("核实产品销售资格"));
        assertThrows(IllegalArgumentException.class,()->OpenJiuwenSavedReportExplainer.archivedFallback("为什么",
                HarnessJson.mapper().createObjectNode().put("status","PARTIAL")));
    }
    @Test void failedReportWithNoEligibleProductsUsesArchivedBusinessBoundary() {
        var report=HarnessJson.mapper().createObjectNode().put("status","PARTIAL").put("explanationStatus","FAILED");
        report.putObject("productReview").putArray("eligibleProducts");
        report.withObject("productReview").putArray("sourceProducts").addObject().put("productId","P001");
        report.putObject("factAssessment").putArray("missing").addObject()
                .put("label","产品来源信息或销售资格尚未完整核实");
        var projection=OpenJiuwenSavedReportExplainer.project(report);
        assertEquals(0,projection.path("productReviewSummary").path("eligibleProductCount").asInt());
        var reason=OpenJiuwenSavedReportExplainer.archivedFallback("为什么选这些？",projection);
        assertTrue(reason.path("answer").asText().contains("没有形成可确认的产品候选"));
        assertTrue(reason.path("answer").asText().contains("不能据此推荐"));
        var talkTrack=OpenJiuwenSavedReportExplainer.archivedFallback("生成沟通话术",projection);
        assertTrue(talkTrack.path("answer").asText().contains("不能生成具体产品推介话术"));
        assertEquals("/productReviewSummary/eligibleProductCount",reason.path("refs").path(0).asText());
    }
}
