package io.github.bitlhk.finance.harness.integrations.customermanager;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;

class OpenJiuwenReportGeneratorTest {
    @Test @org.junit.jupiter.api.Timeout(30)
    void actualSdkReceivesDigestBoundMethodReferencesAndStreamsBusinessSections(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        String external=System.getProperty("previsit.methodFixture");
        var file=external==null?directory.resolve("SKILL.md"):java.nio.file.Path.of(external);
        if(external==null)java.nio.file.Files.writeString(file,"# Synthetic method\nReference text: DELIVERY-REFERENCE-MARKER\n");
        byte[] bytes=java.nio.file.Files.readAllBytes(file);
        String digest="sha256:"+java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        var method=io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod.load(
                "synthetic-delivery-method","1.0.0",file,digest,"sha256:synthetic-lock");
        String response="""
                {"communicationFocus":[{"text":"### 拜访目标\\n确认合成客户资金用途。","factRefs":["/customer/displayName"]}],
                "preparationAdvice":[{"text":"### 面谈问题\\n资金后续如何安排？","factRefs":["/customer/displayName"]}],
                "pendingItems":["实际资金去向待确认"],"reviewChecklist":["核对业务日期；未写入CRM"]}
                """;
        try(var fixture=new OpenJiuwenIntentProposerTest.Fixture(response)) {
            fixture.chunkCharacters=13;
            var generator=new OpenJiuwenReportGenerator("OpenAI","synthetic-only",
                    "http://127.0.0.1:"+fixture.server.getAddress().getPort()+"/v1","synthetic");
            var deltas=new ArrayList<String>();
            var report=generator.generateStreaming(method,"准备面谈",List.of("/customer/displayName"),
                    HarnessJson.mapper().readTree("{\"customer\":{\"displayName\":\"合成客户\"}}"),d->deltas.add(d.text()));
            assertEquals(4,deltas.size());
            assertTrue(report.at("/preparationAdvice/0/text").asText().contains("面谈问题"));
            assertEquals(1,fixture.requests.size());
            var request=fixture.requests.getFirst();
            assertEquals(3200,request.path("max_tokens").asInt(),request.toString());
            assertTrue(request.path("tools").isMissingNode()||request.path("tools").isEmpty());
            var payload=HarnessJson.mapper().readTree(request.path("messages").get(1).path("content").asText());
            assertEquals(method.content(),payload.at("/installedMethod/content").asText());
            assertEquals(digest,payload.at("/installedMethod/digest").asText());
            String installed=payload.at("/installedMethod/content").asText();
            assertTrue(installed.contains(external==null?"DELIVERY-REFERENCE-MARKER":"SOURCE-MARKER-ONE"));
            if(external!=null)assertTrue(installed.contains("Source digest: "));
        }
        assertThrows(SecurityException.class,()->io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod.load(
                "synthetic-delivery-method","1.0.0",file,"sha256:tampered","sha256:synthetic-lock"));
    }
    @Test void sopHeadingsAndConcreteQuestionsFitTheExistingWireContract() throws Exception {
        assertTrue(OpenJiuwenReportGenerator.REPORT_MAX_TOKENS >= 3000);
        assertTrue(OpenJiuwenReportGenerator.DELIVERY_INSTRUCTION.contains("installedMethod"));
        assertTrue(OpenJiuwenReportGenerator.DELIVERY_INSTRUCTION.contains("每个问题"));
        assertTrue(OpenJiuwenReportGenerator.DELIVERY_INSTRUCTION.contains("同一section可以连续输出多行"));
        assertTrue(OpenJiuwenReportGenerator.DELIVERY_INSTRUCTION.contains("不要自行相除"));
        assertTrue(OpenJiuwenReportGenerator.DELIVERY_INSTRUCTION.contains("不能改写成当前现金占比"));
        var deltas=new ArrayList<String>();
        var parser=new OpenJiuwenReportGenerator.ReportLines(delta->deltas.add(delta.text()));
        var line=HarnessJson.mapper().createObjectNode();
        line.put("section","preparationAdvice");
        line.put("text","### 面谈问题\n到期资金目前在哪里？\n### 下一步\n确认资金去向后再讨论安排，未写入CRM。");
        line.putArray("factRefs").add("/temporalAssessment/maturities/0/warning");
        parser.accept(line.toString()+"\n");
        assertEquals(line.path("text"),parser.finish().at("/preparationAdvice/0/text"));
        assertEquals(1,deltas.size());
    }
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
        assertEquals("/factState/items/0/value/availableCash",catalog.at("/0").asText());
        assertEquals("/factState/items/0/value/customerId",catalog.at("/1").asText());
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
