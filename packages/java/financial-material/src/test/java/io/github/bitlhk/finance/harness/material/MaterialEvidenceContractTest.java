package io.github.bitlhk.finance.harness.material;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import static io.github.bitlhk.finance.harness.material.MaterialExtractionV1.*;

class MaterialEvidenceContractTest {
    final String hash="sha256:"+"a".repeat(64);
    Document doc(){return new Document("financial-material-document.v1","doc-1",1,hash,"application/pdf",200,2,"owner","user","customer","task","session");}
    Result result(){return new Result("financial-material-result.v1","run-1","req-1",doc(),Mode.PDF_TABLES,Status.PARTIAL,
            List.of(new Field("f1",JsonNodeFactory.instance.nullNode(),new Location("PAGE",0,1,1,null,null,List.of()))),
            JsonNodeFactory.instance.arrayNode(),List.of(new Unit(0,"OBSERVED",null),new Unit(1,"UNCONFIRMED","EMPTY_UNCONFIRMED")),
            new Source(REVISION,hash,"extract_tables_from_pdf"),"raw-1",Instant.now(),0,List.of("EMPTY_UNCONFIRMED"));}
    @Test void preservesMissingValueAndSourcePageBase(){
        var r=result();assertTrue(r.fields().getFirst().originalValue().isNull());assertEquals(1,r.fields().getFirst().location().sourcePageBase());
        assertEquals(Status.PARTIAL,r.status());
    }
    @Test void invalidLocationsCannotProduceHighlight(){
        assertThrows(IllegalArgumentException.class,()->new Location("REGION",0,0,0,"PERMILLE","TOP_LEFT",List.of(900.,0.,100.,50.)));
        assertThrows(IllegalArgumentException.class,()->new Location("REGION",0,0,0,null,null,List.of()));
        assertThrows(IllegalArgumentException.class,()->new Location("PAGE",-1,0,0,null,null,List.of()));
    }
    @Test void mapperRejectsCrossTaskAndPreservesValidResult() throws Exception {
        byte[] raw=HarnessJson.mapper().writeValueAsBytes(result());
        var good=new MaterialEvidenceMapper.Expected("req-1","user","customer","session","task","doc-1",1,hash,Mode.PDF_TABLES,hash);
        assertEquals("doc-1",MaterialEvidenceMapper.map(raw,good).document().documentRef());
        var bad=new MaterialEvidenceMapper.Expected("req-1","user","customer","session","another-task","doc-1",1,hash,Mode.PDF_TABLES,hash);
        assertThrows(IllegalArgumentException.class,()->MaterialEvidenceMapper.map(raw,bad));
    }
    @Test void correctionsRequireNextRevisionAndKeepOriginal(){
        var original=JsonNodeFactory.instance.textNode("75");
        var corrected=new Correction("financial-material-correction.v1","corr-1","run-1","doc-1",1,"f1",original,JsonNodeFactory.instance.textNode("70"),"核对原文","user",Instant.now(),0,1);
        assertEquals("75",corrected.originalValue().asText());assertEquals("70",corrected.newValue().asText());
        assertThrows(IllegalArgumentException.class,()->new Correction("financial-material-correction.v1","c","r","d",1,"f",original,original,"reason","u",Instant.now(),1,1));
    }
    @Test void requiredNullableEvidenceSurvivesNonNullHostMapper() throws Exception {
        var json=HarnessJson.mapper().readTree(HarnessJson.mapper().writeValueAsBytes(result()));
        assertTrue(json.path("units").get(0).has("code"));
        assertTrue(json.path("units").get(0).get("code").isNull());
        var field=HarnessJson.mapper().readTree(HarnessJson.mapper().writeValueAsBytes(new Field("missing",null,null)));
        assertTrue(field.has("originalValue")&&field.get("originalValue").isNull());
        assertTrue(field.has("location")&&field.get("location").isNull());
    }
    @Test void correctionCanKeepExplicitMissingOriginalValue() throws Exception {
        var corrected=new Correction("financial-material-correction.v1","c","r","d",1,"f",null,
                JsonNodeFactory.instance.textNode("70"),"原文复核","u",Instant.now(),0,1);
        var json=HarnessJson.mapper().readTree(HarnessJson.mapper().writeValueAsBytes(corrected));
        assertTrue(json.has("originalValue")&&json.get("originalValue").isNull());
    }
}
