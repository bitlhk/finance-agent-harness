package io.github.bitlhk.finance.harness.providers.smartaudit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public regulatory baseline tests; bank-specific policy parameters are deliberately not invented here. */
class PersonalLoanRegulatoryPolicyTest {
    private static final Path ROOT = findRepositoryRoot();
    private static final Path RULES = ROOT.resolve("domain-packs/smart-audit/personal-loan-audit/rules");
    private static final Path BASELINE = RULES.resolve("personal-rule-baseline.v1.json");
    private static final Path REGULATORY_POLICY = RULES.resolve("personal-loan-regulatory-baseline.v1.json");

    @Test
    void publishedNfra2024BaselineIsVersionedAndMarksBankThresholdsAsConfigurationOnly() throws Exception {
        assertTrue(Files.isRegularFile(REGULATORY_POLICY), "current public regulatory policy asset must exist");
        JsonNode policy = HarnessJson.mapper().readTree(Files.readString(REGULATORY_POLICY));

        assertEquals("personal-loan-regulatory-baseline.v1", policy.path("schemaVersion").asText());
        assertEquals("个人贷款管理办法", policy.path("regulation").path("title").asText());
        assertEquals("国家金融监督管理总局", policy.path("regulation").path("issuer").asText());
        assertEquals("国家金融监督管理总局令〔2024〕3号", policy.path("regulation").path("order").asText());
        assertEquals("1151064", policy.path("regulation").path("documentId").asText());
        assertEquals("2024-07-01", policy.path("regulation").path("effectiveDate").asText());
        assertEquals("https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=1151064&generaltype=1&itemId=4214",
                policy.path("regulation").path("sourceUrl").asText());
        assertTrue(policy.path("regulatedRequirements").isArray());
        assertEquals(8, policy.path("regulatedRequirements").size(),
                "baseline must capture each selected public requirement with article and content");
        for (JsonNode requirement : policy.path("regulatedRequirements")) {
            assertFalse(requirement.path("requirementId").asText().isBlank());
            assertFalse(requirement.path("article").asText().isBlank());
            assertFalse(requirement.path("requirement").asText().isBlank());
        }
        JsonNode repealed = policy.path("repealedReferences").get(0);
        assertEquals("个人贷款管理暂行办法", repealed.path("title").asText());
        assertEquals("国家金融监督管理总局令〔2024〕3号第五十二条", repealed.path("repealedBy").asText());
        assertTrue(policy.path("bankSpecificParameters").isArray());
        for (JsonNode parameter : policy.path("bankSpecificParameters")) {
            assertEquals("REQUIRED_CONFIGURATION", parameter.path("configurationStatus").asText());
            assertFalse(parameter.path("maySupportPositiveOutcome").asBoolean(true),
                    "an unapproved bank threshold must never support a positive outcome");
        }
    }

