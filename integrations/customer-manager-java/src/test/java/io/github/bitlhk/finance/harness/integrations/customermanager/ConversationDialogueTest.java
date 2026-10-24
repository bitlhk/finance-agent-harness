package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationDialogueTest {
    private final Instant now=Instant.parse("2026-10-21T00:00:00Z");
    private final ConversationDialogue.Binding binding=new ConversationDialogue.Binding("chat-a","a","","scope-a");
    @Test void boundedTurnsAndExpiry() {
        var memory=new ConversationDialogue();
        memory.read(binding,now);
        for(int i=0;i<6;i++) memory.append(binding,"question-"+i,"answer-"+i,now);
        var turns=memory.read(binding,now.plusSeconds(1));
        assertEquals(3,turns.size());assertEquals("question-3",turns.getFirst().get("userText"));
        assertTrue(memory.read(binding,now.plusSeconds(300)).isEmpty());
    }
    @Test void changedObjectContextReportOrAuthorityCannotReuseContext() {
        for(var next:java.util.List.of(new ConversationDialogue.Binding("chat-b","a","","scope-a"),
                new ConversationDialogue.Binding("chat-a","b","","scope-a"),
                new ConversationDialogue.Binding("chat-a","a","report-v4","scope-a"),
                new ConversationDialogue.Binding("chat-a","a","","scope-b"))) {
            var memory=new ConversationDialogue();memory.read(binding,now);memory.append(binding,"old","old answer",now);
            assertTrue(memory.read(next,now).isEmpty());
            memory.append(binding,"late","late answer",now);
            assertTrue(memory.read(next,now).isEmpty());
        }
    }
    @Test void oversizedTextIsNotPartiallyRememberedOrAllowedToEraseState() {
        var memory=new ConversationDialogue();memory.read(binding,now);
        memory.select("a");memory.listed(java.util.Set.of("a","b"));
        memory.append(binding,"question","x".repeat(2001),now);
        assertTrue(memory.read(binding,now.plusSeconds(1)).isEmpty());
        assertEquals("a",memory.object());assertTrue(memory.state().get("directoryPreviouslyReturned").equals(true));
    }
    @Test void selectedObjectCannotHideOtherPreviouslyDisclosedNamesFromRevalidation() {
        var memory=new ConversationDialogue();memory.read(binding,now);
        memory.listed(java.util.Set.of("a","b"));memory.select("a");
        assertEquals(java.util.Set.of("a","b"),memory.disclosedObjects());
        memory.returnedReport();assertEquals(1,memory.state().get("returnedReports"));
        assertNull(memory.object());assertEquals("NONE",memory.state().get("authority"));
        memory.clear();assertEquals(0,memory.state().get("returnedReports"));
        assertTrue(memory.disclosedObjects().isEmpty());
    }
}
