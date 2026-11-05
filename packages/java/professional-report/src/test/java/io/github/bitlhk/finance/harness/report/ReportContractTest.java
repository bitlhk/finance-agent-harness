package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportContractTest {
    JsonNode fixture() throws Exception {
        Path path=Path.of("").toAbsolutePath();
        while(path!=null && !Files.isRegularFile(path.resolve("conformance/professional-report-readonly/schema-cases.v1.json")))path=path.getParent();
        assertNotNull(path,"Repository conformance fixture required");
        return ReportMapper.parse(Files.readAllBytes(path.resolve("conformance/professional-report-readonly/schema-cases.v1.json")));
    }
    ProfessionalReportV1 original() throws Exception {
        var f=fixture();var n=f.path("valid");
        return ReportMapper.mapSource(ReportMapper.bytes(n),new ReportMapper.Expected(n.path("reportId").asText(),n.path("runId").asText(),n.path("scope"),n.path("binding")),f.path("originalDocument").asText().getBytes(StandardCharsets.UTF_8));
    }
    @Test void exactOriginalBytesAndSemanticValuesAreVerifiedAcrossSerializers() throws Exception {
        var report=original();assertEquals(Set.of("source1"),report.verifiedSources());assertEquals("run1",report.runId());
        assertEquals("1.0.0",report.document().path("binding").path("methodVersion").asText());
        var copied=(ObjectNode)report.document();copied.put("reportId","changed");assertEquals("source1",report.reportId());
        assertEquals("BLOCKED",report.document().path("qualification").path("professional").asText());
        assertTrue(report.document().path("metrics").path("inputTokens").isNull());
    }
    @Test void wholeValuedFloatsAndExactDecimalsSurviveProviderMapRoundtrip() throws Exception {
        var f=fixture();ObjectNode n=f.path("valid").deepCopy();
        String original=f.path("originalDocument").asText();
        original=original.substring(0,original.length()-1)+",\"wholeFloat\":1.0,\"zeroFloat\":0.0,\"exactDecimal\":0.12345678901234567890123456789}";
        byte[] raw=original.getBytes(StandardCharsets.UTF_8);var document=ReportMapper.parse(raw);String hash=ReportMapper.sha(raw);
        for(JsonNode e:n.path("evidence")){((ObjectNode)e).put("sourceDigest",hash);((ObjectNode)e).set("value",document.at(e.path("pointer").asText()));}
        for(JsonNode a:n.path("artifacts")){((ObjectNode)a).put("sha256",hash);((ObjectNode)a).put("bytes",raw.length);}
        var json=ReportMapper.json();
        var map=json.convertValue(n,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
        JsonNode roundtrip=json.valueToTree(map);
        var expected=new ReportMapper.Expected(n.path("reportId").asText(),n.path("runId").asText(),n.path("scope"),n.path("binding"));
        var verified=ReportMapper.mapSource(ReportMapper.bytes(roundtrip),expected,raw);
        var values=verified.document().path("evidence").get(0).path("value");
        assertTrue(values.path("wholeFloat").isFloatingPointNumber());assertTrue(values.path("zeroFloat").isFloatingPointNumber());
        assertEquals(new java.math.BigDecimal("0.12345678901234567890123456789"),values.path("exactDecimal").decimalValue());
        ObjectNode changed=(ObjectNode)roundtrip.deepCopy();
        ((ObjectNode)changed.path("evidence").get(0).path("value")).put("wholeFloat",new java.math.BigDecimal("1.0000000000000001"));
        assertThrows(IllegalArgumentException.class,()->ReportMapper.mapSource(ReportMapper.bytes(changed),expected,raw));
    }
    @Test void changedSourceBytesAndCurrentIdentityAreRejected() throws Exception {
        var f=fixture();var n=f.path("valid");var e=new ReportMapper.Expected("source1","run1",n.path("scope"),n.path("binding"));
        byte[] changed=(f.path("originalDocument").asText()+" ").getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,()->ReportMapper.mapSource(ReportMapper.bytes(n),e,changed));
        var wrong=(ObjectNode)n.path("scope").deepCopy();wrong.put("taskRef","another");
        assertThrows(IllegalArgumentException.class,()->ReportMapper.mapSource(ReportMapper.bytes(n),new ReportMapper.Expected("source1","run1",wrong,n.path("binding")),f.path("originalDocument").asText().getBytes(StandardCharsets.UTF_8)));
    }
    @Test void unsupportedFieldsInvalidStatesAndForgedReferencesFailClosed() throws Exception {
        var n=fixture().path("valid");
        for(JsonNode patch:fixture().path("negativePatches")) {
            ObjectNode value=n.deepCopy();value.setAll((ObjectNode)patch.path("patch"));assertThrows(IllegalArgumentException.class,()->ReportMapper.validate(value));
        }
        ObjectNode forged=n.deepCopy();((ObjectNode)forged.path("evidence").get(1)).put("value","invented");assertThrows(IllegalArgumentException.class,()->ReportMapper.validate(forged));
        ObjectNode changedArtifact=n.deepCopy();((ObjectNode)changedArtifact.path("artifacts").get(0)).put("sourceResultId","other");
        assertThrows(IllegalArgumentException.class,()->ReportMapper.validate(changedArtifact));
    }
    @Test void compositionCannotCreateNewEvidenceFromNarrative() throws Exception {
        var report=original();var n=report.document();
        assertEquals(report.verifiedSources(),ReportMapper.compose(n,List.of(report)).verifiedSources());
        assertThrows(IllegalArgumentException.class,()->ReportMapper.compose(n,List.of()));
        assertThrows(IllegalArgumentException.class,()->ReportMapper.retained(n,Set.of()));
    }
    @Test void duplicateJsonFieldsAndInvalidPointersAreRejected() {
        assertThrows(IllegalArgumentException.class,()->ReportMapper.parse("{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class,()->new ReportEvidenceV1("e","r","s","/bad~x","sha256:"+"a".repeat(64),null,null,ReportMapper.json().getNodeFactory().nullNode()));
    }
}
