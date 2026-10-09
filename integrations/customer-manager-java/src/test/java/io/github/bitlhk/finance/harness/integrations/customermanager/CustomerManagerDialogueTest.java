package io.github.bitlhk.finance.harness.integrations.customermanager;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CustomerManagerDialogueTest {
    private static final List<String> CUSTOMERS=List.of("周明远","沈嘉禾","赵婉清");

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
    @Test void productRecommendationRemainsOutsideInstalledScope() {
        assertEquals(CustomerManagerDialogue.Action.UNSUPPORTED_PRODUCT,
                CustomerManagerDialogue.resolve("给周明远推荐理财产品",CUSTOMERS,null).action());
    }
}
