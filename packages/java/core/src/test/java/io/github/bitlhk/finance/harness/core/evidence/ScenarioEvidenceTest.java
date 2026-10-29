package io.github.bitlhk.finance.harness.core.evidence;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScenarioEvidenceTest {
    @TempDir Path root;

    private ScenarioEvidence.Run run() {
        return new ScenarioEvidence(root, Map::of).begin("request", "owner", "synthetic-object",
                "purpose", "capability", Map.of("period", "2026-09"), Map.of("revision", 1), "SYNTHETIC_MAPPING");
    }

    @Test void canonicalAndImmutableBinding() {
        var left=new LinkedHashMap<String,Object>(); left.put("b",2);left.put("a",Map.of("x",1));
        assertEquals(ScenarioEvidence.digest(left),ScenarioEvidence.digest(Map.of("a",Map.of("x",1),"b",2)));
        var proof=new ScenarioEvidence(root,Map::of).begin("r","o","s","p","c",left,Map.of(),"LOCAL");
        String digest=proof.binding().path("bindingDigest").asText();left.put("b",3);
        proof.binding().put("parametersDigest","mutated");
        assertEquals(digest,proof.binding().path("bindingDigest").asText());
        assertNotEquals(ScenarioEvidence.digest(left),proof.binding().path("parametersDigest").asText());
    }

    @Test void managedOwnerMismatchIsRejected() {
        var evidence=new ScenarioEvidence(root,()->Map.of("ownerRef","different"));
        assertThrows(SecurityException.class,()->evidence.begin("r","owner","s","p","c",Map.of(),Map.of(),"LOCAL"));
    }

    @Test void bootstrapHistoryKeepsHostIdentityWithoutInventingRequestCorrelation() {
        var evidence=new ScenarioEvidence(root,()->Map.of("ownerRef","owner","hostRequestId","NOT_OBSERVED",
                "buildId","build","instanceId","instance","serviceLockDigest","sha256:test"));
        var history=evidence.begin("read","owner","subject","history","scenario.report.read",Map.of(),Map.of(),"LOCAL");
        assertEquals("NOT_OBSERVED",history.binding().path("requestCorrelationStatus").asText());
        assertThrows(SecurityException.class,()->evidence.begin("query","owner","subject","purpose","business.query",Map.of(),Map.of(),"LOCAL"));
    }

    @Test void failedEntryDoesNotInvokeProvider() throws Exception {
        Files.writeString(root.resolve("decision-evidence"),"blocked");
        var calls=new AtomicInteger();var proof=run();
        assertThrows(ScenarioEvidence.Unavailable.class,()->{proof.check("ENTRY",()->{},null);calls.incrementAndGet();});
        assertEquals(0,calls.get());
    }

    @Test void returnedButUnrecordedResultIsNotReleased() throws Exception {
        var proof=run();proof.check("ENTRY",()->{},null);proof.invoked();proof.returned();
        Path directory=root.resolve("decision-evidence");
        Files.move(directory,root.resolve("retained-entry"));Files.writeString(directory,"blocked");
        var releases=new AtomicInteger();
        assertThrows(ScenarioEvidence.Unavailable.class,()->{proof.check("PAGE_RELEASE",()->{},Map.of("synthetic",42));releases.incrementAndGet();});
        assertEquals(0,releases.get());
        assertTrue(Files.isDirectory(root.resolve("retained-entry")));
    }

    @Test void revocationRecordsDenialWithoutResultBody() throws Exception {
        var proof=run();proof.check("ENTRY",()->{},null);proof.invoked();proof.returned();
        assertThrows(SecurityException.class,()->proof.check("RETURN",()->{throw new SecurityException("private-detail");},Map.of("secret","never-record")));
        try(var paths=Files.list(root.resolve("decision-evidence"))) {
            var events=paths.sorted().map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).toList();
            assertEquals(2,events.size());
            String denied=events.stream().filter(e->e.contains("DENY")).findFirst().orElseThrow();
            assertFalse(denied.contains("private-detail"));assertFalse(denied.contains("never-record"));
            assertEquals("RETURNED",HarnessJson.mapper().readTree(denied).path("providerDispatch").asText());
        }
    }
}
