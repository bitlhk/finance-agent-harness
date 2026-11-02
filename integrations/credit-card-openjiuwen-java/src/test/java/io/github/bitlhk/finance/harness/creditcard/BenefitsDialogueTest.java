package io.github.bitlhk.finance.harness.creditcard;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BenefitsDialogueTest {
    static ObjectNode decision(){
        var result=CreditContracts.JSON.createObjectNode().put("action","recommend").put("goal","保留其他权益并替换沙拉");
        result.putArray("questions");result.putArray("changes");return result;
    }
    @Test void exclusionsPersistAndExplicitReinstatementIsGrounded() throws Exception {
        var d=decision();d.withArray("changes").addObject().put("benefit_id","benefit-1").put("action","exclude").put("quote","不要沙拉");
        var dialogue=new BenefitsDialogue(input->d.deepCopy());
        var first=dialogue.resolve("不要沙拉",List.of(),CreditContracts.JSON.createObjectNode(),BenefitsValidatorTest.view());
        assertEquals(List.of("benefit-1"),CreditContracts.JSON.convertValue(first.path("excluded_benefit_ids"),List.class));
        var unchanged=new BenefitsDialogue(input->decision()).resolve("再换一个",List.of(),first,BenefitsValidatorTest.view());
        assertEquals(first.path("excluded_benefit_ids"),unchanged.path("excluded_benefit_ids"));
        var reinstate=decision();reinstate.withArray("changes").addObject().put("benefit_id","benefit-1").put("action","reinstate").put("quote","沙拉也可以");
        var restored=new BenefitsDialogue(input->reinstate).resolve("沙拉也可以",List.of(),first,BenefitsValidatorTest.view());
        assertTrue(restored.path("excluded_benefit_ids").isEmpty());
    }
    @Test void hallucinatedPreferenceCannotClearHistory(){
        var prior=CreditContracts.JSON.createObjectNode();prior.putArray("excluded_benefit_ids").add("benefit-1");
        var d=decision();d.withArray("changes").addObject().put("benefit_id","benefit-1").put("action","reinstate").put("quote","沙拉也可以");
        assertThrows(IllegalArgumentException.class,()->new BenefitsDialogue(input->d).resolve("再推荐",List.of(),prior,BenefitsValidatorTest.view()));
    }
    @Test void clarificationMustAskSomethingSpecific(){
        var d=decision();d.put("action","clarify");
        assertThrows(IllegalArgumentException.class,()->new BenefitsDialogue(input->d).resolve("推荐",List.of(),CreditContracts.JSON.createObjectNode(),BenefitsValidatorTest.view()));
    }
}
