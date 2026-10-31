package io.github.bitlhk.finance.harness.integrations.customermanager;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CustomerManagerScenarioExtensionInputTest {
    @Test void builderActionDeclarationMatchesActualExtension() throws Exception {
        String builder = System.getenv("FH_H1B_BUILDER_ROOT");
        org.junit.jupiter.api.Assumptions.assumeTrue(builder != null, "Cross-repository Builder checkout required");
        var file = java.nio.file.Path.of(builder).resolve("community/finance/builder/finance-solution-builder/templates/local-readonly-service/employee-host-support.v0.1.json");
        var declaration = HarnessJson.mapper().readTree(file.toFile()).path("scenarioExtension");
        var actual = new CustomerManagerScenarioExtension();
        var actions = new java.util.HashSet<String>();
        declaration.path("actions").forEach(action -> assertTrue(actions.add(action.asText())));
        assertEquals(actual.id(), declaration.path("id").asText());
        assertEquals(actual.actions(), actions);
    }
    @Test void confirmationScopeIncludesTrustedAliasToResourceMappingWithoutDependingOnMapOrder() {
        var authority=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmAuthority("owner-a","employee-a","r1",
                java.util.Set.of("ref-a","ref-b"),java.time.Instant.parse("2030-01-01T00:00:00Z"),
                io.github.bitlhk.finance.harness.core.FinanceContextV1.AuthenticationLevel.BASIC);
        var first=new java.util.LinkedHashMap<String,String>();first.put("A","ref-a");first.put("B","ref-b");
        var reordered=new java.util.LinkedHashMap<String,String>();reordered.put("B","ref-b");reordered.put("A","ref-a");
        var a=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmEmployeeMapping(authority,first);
        var b=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmEmployeeMapping(authority,reordered);
        assertEquals(CustomerManagerScenarioExtension.conversationScopeDigest(a),CustomerManagerScenarioExtension.conversationScopeDigest(b));
        var changed=new io.github.bitlhk.finance.harness.providers.customercrm.CustomerCrmEmployeeMapping(authority,java.util.Map.of("A","ref-b","B","ref-a"));
        org.junit.jupiter.api.Assertions.assertNotEquals(CustomerManagerScenarioExtension.conversationScopeDigest(a),CustomerManagerScenarioExtension.conversationScopeDigest(changed));
    }
    @Test void optionalObjectIsAValidatedHintNotAnAuthorityField() throws Exception {
        assertDoesNotThrow(() -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"你好\",\"runId\":\"r\",\"customerAlias\":\"\"}")));
        for (String value : java.util.List.of("null", "42", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> CustomerManagerScenarioExtension.exactChat(
                    HarnessJson.mapper().readTree("{\"query\":\"你好\",\"runId\":\"r\",\"customerAlias\":"+value+"}")));
        }
        assertThrows(IllegalArgumentException.class, () -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"你好\",\"runId\":\"r\",\"customerAlias\":\"客户\",\"owner\":\"other\"}")));
    }
    @Test
    void acceptsWorkbenchRunCorrelationWithoutRelaxingTheActionBody() throws Exception {
        assertDoesNotThrow(() -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"runId\":\"run-1\"}")));
        assertThrows(IllegalArgumentException.class, () -> CustomerManagerScenarioExtension.exactChat(
                HarnessJson.mapper().readTree("{\"query\":\"我负责哪些客户？\",\"customerRef\":\"CUST-1\"}")));
    }

    @Test void syntheticMarkerIsNotExposedInCustomerFacingNames() {
        assertEquals("周明远", CustomerManagerScenarioExtension.displayName("合成客户周明远"));
        assertEquals("周明远、沈嘉禾", CustomerManagerScenarioExtension.displayNames(
                java.util.List.of("合成客户周明远", "合成客户沈嘉禾")));
        assertEquals("周明远", CustomerManagerScenarioExtension.displayName("周明远"));
    }

    @Test void extractsOnlyExplicitProductReviewParameters() {
        var values=CustomerManagerScenarioExtension.productArguments("为周明远筛选10万元、12个月、手机银行可办理的产品候选");
        assertEquals(100000.0,values.get("amount"));
        assertEquals(12,values.get("horizonMonths"));
        assertEquals("手机银行",values.get("channel"));
        assertTrue(CustomerManagerScenarioExtension.productArguments("推荐一个产品").isEmpty());
    }

    @Test void pendingProductFlowContinuesForParametersButYieldsToExplicitPreparation() {
        assertTrue(CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PREPARE,true,"手机银行"));
        assertTrue(CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PRODUCT_REVIEW,false,"为周明远筛选产品"));
        assertEquals(false,CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.PREPARE,true,"先不推荐产品，帮我准备周明远的回访"));
        assertEquals(false,CustomerManagerScenarioExtension.shouldUseProductFlow(
                CustomerManagerDialogue.Action.CANCEL_PRODUCT,true,"取消产品筛选"));
    }
}
