package io.github.bitlhk.finance.harness.providers.smartaudit;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Public-contract RED tests for the smart-audit provider and its domain assets.
 *
 * <p>Reflection deliberately keeps this test suite executable before the provider exists. Once the
 * provider is added, it exercises only its published SPI surface rather than implementation details.
 */
class SmartAuditContractRedTest {
    private static final Path ROOT = findRepositoryRoot();
    private static final Path PERSONAL_PACK = ROOT.resolve("domain-packs/smart-audit/personal-loan-audit");
    private static final Path ENTERPRISE_PACK = ROOT.resolve("domain-packs/smart-audit/enterprise-credit-audit");
    private static final Path ENTERPRISE_CAPABILITY_PACK =
            ROOT.resolve("capability-packs/smart-audit-enterprise-credit-audit");
    private static final Path PERSONAL_BASELINE = PERSONAL_PACK.resolve("rules/personal-rule-baseline.v1.json");

    private static final List<String> PERSONAL_SKILLS = List.of(
            "borrower-qualification", "income-verification", "asset-liability", "repayment-capacity");
    private static final List<String> ENTERPRISE_SKILLS = List.of(
            "financial-report", "financial-summary", "transaction-analysis", "transaction-report", "due-diligence");
    private static final List<String> CAPABILITY_IDS = List.of(
            "smartaudit.personal.borrower-qualification.query",
            "smartaudit.personal.income-verification.query",
            "smartaudit.personal.asset-liability.query",
            "smartaudit.personal.repayment-capacity.query",
            "smartaudit.enterprise.financial-report.query",
            "smartaudit.enterprise.financial-summary.query",
            "smartaudit.enterprise.transaction-analysis.query",
            "smartaudit.enterprise.transaction-report.query",
            "smartaudit.enterprise.due-diligence.query");

    @Test
    void smartAuditDomainPacksExposeExpectedPersonalAndEnterpriseSkillAssets() throws Exception {
        assertPackSkills(PERSONAL_PACK, PERSONAL_SKILLS);
        assertPackSkills(ENTERPRISE_PACK, ENTERPRISE_SKILLS);
    }

