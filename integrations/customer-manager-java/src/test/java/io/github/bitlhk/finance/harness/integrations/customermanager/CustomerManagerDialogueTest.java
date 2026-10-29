package io.github.bitlhk.finance.harness.integrations.customermanager;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CustomerManagerDialogueTest {
    private static final List<String> CUSTOMERS=List.of("周明远","沈嘉禾","赵婉清");

    @Test void aSelectedCustomerIsNotPermissionToExecuteEveryUtterance() {
        for (String query : List.of("你好", "解释刚才报告的70万元", "帮我订机票", "帮周明远分析理财")) {
            assertNotEquals(CustomerManagerDialogue.Action.PREPARE,
                    CustomerManagerDialogue.resolve(query,CUSTOMERS,"周明远").action(), query);
        }
    }

    @Test void greetingsCannotResumeAnEarlierTask() {
        for (String query : List.of("你好", "您好！", "谢谢", "再见")) {
            assertEquals("GREETING",CustomerManagerDialogue.resolve(query,CUSTOMERS,"周明远").action().name());
        }
    }

    @Test void explicitPreparationStillSelectsOnlyAnAuthorizedAlias() {
        assertEquals(CustomerManagerDialogue.Action.PREPARE,
                CustomerManagerDialogue.resolve("请为周明远准备回访",CUSTOMERS,null).action());
        assertNotEquals(CustomerManagerDialogue.Action.PREPARE,
                CustomerManagerDialogue.resolve("不要准备回访",CUSTOMERS,"周明远").action());
    }

    @Test void listsAuthorizedCustomersWithoutPreparing() {
        assertEquals(CustomerManagerDialogue.Action.LIST_CUSTOMERS,
                CustomerManagerDialogue.resolve("我负责哪些客户？",CUSTOMERS,null).action());
    }
    @Test void describesInstalledBoundaryWithoutSelectingCustomer() {
        assertEquals(CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES,
                CustomerManagerDialogue.resolve("你现在能做什么？",CUSTOMERS,null).action());
        assertEquals(CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES,
                CustomerManagerDialogue.resolve("你现在能帮我做什么？",CUSTOMERS,"周明远").action());
        assertEquals(CustomerManagerDialogue.Action.DESCRIBE_CAPABILITIES,
                CustomerManagerDialogue.resolve("这个助手如何使用？",CUSTOMERS,"周明远").action());
    }
    @Test void resolvesNamedCustomerAndKeepsItForFollowUp() {
        var first=CustomerManagerDialogue.resolve("帮我准备周明远的回访",CUSTOMERS,null);
        assertEquals(CustomerManagerDialogue.Action.PREPARE,first.action());assertEquals("周明远",first.customer());
        assertEquals("周明远",CustomerManagerDialogue.resolve("再看看缺项",CUSTOMERS,first.customer()).customer());
    }
    @Test void resolvesUniqueShortNameOnlyWithinTheAuthorizedSyntheticList() {
        var result=CustomerManagerDialogue.resolve("帮我准备周明远的回访",List.of("合成客户周明远","合成客户沈嘉禾"),null);
        assertEquals(CustomerManagerDialogue.Action.PREPARE,result.action());
        assertEquals("合成客户周明远",result.customer());
    }
    @Test void rejectsAmbiguousOrMissingCustomer() {
        assertEquals(CustomerManagerDialogue.Action.CLARIFY_MULTIPLE,
                CustomerManagerDialogue.resolve("比较周明远和沈嘉禾",CUSTOMERS,null).action());
        assertEquals(CustomerManagerDialogue.Action.CLARIFY_CUSTOMER,
                CustomerManagerDialogue.resolve("帮我准备回访",CUSTOMERS,null).action());
    }
    @Test void productRequestEntersControlledReviewFlow() {
        assertEquals(CustomerManagerDialogue.Action.PRODUCT_REVIEW,
                CustomerManagerDialogue.resolve("给周明远推荐理财产品",CUSTOMERS,null).action());
        assertEquals(CustomerManagerDialogue.Action.PRODUCT_REVIEW,
                CustomerManagerDialogue.resolve("为周明远筛选10万元的产品候选",CUSTOMERS,null).action());
    }

    @Test void negatedProductLanguageDoesNotOverrideExplicitPreparation() {
        var result=CustomerManagerDialogue.resolve(
                "只做澄清性回访，不正式推荐或业务写回",CUSTOMERS,"周明远");
        assertEquals(CustomerManagerDialogue.Action.PREPARE,result.action());
        assertEquals("周明远",result.customer());
        assertTrue(CustomerManagerDialogue.rejectsProductSelection("先不推荐产品，帮我准备回访"));
        assertTrue(CustomerManagerDialogue.requestsPreparation("先不推荐产品，帮我准备回访"));
    }

    @Test void explicitCancellationClosesProductTaskInsteadOfPreparingAReport() {
        assertEquals(CustomerManagerDialogue.Action.CANCEL_PRODUCT,
                CustomerManagerDialogue.resolve("取消产品筛选",CUSTOMERS,"周明远").action());
        assertEquals(CustomerManagerDialogue.Action.CANCEL_PRODUCT,
                CustomerManagerDialogue.resolve("暂不推荐产品",CUSTOMERS,"周明远").action());
    }
}
