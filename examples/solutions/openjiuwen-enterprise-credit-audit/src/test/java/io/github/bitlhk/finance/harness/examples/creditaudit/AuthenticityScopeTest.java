package io.github.bitlhk.finance.harness.examples.creditaudit;

import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticityScopeTest {
    private static List<CapabilityResultV1> facts() throws Exception {
        try (var input=AuthenticityScopeTest.class.getResourceAsStream("/audit-authenticity-first-failure.json")) {
            var root=HarnessJson.mapper().readTree(input);
            return List.of(HarnessJson.mapper().treeToValue(root.path("deterministicResults").get(0),CapabilityResultV1.class),
                    HarnessJson.mapper().treeToValue(root.path("deterministicResults").get(1),CapabilityResultV1.class));
        }
    }

    @Test void frozenFullFirstResponsePassesWithoutChangingStoredFailure() throws Exception {
        try (var input=getClass().getResourceAsStream("/audit-authenticity-first-failure.json")) {
            var root=HarnessJson.mapper().readTree(input);
            String text=root.path("explanation").asText();
            assertEquals(2565,text.length());
            assertEquals("FAILED",root.path("explanationStatus").asText());
            assertEquals("IllegalArgumentException: MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT",root.path("explanationError").asText());
            assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
        }
    }

    @TestFactory Stream<DynamicTest> boundedScopeCases() {
        var allowed=List.of(
            "不构成核验失败或材料虚假的结论。",
            "不代表材料虚假。",
            "不意味着真实性验证失败或材料虚假。",
            "不等于材料虚假。",
            "不能据此认定材料虚假。",
            "尚未核验，不能说未通过真实性验证或材料虚假。",
            "不代表真实性验证失败，也不构成材料虚假结论。",
            "既不代表真实性验证失败，也不代表材料虚假。",
            "没有证据证明材料虚假。",
            "报告引用外部原话“材料虚假”，但当前尚未核验，不能采信该说法。",
            "不能认定材料虚假，但需另行核验。",
            "不构成真实性核验失败或真实性不合格。"
        );
        var denied=List.of(
            "材料虚假。", "真实性验证失败。", "材料未通过真实性验证。",
            "不构成审批结论，但材料虚假。",
            "不能认定材料虚假，然而材料虚假已获确认。",
            "不代表真实性验证失败，材料虚假。",
            "不代表材料虚假；材料虚假。",
            "不能不认定材料虚假。",
            "并非不代表材料虚假。",
            "不是不构成材料虚假。",
            "不得不说材料虚假。",
            "不代表材料有效，实际已经确认材料虚假。",
            "不能认为没有材料虚假。",
            "不构成任何审批意见，因此材料虚假。",
            "有人声称“材料虚假”。",
            "有人声称“材料虚假”，但没有说不能采信该说法。",
            "不能说未通过真实性验证，且可以认定材料虚假。"
        );
        return Stream.concat(allowed.stream().map(text->DynamicTest.dynamicTest("allow: "+text,()->
                assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts())))),
            denied.stream().map(text->DynamicTest.dynamicTest("deny: "+text,()-> {
                var error=assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
                assertEquals("MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT",error.getMessage());
            })));
    }

    // Fixed M01-M18: variants are assertions within a case, not additional case counts.
    @TestFactory Stream<DynamicTest> frozenSemanticMatrix() {
        return java.util.stream.IntStream.rangeClosed(1,18).mapToObj(id ->
                DynamicTest.dynamicTest(String.format("M%02d",id),()->semanticCase(id)));
    }

    private void semanticCase(int id) throws Exception {
        switch(id) {
            case 1 -> fullFixture("audit-authenticity-first-failure.json",2565);
            case 2 -> fullFixture("audit-authenticity-second-failure.json",2866);
            case 3 -> allow("尚未核验，不得解释为真实性验证失败或材料虚假。");
            case 4 -> allow("真实性**尚未核验**，**不得**解释为真实性验证失败或材料虚假。");
            case 5 -> {
                allow("*不得*解释为真实性验证失败或材料虚假。");
                allow("`不得`解释为真实性验证失败或材料虚假。");
            }
            case 6 -> {
                allow("不得\t解释为\n真实性验证失败或材料虚假。");
                allow("不得\u00a0解释为真实性验证失败或材料虚假。");
            }
            case 7 -> {
                deny("不得解释为\n\n材料虚假。");
                deny("不得解释为\n- 材料虚假。");
                deny("不得解释为\n## 材料虚假");
                deny("不得解释为  \n材料虚假。");
                deny("不得解释为\\\n材料虚假。");
            }
            case 8 -> {
                allow("不得解释为真实性验证失败以及材料虚假。");
                allow("既不代表真实性验证失败，也不代表材料虚假。");
            }
            case 9 -> {
                deny("不得解释为真实性验证失败，但材料虚假。");
                deny("不得解释为材料虚假。然而材料虚假已获确认。");
            }
            case 10 -> deny("不得不说材料虚假。");
            case 11 -> deny("**并非**不代表材料虚假。");
            case 12 -> allow("报告引用外部原话“**材料虚假**”，但当前尚未核验，不能采信该说法。");
            case 13 -> {
                deny("有人声称“**材料虚假**”。");
                deny("有人声称“材料虚假”，但没有说不能采信该说法。");
                deny("有人声称“材料虚假”，不**能**采信该说法。");
            }
            case 14 -> {
                deny("材料虚假。");
                deny("SYNTHETIC_PRIVATE_MARKER：材料虚假。");
            }
            case 15 -> {
                deny("材**料**虚假。");
                deny("不**得**解释为材料虚假。");
                deny("不 得解释为材料虚假。");
            }
            case 16 -> {
                var mixed=List.of(fact(0,"FAILED"),fact(1,"NOT_VERIFIED"));
                String object=String.valueOf(mixed.get(0).data().get("materialSetRef"));
                String check=String.valueOf(mixed.get(0).data().get("checkId"));
                assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        object+" / "+check+"：真实性核验失败。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "materials-other / "+check+"：真实性核验失败。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        object+" / application-contract-consistency：真实性核验失败。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        object+" / "+check+"：材料虚假。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        object+"-other / "+check+"：真实性核验失败。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        object+" / "+check+"-other：真实性核验失败。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "materials-other，真实性核验失败。",List.of(fact(0,"FAILED"))));
            }
            case 17 -> {
                var mixed=List.of(fact(0,"FAILED"),fact(1,"NOT_VERIFIED"));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "两项真实性均验证失败，材料虚假。",mixed));
                assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "真实性核验失败。",List.of(fact(0,"FAILED"),fact(1,"FAILED"))));
            }
            case 18 -> {
                var error=assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(
                        "不得解释为材料虚假，建议批准授信。",facts()));
                assertEquals("MODEL_DECISION_BOUNDARY_VIOLATION",error.getMessage());
            }
            default -> throw new AssertionError(id);
        }
    }

    private static CapabilityResultV1 fact(int index,String state) throws Exception {
        var tree=HarnessJson.mapper().valueToTree(facts().get(index));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data")).put("authenticityStatus",state);
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("data").path("authenticityAssessment")).put("status",state);
        return HarnessJson.mapper().treeToValue(tree,CapabilityResultV1.class);
    }

    private void fullFixture(String name,int length) throws Exception {
        try(var input=getClass().getResourceAsStream("/"+name)) {
            var root=HarnessJson.mapper().readTree(input);
            assertEquals(length,root.path("explanation").asText().length());
            assertEquals("FAILED",root.path("explanationStatus").asText());
            var results=new java.util.ArrayList<CapabilityResultV1>();
            for(var item:root.path("deterministicResults"))results.add(HarnessJson.mapper().treeToValue(item,CapabilityResultV1.class));
            assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(root.path("explanation").asText(),results));
        }
    }
    private static void allow(String text) throws Exception {
        assertDoesNotThrow(()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
    }
    private static void deny(String text) throws Exception {
        var error=assertThrows(IllegalArgumentException.class,()->EnterpriseCreditAuditApplication.ExplanationGuard.requireAllowed(text,facts()));
        assertEquals("MODEL_EXPLANATION_AUTHENTICITY_STATUS_CONFLICT",error.getMessage());
        var detail=((AuditAuthenticityPolicy.Rejected)error).diagnostic();
        assertEquals(java.util.Set.of("rule","start","end","offsetUnit","reason"),detail.keySet());
        assertEquals("UTF16",detail.get("offsetUnit"));
        int start=(int)detail.get("start"),end=(int)detail.get("end");
        assertTrue(start>=0&&end<=text.length()&&end>start);
        assertFalse(detail.toString().contains("SYNTHETIC_PRIVATE_MARKER"));
        assertFalse(detail.toString().contains("材料"));
        if(text.equals("材**料**虚假。"))assertEquals("材**料**虚假",text.substring(start,end));
    }
}
