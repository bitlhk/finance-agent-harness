package io.github.bitlhk.finance.harness.creditcard;

import java.nio.file.Path;
import java.time.Instant;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CreditStateStoreTest {
    @TempDir Path directory;
    static CreditContracts.Turn turn(String session,String question) throws Exception {
        var b=CreditContracts.JSON.readTree("""
          {"tenant_id":"tenant-1","principal_id":"manager-1","customer_id":"customer-1",
           "case_id":"case-1","observed_at":"2026-06-12T00:00:00Z",
           "visible_source_ids":["ledger"],"snapshot_digest":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
          """);
        return new CreditContracts.Turn("operation-1",session,"limit-risk",question,
                new CreditContracts.Binding(CreditContracts.object(b)),Instant.now().plusSeconds(20));
    }
    @Test void terminalIsAtomicAndLateCompletionCannotOverrideCancellation() throws Exception {
        var turn=turn("session-1","额度风险");
        try(var store=new CreditStateStore(directory)){
            assertNull(store.begin(turn));
            assertEquals("running",store.begin(turn).path("status").asText());
            store.finish(turn,"cancelled",CreditContracts.JSON.createObjectNode());
            store.appendAudit(turn,CreditContracts.JSON.createObjectNode().put("call_id","call-1").put("kind","tool")
                    .put("name","late-forecast").put("status","running").put("elapsed_ms",0));
            store.appendAudit(turn,CreditContracts.JSON.createObjectNode().put("call_id","call-1").put("kind","tool")
                    .put("name","late-forecast").put("status","complete").put("elapsed_ms",12));
            var late=store.finish(turn,"complete",CreditContracts.JSON.createObjectNode().put("invented",true));
            assertEquals("cancelled",late.path("status").asText());
            assertFalse(late.path("result").has("invented"));
            assertEquals(1,late.path("result").path("execution").size());
            assertEquals(12,late.path("execution").get(0).path("elapsed_ms").asInt());
            assertThrows(IllegalArgumentException.class,()->store.begin(turn("session-1","不同输入")));
            assertNull(store.begin(turn("session-2","不同输入")));
            assertThrows(IOException.class,()->new CreditStateStore(directory));
        }
        try(var restarted=new CreditStateStore(directory)){
            assertEquals("cancelled",restarted.read(turn).path("status").asText());
            assertEquals("PREVIOUS_COORDINATOR_STOPPED",restarted.read(turn("session-2","不同输入"))
                    .path("result").path("code").asText());
        }
    }
    @Test void benefitsMemoryRequiresSameCurrentSourceBindingAndPrivatePartialIsNotReleased() throws Exception {
        var view=BenefitsValidatorTest.view();var first=BenefitsAgentTest.turn(view,"op-first","session-benefits","推荐");
        try(var store=new CreditStateStore(directory)){
            store.begin(first);var result=CreditContracts.JSON.createObjectNode().put("goal","旧目标");
            result.putArray("excluded_benefit_ids").add("benefit-1");store.finish(first,"complete",result);
            var next=BenefitsAgentTest.turn(view,"op-next","session-benefits","继续");
            assertTrue(store.benefitsMemory(next).has("excluded_benefit_ids"));
            var changed=next.binding().value();changed.put("snapshot_digest","c".repeat(64));
            var narrowed=new CreditContracts.Turn("op-narrow","session-benefits","benefits","继续",new CreditContracts.Binding(changed),Instant.now().plusSeconds(20));
            assertFalse(store.benefitsMemory(narrowed).has("excluded_benefit_ids"));
            changed=next.binding().value();changed.putArray("visible_source_ids").add("ledger");
            var revoked=new CreditContracts.Turn("op-revoked","session-benefits","benefits","继续",new CreditContracts.Binding(changed),Instant.now().plusSeconds(20));
            assertFalse(store.benefitsMemory(revoked).has("excluded_benefit_ids"));
            store.begin(next);store.retainVerified(next,CreditContracts.JSON.createObjectNode().put("verified","before-deadline"));
            var timedOut=store.finish(next,"timeout",CreditContracts.JSON.createObjectNode().put("code","DEADLINE_EXCEEDED"));
            assertFalse(timedOut.has("retained_verified_results"));assertFalse(store.read(next).has("retained_verified_results"));
            try(var paths=java.nio.file.Files.list(directory)){
                assertTrue(paths.filter(p->p.toString().endsWith(".json")).anyMatch(p->{
                    try{return java.nio.file.Files.readString(p).contains("before-deadline");}catch(IOException e){throw new RuntimeException(e);}
                }));
            }
        }
    }
    @Test void conversationDirectoryIsDurableOwnerScopedAndIndependentAcrossThreads() throws Exception {
        String selected;
        try(var store=new CreditStateStore(directory)){
            var a=store.createConversation("tenant:manager","benefits","customer-ref-1");
            var b=store.createConversation("tenant:manager","benefits","customer-ref-1");
            assertNotEquals(a.path("thread"),b.path("thread"));
            a.withArray("messages").addObject().put("role","user").put("text","只在对话A");
            store.saveConversation("tenant:manager","benefits",a);selected=a.path("id").asText();
            assertTrue(store.conversation("tenant:manager","benefits",b.path("id").asText()).path("messages").isEmpty());
            assertThrows(SecurityException.class,()->store.conversation("tenant:other","benefits",selected));
            assertTrue(store.conversations("tenant:other","benefits").isEmpty());
            assertThrows(IllegalArgumentException.class,()->store.saveConversation("tenant:manager","benefits",a));
        }
        try(var store=new CreditStateStore(directory)){
            assertEquals(2,store.conversations("tenant:manager","benefits").size());
            assertEquals("只在对话A",store.conversation("tenant:manager","benefits",selected).path("messages").get(0).path("text").asText());
        }
    }
    @Test void conversationHasOnlyOneActiveOperationAndPersistsParentChain() throws Exception {
        var first=turn("session-serial","首次询问");
        var next=new CreditContracts.Turn("operation-2",first.sessionRef(),first.scene(),"补充需求",first.binding(),Instant.now().plusSeconds(20));
        try(var store=new CreditStateStore(directory)){
            store.begin(first);assertThrows(IllegalArgumentException.class,()->store.begin(next));
            store.finish(first,"complete",CreditContracts.JSON.createObjectNode());store.begin(next);
            assertEquals("operation-1",store.read(next).path("parent_operation_key").asText());
        }
    }
    @Test void aClosedCoordinatorCannotWriteAfterAnotherOneTakesOver() throws Exception {
        var first=new CreditStateStore(directory);first.close();
        try(var second=new CreditStateStore(directory)){
            assertThrows(IOException.class,()->first.begin(turn("session-1","检查")));
            assertNull(second.begin(turn("session-1","检查")));
        }
    }
}
