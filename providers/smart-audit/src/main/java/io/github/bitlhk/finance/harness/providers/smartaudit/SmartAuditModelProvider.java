package io.github.bitlhk.finance.harness.providers.smartaudit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.InvocationAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * In-process, query-only Smart Audit provider.  It never opens a network connection, obtains model
 * credentials, creates grants, or performs a lending/credit business action.
 */
public final class SmartAuditModelProvider implements DescribedFinanceProvider, InvocationAccess {
    public static final String PROVIDER_ID = "smart-audit-model-provider";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    private static final Set<String> BASELINE_COMPONENTS = Set.of(
            "qualificationIncomePreGeneratedRules", "assetLiabilityDefaultRules", "repaymentCapacityDefaultRules");
    private static final String PERSONAL_REGULATORY_POLICY_FILE = "personal-loan-regulatory-baseline.v1.json";
    private static final String PERSONAL_REGULATORY_POLICY_SCHEMA = "personal-loan-regulatory-baseline.v1";
    private static final String NFRA_2024_PERSONAL_LOAN_TITLE = "个人贷款管理办法";
    private static final String NFRA_ISSUER = "国家金融监督管理总局";
    private static final String NFRA_2024_PERSONAL_LOAN_ORDER = "国家金融监督管理总局令〔2024〕3号";
    private static final String NFRA_2024_PERSONAL_LOAN_DOCUMENT_ID = "1151064";
    private static final String NFRA_2024_PERSONAL_LOAN_EFFECTIVE_DATE = "2024-07-01";
    private static final String NFRA_2024_PERSONAL_LOAN_SOURCE_URL =
            "https://www.nfra.gov.cn/cn/view/pages/ItemDetail.html?docId=1151064&generaltype=1&itemId=4214";
    private static final String REPEALED_PERSONAL_LOAN_INTERIM_MEASURES = "个人贷款管理暂行办法";
    private static final String REPEALED_BY_NFRA_2024_PERSONAL_LOAN_ORDER_ARTICLE_52 =
            "国家金融监督管理总局令〔2024〕3号第五十二条";
    private static final Map<String, RegulatoryRequirementDefinition> REQUIRED_REGULATORY_REQUIREMENTS = Map.ofEntries(
            Map.entry("PERSONAL_LOAN_FULL_LIFECYCLE_MANAGEMENT", new RegulatoryRequirementDefinition("第五条", "全流程管理")),
            Map.entry("PERSONAL_LOAN_REPAYMENT_CAPACITY_CONTROL", new RegulatoryRequirementDefinition("第十条", "收入偿债比例控制机制")),
            Map.entry("PERSONAL_LOAN_APPLICATION_CONDITIONS", new RegulatoryRequirementDefinition("第十二条", "完全民事行为能力")),
            Map.entry("PERSONAL_LOAN_WRITTEN_APPLICATION_AND_EVIDENCE", new RegulatoryRequirementDefinition("第十三条", "书面形式")),
            Map.entry("PERSONAL_LOAN_DUE_DILIGENCE", new RegulatoryRequirementDefinition("第十四条", "真实性、准确性")),
            Map.entry("PERSONAL_LOAN_INVESTIGATION_SCOPE", new RegulatoryRequirementDefinition("第十五条", "借款人基本情况")),
            Map.entry("PERSONAL_LOAN_SEPARATION_OF_DUTIES", new RegulatoryRequirementDefinition("第二十一条", "审贷分离")),
            Map.entry("PERSONAL_LOAN_INTERNAL_PROCEDURES", new RegulatoryRequirementDefinition("第五十条", "管理细则及操作规程")));
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");

    private final Config config;
    private final SmartAuditModelPort modelPort;
    private final SmartAuditResultMapper resultMapper;
    private final PersonalLoanDeterministicEvaluator personalLoanEvaluator;
    private final EnterpriseCreditDeterministicEvaluator enterpriseCreditEvaluator;

