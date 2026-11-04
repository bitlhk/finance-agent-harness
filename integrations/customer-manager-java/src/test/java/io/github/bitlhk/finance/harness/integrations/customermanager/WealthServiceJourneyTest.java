package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WealthServiceJourneyTest {
    @Test void retainsOnlyReferencesAndUserParameters() {
        var journey=new WealthServiceJourney();
        journey.selectCustomer("周明远");
        journey.selectProducts(List.of("132112"),false);
        journey.parameters(Map.of("amount",100000,"horizonMonths",6,"ignoredBusinessData","secret"));
        journey.evidence(List.of("request-a","trace-a"));
        journey.report("report-a",2,List.of("132112","SYN-FI-002"));
        Map<String,Object> projection=journey.projection();
        assertEquals("NONE",projection.get("authority"));
        assertEquals(Map.of("amount",100000,"horizonMonths",6),projection.get("parameters"));
        assertFalse(projection.toString().contains("secret"));
        assertTrue(journey.reportFollowup("为什么推荐这些产品"));
        assertTrue(journey.reportFollowup("生成一份我可以和客户沟通的话术"));
        assertTrue(journey.reportFollowup("为什么这份报告没有完整完成？"));
        assertTrue(journey.reportFollowup("请解释当前报告"));
        assertFalse(journey.reportFollowup("重新为客户准备一份报告"));
    }

    @Test void customerSwitchClearsCustomerSpecificTaskButNotAnotherSession() {
        var first=new WealthServiceJourney();
        first.selectCustomer("周明远");first.parameters(Map.of("amount",100000));
        first.evidence(List.of("crm-request-a", "knowledge-receipt-a"));
        first.report("report-a",1,List.of("132112"));
        var second=new WealthServiceJourney();
        assertNull(second.customerRef());
        assertFalse(second.hasReport());
        first.selectCustomer("沈嘉禾");
        assertEquals("沈嘉禾",first.customerRef());
        assertTrue(first.parameters().isEmpty());
        assertTrue(first.productRefs().isEmpty());
        assertEquals(List.of(), first.projection().get("evidenceRefs"));
        assertFalse(first.hasReport());
    }

    @Test void productSwitchAndResetAreBounded() {
        var journey=new WealthServiceJourney();
        journey.selectProducts(List.of("132112"),false);
        journey.selectProducts(List.of("SYN-FI-002"),true);
        assertEquals(List.of("132112","SYN-FI-002"),journey.productRefs());
        journey.selectProducts(List.of("SYN-FI-003"),false);
        assertEquals(List.of("SYN-FI-003"),journey.productRefs());
        journey.reset();
        assertTrue(journey.productRefs().isEmpty());
    }
}