    @Test
    void enterpriseCandidateSkillAssetsNeverInstructCreditApprovalRejectionOrProxyRating() throws Exception {
        List<String> forbiddenDecisionOrProxyRatingPhrases = List.of(
                "建议提供贷款", "不建议提供贷款", "建议拒绝",
                "坚决拒绝", "审慎准入", "推荐准入",
                "推荐产品方向", "不推荐产品清单",
                "终止授信", "特批继续申报", "授信方案总结",
                "授信总金额", "初步建议的信用评级", "终审评级",
                "所建议或同意的具体还款方式", "额度调整",
                "余额充沛", "经营健康度", "资金充足性", "资金承载力",
                "一级风险标签", "二级风险标签", "三级风险标签",
                "大于5000元", "半数时间", "风险雷达", "人工审批前审核建议", "人工审批前",
                "准入建议", "评分");
        for (Path assetRoot : List.of(ENTERPRISE_PACK, ENTERPRISE_CAPABILITY_PACK)) {
            try (var assets = Files.walk(assetRoot)) {
                for (Path asset : assets.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".md")
                                || path.getFileName().toString().endsWith(".yaml")).toList()) {
                    String content = Files.readString(asset);
                    for (String phrase : forbiddenDecisionOrProxyRatingPhrases) {
                        assertFalse(content.contains(phrase),
                                () -> "enterprise candidate asset must not instruct a credit decision or proxy rating: "
                                        + asset + " -> " + phrase);
                    }
                }
            }
        }
    }

    @Test
    void personalRuleBaselineDeclaresEveryRequiredComponentAndTaskSubsetMapping() throws Exception {
        assertTrue(Files.isRegularFile(PERSONAL_BASELINE), "personal rule baseline must exist");

        String baseline = Files.readString(PERSONAL_BASELINE);
        assertTrue(baseline.contains("\"schemaVersion\""), "baseline must declare a schema version");
        assertTrue(baseline.contains("personal-rule-baseline.v1"), "baseline schema version must be personal-rule-baseline.v1");
        for (String component : List.of(
                "qualificationIncomePreGeneratedRules",
                "assetLiabilityDefaultRules",
                "repaymentCapacityDefaultRules")) {
            assertTrue(baseline.contains("\"" + component + "\""), "missing baseline component: " + component);
        }
        assertTrue(baseline.contains("taskToSubset"), "baseline must map tasks to rule subsets");
    }

    @Test
    void smartAuditProviderDeclaresOnlyNineQueryCapabilitiesAndRejectsTaskOrUnknownWithoutCallingModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Object provider = provider(PERSONAL_BASELINE, calls);

        Object descriptor = invoke(provider, "descriptor");
        Method capabilities = descriptor.getClass().getMethod("capabilities");
        List<?> declared = (List<?>) capabilities.invoke(descriptor);
        assertEquals(9, declared.size());
        assertEquals(CAPABILITY_IDS, declared.stream()
                .map(capability -> invokeUnchecked(capability, "capabilityId").toString())
                .collect(Collectors.toList()));
        assertTrue(declared.stream().allMatch(capability -> "QUERY".equals(
                invokeUnchecked(capability, "operation").toString())));

        Object taskResult = invokeQuery(provider, request(CAPABILITY_IDS.getFirst(), "TASK"));
        assertFailed(taskResult, "TASK must be denied");
        assertEquals(0, calls.get(), "TASK rejection must not call the model port");

        Object unknownResult = invokeQuery(provider, request("smartaudit.unknown.query", "QUERY"));
        assertFailed(unknownResult, "unknown capability must be denied");
        assertEquals(0, calls.get(), "unknown-capability rejection must not call the model port");
    }

    @Test
    void missingOrAlteredPersonalBaselineIsDeniedWithoutCallingModel() throws Exception {
        AtomicInteger missingCalls = new AtomicInteger();
        Path missing = PERSONAL_BASELINE.resolveSibling("missing-personal-rule-baseline.v1.json");
        assertFalse(Files.exists(missing), "test baseline path must be absent");
        assertBaselineIsDenied(missing, missingCalls, "missing baseline");

        assertTrue(Files.isRegularFile(PERSONAL_BASELINE), "canonical baseline must exist before tamper test");
        Path altered = Files.createTempFile("altered-personal-rule-baseline-", ".json");
        try {
            Files.writeString(altered, Files.readString(PERSONAL_BASELINE).replace(
                    "repaymentCapacityDefaultRules", "tamperedRepaymentCapacityDefaultRules"));
            AtomicInteger alteredCalls = new AtomicInteger();
            assertBaselineIsDenied(altered, alteredCalls, "altered baseline");
        } finally {
            Files.deleteIfExists(altered);
        }
    }

    private static void assertPackSkills(Path packRoot, List<String> skills) throws Exception {
        assertTrue(Files.isRegularFile(packRoot.resolve("pack.yaml")), "pack descriptor must exist: " + packRoot);
        Path skillsRoot = packRoot.resolve("skills");
        assertTrue(Files.isDirectory(skillsRoot), "skills directory must exist: " + skillsRoot);
        assertEquals(skills.size(), Files.list(skillsRoot).filter(Files::isDirectory).count(),
                "pack must expose exactly " + skills.size() + " skill directories");
        for (String skill : skills) {
            assertTrue(Files.isRegularFile(skillsRoot.resolve(skill).resolve("SKILL.md")),
                    "expected skill asset missing: " + skill);
        }
    }

    private static void assertBaselineIsDenied(Path baseline, AtomicInteger calls, String scenario) throws Exception {
        Object provider = provider(baseline, calls);
        Object result = invokeQuery(provider, request(CAPABILITY_IDS.getFirst(), "QUERY"));
        assertFailed(result, scenario + " must be denied");
        assertEquals(0, calls.get(), scenario + " must not call the model port");
    }

    private static Object provider(Path baseline, AtomicInteger calls) throws Exception {
        Class<?> providerType = requiredClass("io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelProvider");
        Class<?> configType = requiredClass(providerType.getName() + "$Config");
        Class<?> portType = requiredClass("io.github.bitlhk.finance.harness.providers.smartaudit.SmartAuditModelPort");
        Object port = Proxy.newProxyInstance(portType.getClassLoader(), new Class<?>[]{portType}, (proxy, method, arguments) -> {
            if (method.getName().equals("assess")) {
                calls.incrementAndGet();
                fail("model port must not be called for rejected requests");
            }
            if (method.getName().equals("toString")) return "recording-model-port";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == arguments[0];
            return null;
        });

        Constructor<?> constructor = Arrays.stream(providerType.getConstructors())
                .filter(candidate -> Arrays.equals(candidate.getParameterTypes(), new Class<?>[]{configType, portType}))
                .findFirst()
                .orElseThrow(() -> new AssertionError("SmartAuditModelProvider must expose constructor (Config, SmartAuditModelPort)"));
        return constructor.newInstance(config(configType, baseline), port);
    }

    private static Object config(Class<?> configType, Path baseline) throws Exception {
        Constructor<?>[] constructors = configType.getConstructors();
        assertTrue(constructors.length > 0, "SmartAuditModelProvider.Config must expose a public constructor");
        Constructor<?> constructor = Arrays.stream(constructors)
                .filter(candidate -> Arrays.asList(candidate.getParameterTypes()).contains(Path.class))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Config must accept the personal rule baseline Path"));
        Object[] values = Arrays.stream(constructor.getParameterTypes())
                .map(type -> configValue(type, baseline))
                .toArray();
        return constructor.newInstance(values);
    }

    private static Object configValue(Class<?> type, Path baseline) {
        if (type == Path.class) return baseline;
        if (type == String.class) return "smart-audit-test";
        if (type == Duration.class) return Duration.ofSeconds(1);
        if (type == URI.class) return URI.create("https://example.invalid");
        if (type == boolean.class || type == Boolean.class) return false;
        if (type == int.class || type == Integer.class) return 1;
        if (type == long.class || type == Long.class) return 1L;
        if (type == Map.class) return Map.of();
        if (type == Set.class) return Set.of();
        if (type.isEnum()) return type.getEnumConstants()[0];
        throw new AssertionError("test cannot supply Config parameter type: " + type.getName());
    }

    private static Object request(String capabilityId, String operation) throws Exception {
        Class<?> contextType = requiredClass("io.github.bitlhk.finance.harness.core.FinanceContextV1");
        Class<?> principalType = requiredClass(contextType.getName() + "$PrincipalType");
        Class<?> authenticationType = requiredClass(contextType.getName() + "$AuthenticationLevel");
        Object context = contextType.getConstructors()[0].newInstance(
                "finance-context.v1", "request-smart-audit-001", "trace-smart-audit-001", "customer-001",
                Enum.valueOf((Class) principalType, "CUSTOMER"), "smart-audit-test", "LOAN_AUDIT", "customer-001",
                "session-smart-audit", Enum.valueOf((Class) authenticationType, "STRONG"), Set.of("smart-audit:read"));

        Class<?> requestType = requiredClass("io.github.bitlhk.finance.harness.core.CapabilityRequestV1");
        Class<?> operationType = requiredClass(requestType.getName() + "$Operation");
        return requestType.getConstructors()[0].newInstance(
                "capability-request.v1", capabilityId, Enum.valueOf((Class) operationType, operation), context,
                Map.of(), null, null, Map.of(), null);
    }

    private static Object invokeQuery(Object provider, Object request) throws Exception {
        Method query = Arrays.stream(provider.getClass().getMethods())
                .filter(method -> method.getName().equals("query") && method.getParameterCount() == 1)
                .findFirst()
                .orElseThrow(() -> new AssertionError("SmartAuditModelProvider must expose query(CapabilityRequestV1)"));
        return query.invoke(provider, request);
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object invokeUnchecked(Object target, String method) {
        try {
            return invoke(target, method);
        } catch (Exception exception) {
            throw new AssertionError("missing descriptor accessor " + method, exception);
        }
    }

    private static void assertFailed(Object result, String message) throws Exception {
        assertNotNull(result, message + " must return a result");
        Object status = invoke(result, "status");
        assertEquals("FAILED", status.toString(), message);
    }

    private static Class<?> requiredClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("required feature class is absent: " + name, missing);
        }
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("contracts/json-schema"))
                    && Files.isDirectory(current.resolve("domain-packs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("finance-agent-harness repository root not found");
    }
}
