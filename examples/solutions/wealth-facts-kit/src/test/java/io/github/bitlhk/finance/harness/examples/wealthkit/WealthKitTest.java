package io.github.bitlhk.finance.harness.examples.wealthkit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.wealth.WealthJson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.examples.wealthkit.WealthKitApplication.*;

class WealthKitTest {
    @TempDir Path temp;
    @Test void nativeAgentLoopUsesInstalledSkillToolAndProjectsAuthorizedFacts() throws Exception {
        var report = runCase(Binding.BANK_HTTP, Scenario.NORMAL);
        assertEquals(2, report.get("modelRequestCount"));
        assertEquals(1, report.get("modelCompletionCount"));
        assertEquals(2, report.get("sourceReadCount"));
        assertEquals(0, report.get("sourceWriteCount"));
        var tool = tool(report);
        assertEquals("COMPLETED", tool.path("status").asText(), tool.toString());
        assertFalse(tool.path("evidence").isEmpty());
        assertEquals("1000000.12345678", tool.at("/data/facts/assets/amounts/0/money/value").asText());
        assertEquals("NOT_EVALUATED", tool.at("/data/description/suitability").asText());
        var lock = WealthJson.mapper().readTree(temp.resolve("BANK_HTTP-NORMAL/solution-lock.json").toFile());
        assertTrue(lock.toString().contains("NonAiBankingProvider"), "Lock must include the wrapped source artifact");
        assertTrue(lock.toString().contains("WealthFactsProvider"));
        assertTrue(lock.toString().contains("BoundFinanceProvider"));
    }
    @Test void controlledSameProfileProviderSwapChangesOnlyBinding() throws Exception {
        var bank = tool(runCase(Binding.BANK_HTTP, Scenario.NORMAL));
        var snapshot = tool(runCase(Binding.SNAPSHOT, Scenario.NORMAL));
        assertEquals(bank.at("/data/description"), snapshot.at("/data/description"));
        assertEquals(bank.at("/data/facts/assets"), snapshot.at("/data/facts/assets"));
        assertEquals(bank.at("/data/facts/risk"), snapshot.at("/data/facts/risk"));
    }
    @Test void employeeUsesSameConsumerWithoutExposingPrivateFields() throws Exception {
        var report = runCase(Binding.EA_HTTP, Scenario.NORMAL);
        assertEquals("COMPLETED", tool(report).path("status").asText(), tool(report).toString());
        assertEquals(1, report.get("sourceReadCount"));
        assertEquals(false, report.get("privateDataObservedByModel"));
        assertFalse(report.toString().contains("PRIVATE_"));
        assertEquals("COMPLETE_FOR_DESCRIPTION", tool(report).at("/data/description/taskReadiness").asText());
    }
    @TestFactory List<DynamicTest> denialAndMalformedInputsAreBlockedOnTheNativePath() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Binding binding : List.of(Binding.BANK_HTTP, Binding.EA_HTTP))
            for (Scenario scenario : List.of(Scenario.DENIED, Scenario.EXPIRED_GRANT, Scenario.WRONG_SUBJECT, Scenario.BAD_RESPONSE, Scenario.MODEL_SELECTOR))
                tests.add(DynamicTest.dynamicTest(binding + "/" + scenario, () -> {
                    var report = runCase(binding, scenario); var tool = tool(report);
                    assertNotEquals("COMPLETED", tool.path("status").asText(), tool.toString());
                    assertTrue(tool.at("/data/result").isEmpty(), tool.toString());
                    assertTrue(tool.at("/data/facts").isMissingNode(), tool.toString());
                    assertEquals(0, report.get("sourceWriteCount"));
                    if (scenario == Scenario.DENIED || scenario == Scenario.EXPIRED_GRANT || scenario == Scenario.MODEL_SELECTOR)
                        assertEquals(0, report.get("sourceReadCount"));
                    if (scenario == Scenario.WRONG_SUBJECT && binding == Binding.BANK_HTTP)
                        assertEquals(0, report.get("sourceReadCount"));
                }));
        return tests;
    }
    @Test void zeroAndMissingMetadataAreBusinessStatesNotSuccessfulSuitability() throws Exception {
        var zero = tool(runCase(Binding.EA_HTTP, Scenario.ZERO));
        assertEquals("0", zero.at("/data/facts/assets/amounts/0/money/value").asText());
        var missing = tool(runCase(Binding.EA_HTTP, Scenario.MISSING_METADATA));
        assertEquals("COMPLETED", missing.path("status").asText());
        assertEquals("PARTIAL_FOR_DESCRIPTION", missing.at("/data/description/taskReadiness").asText());
        assertTrue(missing.at("/data/facts/assets/amounts").isEmpty());
        var noRisk = tool(runCase(Binding.BANK_HTTP, Scenario.MISSING_RISK));
        assertEquals("MISSING", noRisk.at("/data/facts/risk/availability").asText());
    }
    @Test void incompatibleRolesAndImplicitLiveModeAreRejectedAtConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new Configuration("CONTROLLED", FinanceContextV1.PrincipalType.CUSTOMER,
                Binding.EA_HTTP, Scenario.NORMAL));
        assertThrows(IllegalArgumentException.class, () -> new Configuration("LIVE", FinanceContextV1.PrincipalType.CUSTOMER,
                Binding.BANK_HTTP, Scenario.NORMAL));
    }
    private Map<String,Object> runCase(Binding binding, Scenario scenario) throws Exception {
        return WealthKitApplication.run(root(), new Configuration("CONTROLLED",
                binding == Binding.EA_HTTP ? FinanceContextV1.PrincipalType.EMPLOYEE : FinanceContextV1.PrincipalType.CUSTOMER,
                binding, scenario), temp.resolve(binding + "-" + scenario));
    }
    private JsonNode tool(Map<String,Object> report) { return WealthJson.mapper().valueToTree(report.get("toolResults")).path(0); }
}