    public SmartAuditModelProvider(Config config, SmartAuditModelPort modelPort) {
        this.config = Objects.requireNonNull(config, "config");
        this.modelPort = Objects.requireNonNull(modelPort, "modelPort");
        this.resultMapper = new SmartAuditResultMapper();
        this.personalLoanEvaluator = new PersonalLoanDeterministicEvaluator();
        this.enterpriseCreditEvaluator = new EnterpriseCreditDeterministicEvaluator();
    }

    @Override
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, PROVIDER_ID, PROVIDER_VERSION,
                SmartAuditCapabilityCatalog.descriptorCapabilities());
    }

    @Override
    public void checkAccess(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        SmartAuditCapabilityCatalog.TaskDefinition task = SmartAuditCapabilityCatalog.require(request.capabilityId());
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) throw new SecurityException("QUERY_OPERATION_REQUIRED");
        FinanceContextV1 context = request.context();
        if (context.principalType() != FinanceContextV1.PrincipalType.EMPLOYEE
                || !task.purpose().equals(context.purpose())
                || context.subjectRef() == null || context.sessionRef() == null
                || !context.scopes().contains(task.requiredScope())) {
            throw new SecurityException("SMART_AUDIT_CONTEXT_REJECTED");
        }
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return failure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "Smart Audit capabilities are query-only.", List.of("USE_QUERY_OPERATION"));
        }
        final SmartAuditCapabilityCatalog.TaskDefinition task;
        try {
            task = SmartAuditCapabilityCatalog.require(request.capabilityId());
        } catch (IllegalArgumentException unsupported) {
            return failure(request, "INVALID_INPUT", "CAPABILITY_NOT_SUPPORTED", false,
                    "The requested Smart Audit capability is not installed.", List.of("SELECT_VISIBLE_TASK"));
        }
        final Baseline baseline;
        try {
            baseline = task.personal() ? validatePersonalBaseline() : Baseline.empty();
        } catch (IllegalStateException invalidAsset) {
            return failure(request, "PERMANENT_FAILURE", "PERSONAL_RULE_BASELINE_INVALID", false,
                    "The fixed personal rule baseline is unavailable or has drifted.", List.of("REBUILD_CANDIDATE"));
        }
        try {
            checkAccess(request);
        } catch (SecurityException denied) {
            return failure(request, "NOT_AUTHORIZED", "SMART_AUDIT_CONTEXT_REJECTED", false,
                    "The current audit session is not authorized for this task.", List.of("REESTABLISH_AUDIT_SESSION"));
        }
        final List<SmartAuditModelRequest.Material> materials;
        try {
            materials = parseMaterials(request.parameters(), task);
        } catch (MissingMaterials missing) {
            return resultMapper.needUser(request, missing.categories());
        } catch (IllegalArgumentException invalidMaterials) {
            return failure(request, "INVALID_INPUT", "MATERIALS_INVALID", false,
                    "Submitted materials do not satisfy the selected task contract.", List.of("CORRECT_MATERIALS"));
        }
        if (task.personal()) {
            List<String> missingBankParameters = missingBankParameters(baseline);
            PersonalLoanDeterministicEvaluator.Evaluation evaluation = personalLoanEvaluator.evaluate(
                    baseline.regulatoryPolicyVersion(), task, materials, missingBankParameters);
            SmartAuditModelResponse explanation = missingBankParameters.isEmpty()
                    ? renderPersonalExplanation(task, baseline, evaluation) : null;
            return resultMapper.mapDeterministicPersonal(request, task, baseline.summary(task.ruleSubset()), evaluation, explanation);
        }
        EnterpriseCreditDeterministicEvaluator.Evaluation evaluation = enterpriseCreditEvaluator.evaluate(task, materials);
        return resultMapper.mapDeterministicEnterprise(request, task, evaluation);
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        return failure(request, "INVALID_INPUT", "TASK_NOT_SUPPORTED", false,
                "Smart Audit does not execute financial tasks.", List.of("USE_QUERY_OPERATION"));
    }

    private Baseline validatePersonalBaseline() {
        Path path = config.personalRuleBaseline();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("baseline missing");
        try {
            JsonNode root = HarnessJson.mapper().readTree(Files.readString(path));
            if (root == null || !root.isObject()
                    || !"personal-rule-baseline.v1".equals(root.path("schemaVersion").asText())) {
                throw new IllegalStateException("baseline schema");
            }
            String baselineVersion = requiredText(root, "version", "baseline version");
            RegulatoryPolicy regulatoryPolicy = validatePersonalRegulatoryPolicy(
                    path.resolveSibling(PERSONAL_REGULATORY_POLICY_FILE));
            if (!PERSONAL_REGULATORY_POLICY_FILE.equals(root.path("regulatoryPolicy").asText())
                    || containsText(root, REPEALED_PERSONAL_LOAN_INTERIM_MEASURES)
                    || !validBaselineThresholdPolicy(root.path("bankSpecificThresholdPolicy"), regulatoryPolicy)) {
                throw new IllegalStateException("baseline regulatory reference");
            }
            JsonNode components = root.path("components");
            JsonNode taskToSubset = root.path("taskToSubset");
            if (!components.isObject() || !taskToSubset.isObject()) throw new IllegalStateException("baseline sections");
            Map<String, Object> values = new LinkedHashMap<>();
            for (String component : BASELINE_COMPONENTS) {
                JsonNode value = components.path(component);
                if (!value.isObject() || value.path("source").asText().isBlank()
                        || !SHA256.matcher(value.path("sha256").asText()).matches()) {
                    throw new IllegalStateException("baseline component");
                }
                values.put(component, HarnessJson.mapper().convertValue(value, Map.class));
            }
            Map<String, List<String>> mappings = new LinkedHashMap<>();
            for (SmartAuditCapabilityCatalog.TaskDefinition task : SmartAuditCapabilityCatalog.tasks().stream()
                    .filter(SmartAuditCapabilityCatalog.TaskDefinition::personal).toList()) {
                JsonNode subset = taskToSubset.path(task.capabilityId());
                if (!subset.isArray() || subset.isEmpty()) throw new IllegalStateException("baseline task mapping");
                List<String> names = new ArrayList<>();
                for (JsonNode name : subset) if (!BASELINE_COMPONENTS.contains(name.asText())) {
                    throw new IllegalStateException("baseline subset component");
                } else {
                    names.add(name.asText());
                }
                if (!names.equals(task.ruleSubset())) throw new IllegalStateException("baseline task mapping drift");
                mappings.put(task.capabilityId(), List.copyOf(names));
            }
            return new Baseline(Map.copyOf(values), Map.copyOf(mappings),
                    baselineVersion, regulatoryPolicy.version(),
                    regulatoryPolicy.requiredBankParameterIds());
        } catch (IOException exception) {
            throw new IllegalStateException("baseline unreadable", exception);
        }
    }

    private static RegulatoryPolicy validatePersonalRegulatoryPolicy(Path path) {
        if (!Files.isRegularFile(path)) throw new IllegalStateException("regulatory policy missing");
        try {
            JsonNode root = HarnessJson.mapper().readTree(Files.readString(path));
            if (root == null || !root.isObject()
                    || !PERSONAL_REGULATORY_POLICY_SCHEMA.equals(root.path("schemaVersion").asText())) {
                throw new IllegalStateException("regulatory policy schema");
            }
            JsonNode regulation = root.path("regulation");
            if (!regulation.isObject()
                    || !NFRA_2024_PERSONAL_LOAN_TITLE.equals(regulation.path("title").asText())
                    || !NFRA_ISSUER.equals(regulation.path("issuer").asText())
                    || !NFRA_2024_PERSONAL_LOAN_ORDER.equals(regulation.path("order").asText())
                    || !NFRA_2024_PERSONAL_LOAN_DOCUMENT_ID.equals(regulation.path("documentId").asText())
                    || !NFRA_2024_PERSONAL_LOAN_EFFECTIVE_DATE.equals(regulation.path("effectiveDate").asText())
                    || !NFRA_2024_PERSONAL_LOAN_SOURCE_URL.equals(regulation.path("sourceUrl").asText())
                    || root.path("version").asText().isBlank()) {
                throw new IllegalStateException("regulatory policy metadata");
            }
            Map<String, JsonNode> requirements = indexedObjects(root.path("regulatedRequirements"), "requirementId");
            if (!requirements.keySet().equals(REQUIRED_REGULATORY_REQUIREMENTS.keySet())) {
                throw new IllegalStateException("regulatory requirements");
            }
            for (Map.Entry<String, RegulatoryRequirementDefinition> expected : REQUIRED_REGULATORY_REQUIREMENTS.entrySet()) {
                JsonNode requirement = requirements.get(expected.getKey());
                RegulatoryRequirementDefinition definition = expected.getValue();
                if (!definition.article().equals(requirement.path("article").asText())
                        || !requirement.path("requirement").asText().contains(definition.requiredContent())) {
                    throw new IllegalStateException("regulatory requirement content");
                }
            }
            boolean declaresRepeal = false;
            JsonNode repealed = root.path("repealedReferences");
            if (repealed.isArray()) for (JsonNode reference : repealed) {
                if (REPEALED_PERSONAL_LOAN_INTERIM_MEASURES.equals(reference.path("title").asText())
                        && "中国银行业监督管理委员会".equals(reference.path("issuer").asText())
                        && "中国银行业监督管理委员会令2010年第2号".equals(reference.path("order").asText())
                        && "REPEALED".equals(reference.path("status").asText())
                        && REPEALED_BY_NFRA_2024_PERSONAL_LOAN_ORDER_ARTICLE_52.equals(reference.path("repealedBy").asText())) {
                    declaresRepeal = true;
                }
            }
            if (!declaresRepeal) throw new IllegalStateException("repealed reference");
            JsonNode parameters = root.path("bankSpecificParameters");
            if (!parameters.isArray() || parameters.isEmpty()) throw new IllegalStateException("bank parameters");
            Set<String> parameterIds = textIds(parameters, "parameterId");
            for (JsonNode parameter : parameters) {
                if (parameter.path("parameterId").asText().isBlank()
                        || !"REQUIRED_CONFIGURATION".equals(parameter.path("configurationStatus").asText())
                        || parameter.path("maySupportPositiveOutcome").asBoolean(true)) {
                    throw new IllegalStateException("bank parameter control");
                }
            }
            return new RegulatoryPolicy(root.path("version").asText(), parameterIds);
        } catch (IOException exception) {
            throw new IllegalStateException("regulatory policy unreadable", exception);
        }
    }

    private static Set<String> textIds(JsonNode values, String field) {
        if (!values.isArray()) throw new IllegalStateException("regulatory policy values");
        Set<String> result = new java.util.HashSet<>();
        for (JsonNode value : values) {
            String id = value.path(field).asText();
            if (id.isBlank() || !result.add(id)) throw new IllegalStateException("regulatory policy identifier");
        }
        return Set.copyOf(result);
    }

    private static String requiredText(JsonNode object, String field, String error) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalStateException(error);
        return value.asText().trim();
    }

    private static Map<String, JsonNode> indexedObjects(JsonNode values, String field) {
        if (!values.isArray()) throw new IllegalStateException("regulatory policy values");
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : values) {
            String id = value.path(field).asText();
            if (!value.isObject() || id.isBlank() || result.put(id, value) != null) {
                throw new IllegalStateException("regulatory policy identifier");
            }
        }
        return Map.copyOf(result);
    }

    private static boolean validBaselineThresholdPolicy(JsonNode policy, RegulatoryPolicy regulatoryPolicy) {
        if (!policy.isObject()
                || !"REQUIRED_CONFIGURATION".equals(policy.path("configurationStatus").asText())
                || policy.path("maySupportPositiveOutcome").asBoolean(true)) return false;
        return textValues(policy.path("requiredParameterIds")).equals(regulatoryPolicy.requiredBankParameterIds());
    }

    private static Set<String> textValues(JsonNode values) {
        if (!values.isArray()) throw new IllegalStateException("baseline threshold parameter identifiers");
        Set<String> result = new java.util.HashSet<>();
        for (JsonNode value : values) {
            String id = value.asText();
            if (!value.isTextual() || id.isBlank() || !result.add(id)) {
                throw new IllegalStateException("baseline threshold parameter identifier");
            }
        }
        return Set.copyOf(result);
    }

    private List<String> missingBankParameters(Baseline baseline) {
        return baseline.requiredBankParameterIds().stream()
                .filter(parameterId -> !isApproved(config.bankSpecificParameters().get(parameterId)))
                .sorted()
                .toList();
    }

    private static boolean isApproved(BankPolicyParameter parameter) {
        return parameter != null && parameter.approved() && !parameter.policyVersion().isBlank()
                && parameter.effectiveDate() != null && !parameter.effectiveDate().isAfter(LocalDate.now(Clock.systemUTC()))
                && validBankParameterValue(parameter.value());
    }

    /** The optional model receives only deterministic findings and de-identified evidence locators. */
    private SmartAuditModelResponse renderPersonalExplanation(SmartAuditCapabilityCatalog.TaskDefinition task,
                                                               Baseline baseline,
                                                               PersonalLoanDeterministicEvaluator.Evaluation evaluation) {
        try {
            SmartAuditModelRequest explanationRequest = new SmartAuditModelRequest(
                    task.capabilityId(), task.taskId(), task.skillId(), config.modelVersion(),
                    baseline.regulatoryPolicyVersion(), evaluation.ruleOutcomes(),
                    evaluation.evidence().stream().map(evidence -> new SmartAuditModelRequest.EvidenceLocator(
                            evidence.sourceCategory(), evidence.locator())).toList(),
                    List.of("MANUAL_REVIEW_REQUIRED"), evaluation.missingBankParameterIds(), task.outputSections());
            return modelPort.assess(explanationRequest);
        } catch (RuntimeException ignored) {
            // A model outage must not erase authoritative evidence/rule results or block human review.
            return null;
        }
    }

    private static boolean validBankParameterValue(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("type", "value"))) return false;
        if (!(map.get("type") instanceof String type) || type.isBlank()) return false;
        Object rawValue = map.get("value");
        return switch (type) {
            case "NUMBER" -> finiteNumber(rawValue);
            case "TEXT" -> rawValue instanceof String text && !text.isBlank();
            default -> false;
        };
    }

    private static boolean finiteNumber(Object value) {
        if (!(value instanceof Number)) return false;
        if (value instanceof Double floating) return Double.isFinite(floating);
        if (value instanceof Float floating) return Float.isFinite(floating);
        return true;
    }

    private static boolean containsText(JsonNode node, String prohibited) {
        if (node.isTextual()) return node.asText().contains(prohibited);
        if (node.isContainerNode()) for (JsonNode child : node) if (containsText(child, prohibited)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<SmartAuditModelRequest.Material> parseMaterials(Map<String, Object> parameters,
                                                                          SmartAuditCapabilityCatalog.TaskDefinition task) {
        Object raw = parameters.get("materials");
        if (!(raw instanceof List<?> submitted) || submitted.isEmpty()) throw new MissingMaterials(task.materialCategories());
        List<SmartAuditModelRequest.Material> materials = new ArrayList<>();
        Set<String> categories = new java.util.HashSet<>();
        for (Object item : submitted) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("material must be an object");
            if (!map.keySet().equals(Set.of("category", "content", "declaredFormat"))) {
                throw new IllegalArgumentException("material control fields are forbidden");
            }
            String category = text(map.get("category"));
            String content = text(map.get("content"));
            String format = text(map.get("declaredFormat"));
            if (!task.materialCategories().contains(category)) throw new IllegalArgumentException("category not permitted");
            if (!("OCR_TEXT".equals(format) || "TEXT".equals(format) || "JSON".equals(format))) {
                throw new IllegalArgumentException("format not permitted");
            }
            if ("JSON".equals(format)) {
                try {
                    JsonNode json = HarnessJson.mapper().readTree(content);
                    if (json == null || !(json.isObject() || json.isArray())) throw new IllegalArgumentException("JSON object or array required");
                } catch (IOException invalidJson) {
                    throw new IllegalArgumentException("invalid JSON", invalidJson);
                }
            }
            materials.add(new SmartAuditModelRequest.Material(category, content, format));
            categories.add(category);
        }
        if (task.personal() && !categories.containsAll(task.materialCategories())) {
            List<String> missing = task.materialCategories().stream().filter(category -> !categories.contains(category)).toList();
            throw new MissingMaterials(missing);
        }
        return List.copyOf(materials);
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("material field required");
        return text.trim();
    }

    private CapabilityResultV1 failure(CapabilityRequestV1 request, String failureClass, String sourceCode,
                                       boolean retryable, String message, List<String> nextActions) {
        return resultMapper.failure(request, failureClass, sourceCode, retryable, message, nextActions);
    }

    public record Config(Path personalRuleBaseline, String modelVersion,
                         Map<String, BankPolicyParameter> bankSpecificParameters) {
        public Config(Path personalRuleBaseline, String modelVersion) {
            this(personalRuleBaseline, modelVersion, Map.of());
        }

        public Config {
            personalRuleBaseline = Objects.requireNonNull(personalRuleBaseline, "personalRuleBaseline").toAbsolutePath().normalize();
            if (modelVersion == null || modelVersion.isBlank()) throw new IllegalArgumentException("modelVersion is required");
            modelVersion = modelVersion.trim();
            bankSpecificParameters = bankSpecificParameters == null ? Map.of() : Map.copyOf(bankSpecificParameters);
        }
    }

    /** A bank-approved parameter value; public regulatory assets never provide these values. */
    public record BankPolicyParameter(String policyVersion, LocalDate effectiveDate, Object value, boolean approved) {
        public BankPolicyParameter {
            policyVersion = policyVersion == null ? "" : policyVersion.trim();
        }
    }

    private record Baseline(Map<String, Object> components, Map<String, List<String>> taskToSubset,
                            String version, String regulatoryPolicyVersion, Set<String> requiredBankParameterIds) {
        static Baseline empty() { return new Baseline(Map.of(), Map.of(), "", "", Set.of()); }

        Map<String, Object> subset(List<String> names) {
            Map<String, Object> subset = new LinkedHashMap<>();
            for (String name : names) subset.put(name, components.get(name));
            return Map.copyOf(subset);
        }

        Map<String, Object> summary(List<String> names) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("version", version);
            summary.put("regulatoryPolicyVersion", regulatoryPolicyVersion);
            summary.put("components", names);
            return Map.copyOf(summary);
        }
    }

    private record RegulatoryPolicy(String version, Set<String> requiredBankParameterIds) { }

    private record RegulatoryRequirementDefinition(String article, String requiredContent) { }

    private static final class MissingMaterials extends IllegalArgumentException {
        private final List<String> categories;
        MissingMaterials(List<String> categories) { this.categories = List.copyOf(categories); }
        List<String> categories() { return categories; }
    }
}
