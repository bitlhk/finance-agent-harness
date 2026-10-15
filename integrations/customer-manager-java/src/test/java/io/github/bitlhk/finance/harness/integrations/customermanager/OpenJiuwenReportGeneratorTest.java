package io.github.bitlhk.finance.harness.integrations.customermanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

class OpenJiuwenReportGeneratorTest {
    @Test void disablesReasoningOnlyForDeepSeekReportModels() {
        var deepSeek=new com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig();
        OpenJiuwenReportGenerator.configureModelRequest(deepSeek,"deepseek-v4-flash");
        assertEquals(java.util.Map.of("type","disabled"),deepSeek.getExtraFields().get("thinking"));

        var other=new com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig();
        OpenJiuwenReportGenerator.configureModelRequest(other,"glm-5.3-flash");
        assertFalse(other.getExtraFields().containsKey("thinking"));
    }

    @Test void projectsStableLeafReferenceCatalogWithoutInventingParentPaths() throws Exception {
        var facts=HarnessJson.mapper().readTree("""
                {"factState":{"items":[{"value":{"availableCash":360000,"customerId":"CUST-1"}}]}}
                """);
        var catalog=OpenJiuwenReportGenerator.referenceCatalog(List.of(
                "/factState/items/0/value/availableCash",
                "/factState/items/0/value/customerId",
                "/factState/items/0/value",
                "/missing"),facts);
        assertEquals(2,catalog.size());
        assertEquals("/factState/items/0/value/availableCash",catalog.at("/0/factRef").asText());
        assertEquals(360000,catalog.at("/0/value").asInt());
        assertFalse(catalog.toString().contains("\"/factState/items/0/value\""));
        assertTrue(OpenJiuwenReportGenerator.FACT_REFERENCE_INSTRUCTION.contains("不得引用父对象"));
    }

    @Test void streamsAndAcceptsCanonicalReportReturnedByProvider() throws Exception {
        var deltas=new ArrayList<String>();
        var parser=new OpenJiuwenReportGenerator.ReportLines(delta->deltas.add(delta.section()+":"+delta.text()));
        String value="""
                {
                  "communicationFocus":[{"text":"核对服务目标","factRefs":["/customer/displayName"]}],
                  "preparationAdvice":[{"text":"准备回访提纲","factRefs":["/customer/customerStatus"]}],
                  "pendingItems":["核实具体需求"],
                  "reviewChecklist":["复核事实来源"]
                }
                """;
        for(int i=0;i<value.length();i+=11)parser.accept(value.substring(i,Math.min(value.length(),i+11)));
        var result=parser.finish();
        assertEquals(4,deltas.size());
        assertEquals("核对服务目标",result.at("/communicationFocus/0/text").asText());
        assertEquals("核实具体需求",result.at("/pendingItems/0").asText());
    }

    @Test void retainsNdjsonCompatibility() throws Exception {
        var parser=new OpenJiuwenReportGenerator.ReportLines(delta->{});
        parser.accept("{\"section\":\"communicationFocus\",\"text\":\"重点\",\"factRefs\":[\"/customer/displayName\"]}\n");
        parser.accept("{\"section\":\"preparationAdvice\",\"text\":\"建议\",\"factRefs\":[\"/customer/customerStatus\"]}\n");
        parser.accept("{\"section\":\"pendingItems\",\"text\":\"待核实\",\"factRefs\":[]}\n");
        parser.accept("{\"section\":\"reviewChecklist\",\"text\":\"复核\",\"factRefs\":[]}\n");
        assertEquals("重点",parser.finish().at("/communicationFocus/0/text").asText());
    }

    @Test void rejectsUnknownCanonicalShape() {
        var parser=new OpenJiuwenReportGenerator.ReportLines(delta->{});
        assertThrows(Exception.class,()->{
            parser.accept("{\"communicationFocus\":[],\"preparationAdvice\":[],\"pendingItems\":[],\"reviewChecklist\":[],\"extra\":true}");
            parser.finish();
        });
    }
}
