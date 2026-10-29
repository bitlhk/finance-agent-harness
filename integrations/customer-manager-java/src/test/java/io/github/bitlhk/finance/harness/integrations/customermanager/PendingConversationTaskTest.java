package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PendingConversationTaskTest {
    private final Instant now=Instant.parse("2026-10-27T00:00:00Z");
    private final ConversationConfirmation.Binding binding=new ConversationConfirmation.Binding("owner","instance","lock",1,"scope");
    private PendingConversationTask product(String query,String customer) {
        return PendingConversationTask.start(binding,ConversationIntent.Kind.PRODUCT_REVIEW,query,customer,now);
    }
    @Test void amountTermChannelAreSeparateInputsNotAnExecutableApproval() {
        var pending=product("筛选10万元的产品",null);
        assertEquals(List.of("customerAlias","horizonMonths","channel"),pending.missing());
        pending=pending.supplement("客户A","A").supplement("12个月",null).supplement("手机银行",null);
        assertTrue(pending.missing().isEmpty());assertEquals("A",pending.object());
        assertEquals("NONE",pending.projection().get("authority"));
        assertFalse(pending.projection().containsKey("confirmationId"));
    }
    @Test void currentParameterCorrectionOverridesOldValueWithoutReparsingOldText() {
        var pending=product("10万元、12个月、手机银行","A").supplement("改为20万元",null);
        assertEquals(200000.0,pending.parameters().get("amount"));
        assertEquals(12,pending.parameters().get("horizonMonths"));
        assertThrows(UnsupportedOperationException.class,()->pending.parameters().put("amount",1));
    }
    @Test void differentObjectDropsEveryOldParameterAndOriginalCustomerText() {
        var pending=product("客户A，10万元、12个月、手机银行","A").supplement("客户B","B");
        assertEquals("客户B",pending.query());assertTrue(pending.parameters().isEmpty());
        assertEquals(List.of("amount","horizonMonths","channel"),pending.missing());
    }
    @Test void supplementsDoNotExtendExpiryAndChangedBindingsInvalidate() {
        var pending=product("筛选产品","A").supplement("10万元",null);
        assertTrue(pending.valid(binding,now.plusSeconds(299)));
        assertFalse(pending.valid(binding,now.plusSeconds(300)));
        for(var changed:List.of(
                new ConversationConfirmation.Binding("other","instance","lock",1,"scope"),
                new ConversationConfirmation.Binding("owner","replacement","lock",1,"scope"),
                new ConversationConfirmation.Binding("owner","instance","new-lock",1,"scope"),
                new ConversationConfirmation.Binding("owner","instance","lock",2,"scope"),
                new ConversationConfirmation.Binding("owner","instance","lock",1,"revoked")))
            assertFalse(pending.valid(changed,now));
    }
    @Test void arbitraryNonBusinessKindCannotBecomePendingAction() {
        assertThrows(IllegalArgumentException.class,()->PendingConversationTask.start(binding,
                ConversationIntent.Kind.CLARIFY,"hello","A",now));
    }
    @Test void confirmationOwnsImmutableParametersAndStillChecksBindingAndOneShotClaim() {
        var confirmation=new ConversationConfirmation(java.time.Clock.fixed(now,java.time.ZoneOffset.UTC));
        var params=new HashMap<String,Object>(Map.of("amount",100000.0,"horizonMonths",12,"channel","手机银行"));
        var intent=new ConversationIntent(ConversationIntent.Kind.PRODUCT_REVIEW,"A",null,null);
        var plan=confirmation.propose(binding,intent,"明确筛选",params);
        params.put("amount",999.0);
        var claimed=confirmation.claim(plan.id(),binding,Set.of("A"),Set.of(ConversationIntent.Kind.PRODUCT_REVIEW));
        assertEquals(100000.0,claimed.parameters().get("amount"));
        assertThrows(SecurityException.class,()->confirmation.claim(plan.id(),binding,Set.of("A"),Set.of(ConversationIntent.Kind.PRODUCT_REVIEW)));
    }
}