    @Test
    void providerRejectsPersonalBaselineThatCitesRepealedInterimMeasuresBeforeCallingModel() throws Exception {
        Path temporaryRules = Files.createTempDirectory("stale-personal-loan-policy-");
        try {
            Path policy = temporaryRules.resolve(REGULATORY_POLICY.getFileName());
            Path staleBaseline = temporaryRules.resolve(BASELINE.getFileName());
            Files.copy(REGULATORY_POLICY, policy);
            String original = Files.readString(BASELINE);
            String stale = original.replaceFirst("\\n}\\s*$",
                    ",\\n  \\\"legacyReference\\\": \\\"《个人贷款管理暂行办法》\\\"\\n}\\n");
            Files.writeString(staleBaseline, stale);

            AtomicInteger calls = new AtomicInteger();
            SmartAuditModelProvider provider = new SmartAuditModelProvider(
                    new SmartAuditModelProvider.Config(staleBaseline, "test-model"), request -> {
                        calls.incrementAndGet();
                        return new SmartAuditModelResponse("not used", "完整", List.of(), List.of(), List.of(), "test-model");
                    });

            CapabilityResultV1 result = provider.query(validBorrowerQualificationRequest());

            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("PERSONAL_RULE_BASELINE_INVALID", result.error().sourceCode());
            assertEquals(0, calls.get(), "repealed references must be rejected before a model call");
        } finally {
            try (var paths = Files.walk(temporaryRules)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException exception) {
                        throw new IllegalStateException(exception);
                    }
                });
            }
        }
    }

    @Test
    void providerRejectsBaselineWithPositiveBankThresholdControlBeforeCallingModel() throws Exception {
        Path temporaryRules = copyRules("malformed-personal-loan-threshold-");
        try {
            Path baseline = temporaryRules.resolve(BASELINE.getFileName());
            Files.writeString(baseline, Files.readString(baseline).replace(
                    "\"maySupportPositiveOutcome\": false", "\"maySupportPositiveOutcome\": true"));

            AtomicInteger calls = new AtomicInteger();
            CapabilityResultV1 result = provider(baseline, calls).query(validBorrowerQualificationRequest());

            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("PERSONAL_RULE_BASELINE_INVALID", result.error().sourceCode());
            assertEquals(0, calls.get());
        } finally {
            deleteTree(temporaryRules);
        }
    }

    @Test
    void providerRejectsBaselineThatOmitsARequiredBankThresholdParameterBeforeCallingModel() throws Exception {
        Path temporaryRules = copyRules("incomplete-personal-loan-threshold-");
        try {
            Path baseline = temporaryRules.resolve(BASELINE.getFileName());
            Files.writeString(baseline, Files.readString(baseline).replace(
                    "      \"COLLATERAL_AND_LOAN_AMOUNT_LIMITS\"\n", ""));

            AtomicInteger calls = new AtomicInteger();
            CapabilityResultV1 result = provider(baseline, calls).query(validBorrowerQualificationRequest());

            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("PERSONAL_RULE_BASELINE_INVALID", result.error().sourceCode());
            assertEquals(0, calls.get());
        } finally {
            deleteTree(temporaryRules);
        }
    }

    @Test
    void providerRejectsBaselineWithBlankRootVersionBeforeCallingModel() throws Exception {
        Path temporaryRules = copyRules("blank-personal-loan-baseline-version-");
        try {
            Path baseline = temporaryRules.resolve(BASELINE.getFileName());
            Files.writeString(baseline, Files.readString(baseline).replace(
                    "\"version\": \"1.0.0\"", "\"version\": \"  \""));

            AtomicInteger calls = new AtomicInteger();
            CapabilityResultV1 result = provider(baseline, calls).query(validBorrowerQualificationRequest());

            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("PERSONAL_RULE_BASELINE_INVALID", result.error().sourceCode());
            assertEquals(0, calls.get());
        } finally {
            deleteTree(temporaryRules);
        }
    }

    @Test
    void providerRejectsPolicyWhoseOfficialIdentityOrRequiredArticleContentDriftsBeforeCallingModel() throws Exception {
        Path temporaryRules = copyRules("drifted-nfra-personal-loan-policy-");
        try {
            Path policy = temporaryRules.resolve(REGULATORY_POLICY.getFileName());
            String altered = Files.readString(policy)
                    .replace("\"title\": \"个人贷款管理办法\"", "\"title\": \"错误法规标题\"")
                    .replace("\"article\": \"第五条\"", "\"article\": \"第六条\"");
            Files.writeString(policy, altered);

            AtomicInteger calls = new AtomicInteger();
            CapabilityResultV1 result = provider(temporaryRules.resolve(BASELINE.getFileName()), calls)
                    .query(validBorrowerQualificationRequest());

            assertEquals(CapabilityResultV1.Status.FAILED, result.status());
            assertEquals("PERSONAL_RULE_BASELINE_INVALID", result.error().sourceCode());
            assertEquals(0, calls.get());
        } finally {
            deleteTree(temporaryRules);
        }
    }

    @Test
    void missingApprovedBankConfigurationReturnsCannotDetermineAndManualReviewWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityResultV1 result = provider(BASELINE, calls).query(validBorrowerQualificationRequest());

        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), () -> String.valueOf(result.error()));
        assertEquals(0, calls.get(), "model must not form a result without approved bank configuration");
        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
        assertEquals("不可判断", result.data().get("completeness"));
        List<?> outcomes = (List<?>) result.data().get("ruleOutcomes");
        assertNotNull(outcomes);
        assertEquals("CANNOT_DETERMINE", ((Map<?, ?>) outcomes.getFirst()).get("status"));
        assertTrue(outcomes.stream().map(Map.class::cast).anyMatch(outcome ->
                "PERSONAL_LOAN_DUE_DILIGENCE_EVIDENCE_VERIFICATION_V1".equals(outcome.get("ruleId"))
                        && "CANNOT_DETERMINE".equals(outcome.get("status"))),
                "the provider must execute evidence verification deterministically before any model explanation");
        List<?> evidence = (List<?>) result.data().get("evidence");
        assertEquals(4, evidence.size());
        assertTrue(evidence.stream().map(Map.class::cast).allMatch(item -> "UNVERIFIED".equals(item.get("status"))),
                "uploaded text must never be presented as independently verified evidence");
        assertTrue(result.nextActions().contains("SUBMIT_HUMAN_REVIEW"));
    }

    @Test
    void allPersonalTasksEmitOnlyEvidenceSupportedMetOutcomesAndDoNotCallTheModelWithoutBankPolicy() {
        AtomicInteger calls = new AtomicInteger();
        SmartAuditModelProvider provider = provider(BASELINE, calls);
        for (SmartAuditCapabilityCatalog.TaskDefinition task : SmartAuditCapabilityCatalog.tasks()) {
            if (!task.personal()) continue;
            CapabilityResultV1 result = provider.query(validPersonalRequest(task));
            assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), task.capabilityId());
            List<?> outcomes = (List<?>) result.data().get("ruleOutcomes");
            boolean allowsWrittenApplicationCollection = task.materialCategories().contains("贷款申请表");
            for (Object rawOutcome : outcomes) {
                Map<?, ?> outcome = (Map<?, ?>) rawOutcome;
                if (!allowsWrittenApplicationCollection) {
                    assertFalse("MET".equals(outcome.get("status")),
                            () -> task.taskId() + " must not mark a rule MET without its required evidence");
                }
            }
        }
        assertEquals(0, calls.get(), "missing approved bank policy must prevent explanatory-model calls for every task");
    }

    @Test
    void futureApprovedBankParameterStillReturnsCannotDetermineAndManualReviewWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        CapabilityResultV1 result = provider(BASELINE, calls,
                approvedParameters(LocalDate.now().plusDays(1), validValue())).query(validBorrowerQualificationRequest());

        assertCannotDetermineForConfiguration(result, calls, "BORROWER_AGE_AND_TERM_LIMITS");
    }

    @Test
    void malformedApprovedBankParameterValueStillReturnsCannotDetermineAndManualReviewWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, SmartAuditModelProvider.BankPolicyParameter> parameters = approvedParameters(LocalDate.now(), validValue());
        parameters.put("INCOME_VERIFICATION_PERIOD_AND_VARIANCE",
                new SmartAuditModelProvider.BankPolicyParameter("bank-policy-v1", LocalDate.now(),
                        Map.of("type", "NUMBER", "value", "not-a-number"), true));
        CapabilityResultV1 result = provider(BASELINE, calls, parameters).query(validBorrowerQualificationRequest());

        assertCannotDetermineForConfiguration(result, calls, "INCOME_VERIFICATION_PERIOD_AND_VARIANCE");
    }

    @Test
    void blankPolicyVersionOnOtherwiseApprovedAndEffectiveParameterReturnsCannotDetermineWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, SmartAuditModelProvider.BankPolicyParameter> parameters = approvedParameters(LocalDate.now(), validValue());
        parameters.put("REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST",
                new SmartAuditModelProvider.BankPolicyParameter(" ", LocalDate.now(), validValue(), true));
        CapabilityResultV1 result = provider(BASELINE, calls, parameters).query(validBorrowerQualificationRequest());

        assertCannotDetermineForConfiguration(result, calls, "REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST");
    }

    private static CapabilityRequestV1 validBorrowerQualificationRequest() {
        return validPersonalRequest(SmartAuditCapabilityCatalog.require(
                SmartAuditCapabilityCatalog.PERSONAL_BORROWER_QUALIFICATION));
    }

    private static CapabilityRequestV1 validPersonalRequest(SmartAuditCapabilityCatalog.TaskDefinition task) {
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION, "request-policy-001", "trace-policy-001", "employee-001",
                FinanceContextV1.PrincipalType.EMPLOYEE, "builder", "PERSONAL_LOAN_AUDIT", "case-001",
                "session-001", FinanceContextV1.AuthenticationLevel.STRONG,
                Set.of(task.requiredScope()));
        List<Map<String, String>> materials = task.materialCategories().stream().map(PersonalLoanRegulatoryPolicyTest::material).toList();
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                task.capabilityId(), CapabilityRequestV1.Operation.QUERY,
                context, Map.of("materials", materials), null, null, Map.of(), null);
    }

    private static Map<String, String> material(String category) {
        return Map.of("category", category, "content", category + "示例材料", "declaredFormat", "TEXT");
    }

    private static SmartAuditModelProvider provider(Path baseline, AtomicInteger calls) {
        return provider(baseline, calls, Map.of());
    }

    private static SmartAuditModelProvider provider(Path baseline, AtomicInteger calls,
                                                    Map<String, SmartAuditModelProvider.BankPolicyParameter> parameters) {
        return new SmartAuditModelProvider(new SmartAuditModelProvider.Config(baseline, "test-model", parameters), request -> {
            calls.incrementAndGet();
            return new SmartAuditModelResponse("model response must not be used", "完整", List.of(), List.of(), List.of(), "test-model");
        });
    }

    private static Map<String, SmartAuditModelProvider.BankPolicyParameter> approvedParameters(
            LocalDate ageParameterEffectiveDate, Map<String, Object> value) {
        Map<String, SmartAuditModelProvider.BankPolicyParameter> parameters = new java.util.LinkedHashMap<>();
        parameters.put("BORROWER_AGE_AND_TERM_LIMITS",
                new SmartAuditModelProvider.BankPolicyParameter("bank-policy-v1", ageParameterEffectiveDate, value, true));
        parameters.put("INCOME_VERIFICATION_PERIOD_AND_VARIANCE",
                new SmartAuditModelProvider.BankPolicyParameter("bank-policy-v1", LocalDate.now(), value, true));
        parameters.put("REPAYMENT_CAPACITY_RATIO_AND_STRESS_TEST",
                new SmartAuditModelProvider.BankPolicyParameter("bank-policy-v1", LocalDate.now(), value, true));
        parameters.put("COLLATERAL_AND_LOAN_AMOUNT_LIMITS",
                new SmartAuditModelProvider.BankPolicyParameter("bank-policy-v1", LocalDate.now(), value, true));
        return parameters;
    }

    private static Map<String, Object> validValue() {
        return Map.of("type", "NUMBER", "value", new java.math.BigDecimal("1"));
    }

    private static void assertCannotDetermineForConfiguration(CapabilityResultV1 result, AtomicInteger calls,
                                                               String missingParameterId) {
        assertEquals(CapabilityResultV1.Status.COMPLETED, result.status(), () -> String.valueOf(result.error()));
        assertEquals(0, calls.get());
        assertEquals(Boolean.TRUE, result.data().get("manualReviewRequired"));
        assertEquals("不可判断", result.data().get("completeness"));
        assertTrue(result.missingInputs().contains(missingParameterId));
        List<?> outcomes = (List<?>) result.data().get("ruleOutcomes");
        assertTrue(outcomes.stream().map(Map.class::cast).anyMatch(outcome ->
                "PERSONAL_LOAN_BANK_THRESHOLD_APPLICATION_V1".equals(outcome.get("ruleId"))
                        && "CANNOT_DETERMINE".equals(outcome.get("status"))));
        assertTrue(result.nextActions().contains("SUBMIT_HUMAN_REVIEW"));
    }

    private static Path copyRules(String prefix) throws Exception {
        Path temporaryRules = Files.createTempDirectory(prefix);
        Files.copy(BASELINE, temporaryRules.resolve(BASELINE.getFileName()));
        Files.copy(REGULATORY_POLICY, temporaryRules.resolve(REGULATORY_POLICY.getFileName()));
        return temporaryRules;
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("domain-packs"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
