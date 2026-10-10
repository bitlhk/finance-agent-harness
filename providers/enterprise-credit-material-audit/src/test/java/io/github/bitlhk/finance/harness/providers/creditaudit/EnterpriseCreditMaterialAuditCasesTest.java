package io.github.bitlhk.finance.harness.providers.creditaudit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnterpriseCreditMaterialAuditCasesTest {
    @TempDir Path root;

    @Test void frozenCasesMatchDeterministicTruth() throws Exception {
        JsonNode suite = HarnessJson.mapper().readTree(Path.of(
                "../../conformance/fixtures/enterprise-credit-material-audit/v0.1/cases.json").toFile());
        assertEquals(26, suite.path("cases").size());
        for (JsonNode item : suite.path("cases")) {
            String variant = item.path("variant").asText();
            MaterialSet set = materialSet(variant);
            write(set);
            DemoRuleSet rule = rule(variant);
            var provider = new EnterpriseCreditMaterialAuditProvider(new MaterialSetRepository(root), rule);
            String capability = "cross".equals(item.path("capability").asText())
                    ? EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL
                    : EnterpriseCreditMaterialAuditProvider.FINANCIAL;
            CapabilityResultV1 result = provider.query(request(capability, set.materialSetId(), context()));
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), item.path("id").asText());
            assertEquals(item.path("expected").asText(), result.data().get("status"), item.path("id").asText());
            assertEquals(Boolean.FALSE, result.data().get("approvalDecision"), item.path("id").asText());
            assertEquals(Boolean.TRUE, result.data().get("advisoryOnly"), item.path("id").asText());
        }
    }

    @Test void evidenceRetainsDigestAndJsonPointerWithoutInventingPages() throws Exception {
        MaterialSet set = materialSet("amount-difference");
        write(set);
        var result = provider().query(request(EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL,
                set.materialSetId(), context()));
        List<Map<String, Object>> evidence = (List<Map<String, Object>>) result.data().get("evidence");
        assertEquals(2, evidence.size());
        assertTrue(evidence.stream().allMatch(item -> String.valueOf(item.get("materialDigest")).startsWith("sha256:")));
        assertTrue(evidence.stream().allMatch(item -> {
            Map<?, ?> locator = (Map<?, ?>) item.get("sourceLocator");
            return "JSON_POINTER".equals(locator.get("kind")) && String.valueOf(locator.get("value")).startsWith("/");
        }));
        assertFalse(result.data().toString().contains("page"));
        assertEquals("200.00", ((Map<?, ?>) result.data().get("values")).get("difference"));
    }

    @Test void crossMaterialResultCarriesEveryFrozenComparabilityDimensionAndSource() throws Exception {
        MaterialSet set = materialSet("amount-difference");
        write(set);
        var result = provider().query(request(EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL,
                set.materialSetId(), context()));

        Map<?, ?> basis = (Map<?, ?>) result.data().get("verificationBasis");
        assertEquals("CROSS_MATERIAL", basis.get("mode"));
        assertEquals("COMPARABLE", basis.get("outcome"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) basis.get("dimensions");
        assertEquals(List.of("subject", "period", "currency", "unit", "businessBasis"),
                dimensions.stream().map(item -> String.valueOf(item.get("name"))).toList());
        assertTrue(dimensions.stream().allMatch(item -> "MATCH".equals(item.get("outcome"))));
        Map<String, Object> period = dimensions.stream()
                .filter(item -> "period".equals(item.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> values = (List<Map<String, Object>>) period.get("values");
        assertEquals(2, values.size());
        assertEquals("/period", ((Map<?, ?>) values.get(0).get("sourceLocator")).get("value"));
        assertEquals("2026-Q3", ((Map<?, ?>) values.get(0).get("value")).get("label"));
    }

    @Test void nonComparableResultRetainsBothConflictingPeriodValues() throws Exception {
        MaterialSet set = materialSet("period-mismatch");
        write(set);
        var result = provider().query(request(EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL,
                set.materialSetId(), context()));

        Map<?, ?> basis = (Map<?, ?>) result.data().get("verificationBasis");
        assertEquals("NOT_COMPARABLE", basis.get("outcome"));
        List<Map<String, Object>> dimensions = (List<Map<String, Object>>) basis.get("dimensions");
        Map<String, Object> period = dimensions.stream()
                .filter(item -> "period".equals(item.get("name"))).findFirst().orElseThrow();
        assertEquals("MISMATCH", period.get("outcome"));
        List<Map<String, Object>> values = (List<Map<String, Object>>) period.get("values");
        assertEquals(List.of("2026-Q3", "2026-Q2"), values.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item.get("value")).get("label"))).toList());
    }

    @Test void financialResultCarriesStatementIdentityPeriodAndBasis() throws Exception {
        MaterialSet set = materialSet("balanced");
        write(set);
        var result = provider().query(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                set.materialSetId(), context()));

        Map<?, ?> basis = (Map<?, ?>) result.data().get("verificationBasis");
        assertEquals("SINGLE_MATERIAL", basis.get("mode"));
        assertEquals("RECORDED", basis.get("outcome"));
        assertEquals(5, ((List<?>) basis.get("dimensions")).size());
        assertTrue(String.valueOf(basis).contains("BALANCE_SHEET_END"));
    }

    @Test void ownerPrincipalSessionAndTaskAreAllEnforced() throws Exception {
        MaterialSet set = materialSet("balanced");
        write(set);
        var provider = provider();
        assertDenied(provider, changedContext("other-owner", "reviewer-1", "session-a", "enterprise-credit-material-review"));
        assertDenied(provider, changedContext("enterprise-a", "other-reviewer", "session-a", "enterprise-credit-material-review"));
        assertDenied(provider, changedContext("enterprise-a", "reviewer-1", "other-session", "enterprise-credit-material-review"));
        CapabilityResultV1 wrongTask = provider.query(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                set.materialSetId(), changedContext("enterprise-a", "reviewer-1", "session-a", "another-task")));
        assertEquals("MATERIAL_TASK_MISMATCH", wrongTask.error().sourceCode());
    }

    @Test void pathsAndUnmanagedOperationsAreRejected() throws Exception {
        var provider = provider();
        CapabilityResultV1 traversal = provider.query(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                "../other", context()));
        assertEquals(CapabilityResultV1.Status.FAILED, traversal.status());
        assertEquals("MATERIAL_SET_REF_INVALID", traversal.error().sourceCode());
        CapabilityResultV1 task = provider.executeTask(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                "material-a", context()));
        assertEquals("READ_ONLY_PROVIDER", task.error().sourceCode());
    }

    private void assertDenied(EnterpriseCreditMaterialAuditProvider provider, FinanceContextV1 context) {
        CapabilityResultV1 result = provider.query(request(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                "material-a", context));
        assertEquals(CapabilityResultV1.Status.FAILED, result.status());
        assertEquals("MATERIAL_SET_SCOPE_MISMATCH", result.error().sourceCode());
    }

    private EnterpriseCreditMaterialAuditProvider provider() {
        return new EnterpriseCreditMaterialAuditProvider(new MaterialSetRepository(root), rule("balanced"));
    }

    private void write(MaterialSet set) throws Exception {
        HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValue(root.resolve(set.materialSetId() + ".json").toFile(), set);
    }

    private static CapabilityRequestV1 request(String capability, String reference, FinanceContextV1 context) {
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, capability,
                CapabilityRequestV1.Operation.QUERY, context, Map.of("materialSetRef", reference),
                null, null, Map.of(), null);
    }

    private static FinanceContextV1 context() {
        return changedContext("enterprise-a", "reviewer-1", "session-a", "enterprise-credit-material-review");
    }

    private static FinanceContextV1 changedContext(String owner, String principal, String session, String purpose) {
        return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request-a", "trace-a", principal,
                FinanceContextV1.PrincipalType.EMPLOYEE, "isolated-builder", purpose, owner, session,
                FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of("credit-audit:financial-statement:read", "credit-audit:application-contract:read"));
    }

    private static DemoRuleSet rule(String variant) {
        LocalDate from = "future-rule".equals(variant) ? LocalDate.of(2027, 1, 1) : LocalDate.of(2026, 1, 1);
        LocalDate to = "expired-rule".equals(variant) ? LocalDate.of(2026, 6, 30) : LocalDate.of(2027, 12, 31);
        return new DemoRuleSet("finance-audit-demo-rules.v0.1", "demo-enterprise-material-verification",
                "0.1.0", "DEMO", "ACTIVE", from, to, 2, "HALF_UP",
                List.of(EnterpriseCreditMaterialAuditProvider.FINANCIAL,
                        EnterpriseCreditMaterialAuditProvider.CROSS_MATERIAL));
    }

    private static MaterialSet materialSet(String variant) {
        var materials = new java.util.ArrayList<MaterialSet.Material>();
        if (!"statement-missing".equals(variant) && !"application-missing".equals(variant)
                && !"contract-missing".equals(variant)) {
            if (variant.startsWith("fin") || Set.of("balanced", "balanced-decimal", "difference-positive",
                    "difference-negative", "assets-missing", "liabilities-missing", "equity-extraction-failed",
                    "currency-missing", "unit-missing", "round-half-up", "expired-rule",
                    "malicious-financial-content").contains(variant)) {
                materials.add(statement(variant));
            }
        }
        boolean cross = Set.of("equal", "amount-difference", "subject-mismatch", "period-mismatch",
                "currency-mismatch", "unit-mismatch", "basis-mismatch", "application-amount-missing",
                "contract-amount-failed", "application-missing", "contract-missing", "future-rule").contains(variant)
                || "malicious-cross-content".equals(variant);
        if (cross) {
            if (!"application-missing".equals(variant)) materials.add(application(variant));
            if (!"contract-missing".equals(variant)) materials.add(contract(variant));
        }
        if (materials.isEmpty() && !"statement-missing".equals(variant)) materials.add(statement(variant));
        return new MaterialSet("finance-material-set.v0.1", "material-a", "enterprise-a", "reviewer-1",
                "enterprise-credit-material-review", "session-a", LocalDate.of(2026, 10, 9), materials);
    }

    private static MaterialSet.Material statement(String variant) {
        Map<String, MaterialSet.Field> fields = new LinkedHashMap<>();
        fields.put("assets", field(Set.of("balanced-decimal", "round-half-up").contains(variant) ? "1000.005" :
                "difference-positive".equals(variant) ? "1200" : "difference-negative".equals(variant) ? "900" : "1000"));
        fields.put("liabilities", field("400"));
        fields.put("equity", field(Set.of("balanced-decimal", "round-half-up").contains(variant) ? "600.005" : "600"));
        if ("assets-missing".equals(variant)) fields.remove("assets");
        if ("liabilities-missing".equals(variant)) fields.remove("liabilities");
        if ("equity-extraction-failed".equals(variant)) fields.put("equity", failed("/equity"));
        String currency = "currency-missing".equals(variant) ? null : "CNY";
        String unit = "unit-missing".equals(variant) ? null : "YUAN";
        return material("statement-1", "FINANCIAL_STATEMENT", "企业甲", period("2025"), currency, unit,
                "BALANCE_SHEET_END", fields);
    }

    private static MaterialSet.Material application(String variant) {
        String subject = "企业甲";
        String currency = "CNY";
        String unit = "YUAN";
        String basis = "PURCHASE_TOTAL_INCL_TAX";
        var period = period("2026-Q3");
        Map<String, MaterialSet.Field> fields = new LinkedHashMap<>();
        fields.put("requestedAmount", field("1000"));
        if ("application-amount-missing".equals(variant)) fields.remove("requestedAmount");
        return material("application-1", "CREDIT_APPLICATION", subject, period, currency, unit, basis, fields);
    }

    private static MaterialSet.Material contract(String variant) {
        String subject = "subject-mismatch".equals(variant) ? "企业乙" : "企业甲";
        String currency = "currency-mismatch".equals(variant) ? "USD" : "CNY";
        String unit = "unit-mismatch".equals(variant) ? "THOUSAND" : "YUAN";
        String basis = "basis-mismatch".equals(variant) ? "PURCHASE_NET_OF_TAX" : "PURCHASE_TOTAL_INCL_TAX";
        var period = period("period-mismatch".equals(variant) ? "2026-Q2" : "2026-Q3");
        Map<String, MaterialSet.Field> fields = new LinkedHashMap<>();
        fields.put("contractAmount", field("amount-difference".equals(variant) ? "800" : "1000"));
        if ("contract-amount-failed".equals(variant)) fields.put("contractAmount", failed("/contractAmount"));
        return material("contract-1", "USE_CONTRACT", subject, period, currency, unit, basis, fields);
    }

    private static MaterialSet.Material material(String id, String category, String subject, MaterialSet.Period period,
                                                  String currency, String unit, String basis,
                                                  Map<String, MaterialSet.Field> fields) {
        return new MaterialSet.Material(id, category, "1", "sha256:" + id + "000000000000000000000000000000000000000000000000000000",
                subject, period, currency, unit, basis, "JSON", fields);
    }

    private static MaterialSet.Field field(String value) {
        return new MaterialSet.Field(value, MaterialSet.FieldStatus.EXTRACTED,
                new MaterialSet.Locator("JSON_POINTER", "/" + (value.equals("1000") ? "amount" : "field")));
    }

    private static MaterialSet.Field failed(String path) {
        return new MaterialSet.Field(null, MaterialSet.FieldStatus.EXTRACTION_FAILED,
                new MaterialSet.Locator("JSON_POINTER", path));
    }

    private static MaterialSet.Period period(String label) {
        return new MaterialSet.Period("2026-Q2".equals(label) ? LocalDate.of(2026, 4, 1) : LocalDate.of(2026, 7, 1),
                "2026-Q2".equals(label) ? LocalDate.of(2026, 6, 30) : LocalDate.of(2026, 9, 30), label);
    }
}
