package io.github.bitlhk.finance.builder.local;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class ResponseFactGuardTest {
    private static List<Object> evidence() {
        return List.of(Map.of("data", Map.of("availableBalance", "12500.50", "currency", "CNY")));
    }

    @Test public void acceptsFormattingOfCurrentEvidenceAmount() {
        assertTrue(ResponseFactGuard.grounded("本行余额为 12,500.5 元。", evidence()));
    }

    @Test public void rejectsUnrelatedHistoricalAmount() {
        assertFalse(ResponseFactGuard.grounded("余额查询完成。另有一笔转账 66 元。", evidence()));
    }

    @Test public void ignoresNonMonetaryNumbers() {
        assertTrue(ResponseFactGuard.grounded("本次完成 1 项查询。", evidence()));
    }

    @Test public void createsAUsefulNonDecisionalFallbackForCompletedSmartAuditTools() {
        List<Object> tools = List.of(
                Map.of("data", Map.of("capabilityId", "smartaudit.personal.borrower-qualification.query", "resultStatus", "COMPLETED")),
                Map.of("data", Map.of("capabilityId", "smartaudit.personal.income-verification.query", "resultStatus", "COMPLETED")));

        String fallback = ResponseFactGuard.conflictFallback(tools);

        assertTrue(fallback.contains("借款人主体核验"));
        assertTrue(fallback.contains("收入核验"));
        assertTrue(fallback.contains("人工复核"));
        assertFalse(fallback.contains("元"));
    }
}
