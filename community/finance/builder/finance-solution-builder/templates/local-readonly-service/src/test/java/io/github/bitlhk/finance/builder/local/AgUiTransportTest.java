package io.github.bitlhk.finance.builder.local;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgUiTransportTest {
    @Test public void chunksPreserveTheAuthorizedAnswerWithoutEmptyDeltas() {
        var chunks = AgUiTransport.textChunks("材料已收齐，进入人工复核。", 8);
        assertEquals("材料已收齐，进入人工复核。", String.join("", chunks));
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().noneMatch(String::isBlank));
    }

    @Test public void reviewTeamContainsOnlyInstalledAuditSpecialists() {
        var team = AgUiTransport.reviewTeam(Set.of("smartaudit.personal.borrower-qualification.query"), List.of());
        assertEquals("case-orchestrator", team.getFirst().get("id"));
        assertEquals("READY", team.get(1).get("status"));
        assertFalse(team.stream().anyMatch(member -> "enterprise-financial-report".equals(member.get("id"))));
    }
}
