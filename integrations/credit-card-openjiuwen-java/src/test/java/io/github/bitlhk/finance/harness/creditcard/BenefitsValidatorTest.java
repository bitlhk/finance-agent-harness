package io.github.bitlhk.finance.harness.creditcard;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenefitsValidatorTest {
    static ObjectNode view(){
        var view=CreditModelToolsTest.snapshot();view.put("dataset_version","a".repeat(64));
        ((ObjectNode)view.path("customer")).put("tier",4);
        var c=(ObjectNode)view.path("case");c.put("observed_at","2026-06-12T10:00:00Z");
        c.putArray("visible_sources").add("ledger").add("benefits");view.putArray("facts");
        var benefits=view.putArray("benefits");
        for(int i=1;i<=4;i++)benefits.addObject().put("benefit_id","benefit-"+i).put("title",i==1?"健康轻食沙拉":"合成权益"+i)
                .put("known_at","2026-06-01T00:00:00Z").put("valid_from","2026-06-01T00:00:00Z")
                .put("valid_until",i==3?"2026-06-10T00:00:00Z":"2026-07-01T00:00:00Z")
                .put("minimum_tier",i==4?6:4).put("remaining",2).put("synthetic",true);
        return view;
    }
    @Test void eligibilityExclusionsAndProvenanceUseVerifiedSource(){
        var context=BenefitsValidator.context(view(),"推荐",Set.of("benefit-1"),3);
        assertEquals(1,context.path("candidates").size());assertEquals(3,context.path("exclusions").size());
        var raw=CreditContracts.JSON.createObjectNode();raw.putArray("items").addObject().put("benefit_id","benefit-2").put("reason","匹配目标");
        var result=BenefitsValidator.ranking(context,raw);
        assertEquals("benefit-2",result.path("items").get(0).path("reference").path("record_id").asText());
        assertEquals(CreditContracts.hash(view()),result.path("items").get(0).path("reference").path("snapshot_digest").asText());
    }
    @Test void excludedUnknownAndDuplicateIdsAreRejected(){
        var context=BenefitsValidator.context(view(),"推荐",Set.of("benefit-1"),3);
        for(String id:List.of("benefit-1","benefit-unknown","benefit-3","benefit-4")){
            var raw=CreditContracts.JSON.createObjectNode();raw.putArray("items").addObject().put("benefit_id",id).put("reason","模型声称适合");
            assertThrows(IllegalArgumentException.class,()->BenefitsValidator.ranking(context,raw));
        }
        var raw=CreditContracts.JSON.createObjectNode();var items=raw.putArray("items");
        for(int i=0;i<2;i++)items.addObject().put("benefit_id","benefit-2").put("reason","重复");
        assertThrows(IllegalArgumentException.class,()->BenefitsValidator.ranking(context,raw));
    }
    @Test void benefitsSourceIsRequired(){
        var view=view();((ObjectNode)view.path("case")).putArray("visible_sources").add("ledger");
        assertThrows(SecurityException.class,()->BenefitsValidator.context(view,"推荐",Set.of(),3));
    }
}
