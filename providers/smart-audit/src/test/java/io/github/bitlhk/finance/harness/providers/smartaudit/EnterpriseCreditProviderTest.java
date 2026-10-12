package io.github.bitlhk.finance.harness.providers.smartaudit;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider integration must preserve deterministic enterprise evidence/rules without model authority. */
class EnterpriseCreditProviderTest {

    @Test
    void eachFixedEnterpriseTaskReturnsDeterministicUnverifiedEvidenceWithoutCallingModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        SmartAuditModelProvider provider = new SmartAuditModelProvider(
                new SmartAuditModelProvider.Config(Path.of("enterprise-baseline-not-required.json"), "model-v1"),
                request -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("enterprise deterministic query must not call the model");
                });

        for (String capabilityId : List.of(
                SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_REPORT,
                SmartAuditCapabilityCatalog.ENTERPRISE_FINANCIAL_SUMMARY,
                SmartAuditCapabilityCatalog.ENTERPRISE_TRANSACTION_ANALYSIS,
                SmartAuditCapabilityCatalog.ENTERPRISE_TRANSACTION_REPORT,
                SmartAuditCapabilityCatalog.ENTERPRISE_DUE_DILIGENCE)) {
            SmartAuditCapabilityCatalog.TaskDefinition task = SmartAuditCapabilityCatalog.require(capabilityId);
            CapabilityResultV1 result = provider.query(request(task, task.materialCategories()));

            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), capabilityId);
            assertEquals(Boolean.TRUE, result.data().get("advisoryOnly"));
            assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
            assertEquals("不可判断", result.data().get("completeness"));
            List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.data().get("evidence");
            assertEquals(task.materialCategories().size(), evidence.size(), capabilityId);
            assertTrue(evidence.stream().allMatch(item -> "UNVERIFIED".equals(item.get("status"))), capabilityId);
            assertFalse(((List<?>) result.data().get("ruleOutcomes")).isEmpty(), capabilityId);
            assertEquals(List.of("VERIFY_EVIDENCE", "SUBMIT_HUMAN_REVIEW"), result.nextActions(), capabilityId);
        }
        assertEquals(0, modelCalls.get());
    }

    @Test
    void incompleteEnterpriseTaskNeedsSupplementAndNeverNeedsAModel() {
        AtomicInteger modelCalls = new AtomicInteger();
        SmartAuditModelProvider provider = new SmartAuditModelProvider(
                new SmartAuditModelProvider.Config(Path.of("enterprise-baseline-not-required.json"), "model-v1"),
                request -> {
                    modelCalls.incrementAndGet();
                    throw new AssertionError("enterprise incomplete query must not call the model");
                });
        SmartAuditCapabilityCatalog.TaskDefinition task =
                SmartAuditCapabilityCatalog.require(SmartAuditCapabilityCatalog.ENTERPRISE_DUE_DILIGENCE);

        CapabilityResultV1 result = provider.query(request(task, List.of(task.materialCategories().getFirst())));

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status());
        List<Map<String, Object>> outcomes = (List<Map<String, Object>>) result.data().get("ruleOutcomes");
        assertEquals("NEEDS_SUPPLEMENT", outcomes.getFirst().get("status"));
        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
        assertEquals(0, modelCalls.get());
    }

    private static CapabilityRequestV1 request(SmartAuditCapabilityCatalog.TaskDefinition task,
                                                List<String> categories) {
        FinanceContextV1 context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-" + task.taskId(),
                "trace-" + task.taskId(), "employee-001", FinanceContextV1.PrincipalType.EMPLOYEE, "builder",
                task.purpose(), "enterprise-case-001", "session-001", FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of(task.requiredScope()));
        List<Map<String, String>> materials = categories.stream()
                .map(category -> Map.of("category", category, "content", category + "示例材料", "declaredFormat", "TEXT"))
                .toList();
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, task.capabilityId(),
                CapabilityRequestV1.Operation.QUERY, context, Map.of("materials", materials), null, null, Map.of(), null);
    }
}
