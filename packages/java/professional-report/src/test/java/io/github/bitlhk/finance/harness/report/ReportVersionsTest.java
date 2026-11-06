package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReportVersionsTest {
    @TempDir Path temporary;
    ProfessionalReportV1 original() throws Exception {
        Path path=Path.of("").toAbsolutePath();while(path!=null&&!Files.isRegularFile(path.resolve("conformance/professional-report-readonly/schema-cases.v1.json")))path=path.getParent();
        assertNotNull(path);var f=ReportMapper.parse(Files.readAllBytes(path.resolve("conformance/professional-report-readonly/schema-cases.v1.json")));var n=f.path("valid");
        return ReportMapper.mapSource(ReportMapper.bytes(n),new ReportMapper.Expected("source1","run1",n.path("scope"),n.path("binding")),f.path("originalDocument").asText().getBytes(StandardCharsets.UTF_8));
    }
    @Test void immutableHistoryAndHumanNotesSurviveStoreRestart() throws Exception {
        var original=original();String owner=original.scope().path("ownerRef").asText();var access=new ReportAccess(owner,s->s.equals(original.scope()));
        Clock clock=Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"),ZoneOffset.UTC);Path root=temporary.resolve("reports");var store=new ReportVersions(root,access,clock);
        store.save(owner,original,null);byte[] before=Files.readAllBytes(root.resolve("source1/v000001.json"));
        var annotated=store.note(owner,"source1",1,"人工说明，未修改原事实");assertEquals(2,annotated.revision());
        assertEquals(original.document().path("evidence"),annotated.document().path("evidence"));
        assertEquals(original.document().path("qualification"),annotated.document().path("qualification"));assertEquals(original.status(),annotated.status());
        assertArrayEquals(before,Files.readAllBytes(root.resolve("source1/v000001.json")));
        var resumed=new ReportVersions(root,access,clock);assertEquals(2,resumed.versions(owner,"source1").size());assertEquals(1,resumed.history(owner).size());
        assertEquals("AVAILABLE",resumed.lineage(owner,"source1",2).get("previousState"));
        assertThrows(IllegalStateException.class,()->resumed.note(owner,"source1",1,"stale"));
        assertThrows(IllegalStateException.class,()->resumed.save(owner,original,null));
    }
    @Test void currentRevocationAppliesToVersionsHistoryAndNotes() throws Exception {
        var original=original();String owner=original.scope().path("ownerRef").asText();var active=new AtomicBoolean(true);var store=new ReportVersions(temporary.resolve("reports"),new ReportAccess(owner,s->active.get()&&s.equals(original.scope())),Clock.systemUTC());
        store.save(owner,original,null);active.set(false);
        assertThrows(SecurityException.class,()->store.read(owner,"source1",1));assertThrows(SecurityException.class,()->store.note(owner,"source1",1,"note"));
        assertTrue(store.history(owner).isEmpty());assertThrows(SecurityException.class,()->store.history("other"));
    }
    @Test void changedSourceAndStorageCorruptionCannotBecomeEmptyOrSuccessfulHistory() throws Exception {
        var original=original();String owner=original.scope().path("ownerRef").asText();Path root=temporary.resolve("reports");var store=new ReportVersions(root,new ReportAccess(owner,s->s.equals(original.scope())),Clock.systemUTC());
        store.save(owner,original,null);Path record=root.resolve("source1/v000001.json");ObjectNode value=(ObjectNode)ReportMapper.parse(Files.readAllBytes(record));
        ((ObjectNode)value.path("report")).put("status","CANCELLED");Files.write(record,ReportMapper.bytes(value));
        assertThrows(SecurityException.class,()->store.read(owner,"source1",1));assertThrows(SecurityException.class,()->store.history(owner));
        assertThrows(IllegalArgumentException.class,()->store.read(owner,"../other",1));
    }
}
