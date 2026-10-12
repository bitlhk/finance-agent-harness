package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Immutable, task-bound input passed to a host-injected explanation model.
 *
 * <p>The model boundary intentionally carries only task metadata, a policy version, authoritative
 * rule outcomes, de-identified evidence locators, risks, and supplement hints. It never retains
 * uploaded document content.</p>
 */
public record SmartAuditModelRequest(
        String capabilityId,
        String taskId,
        String skillId,
        String modelVersion,
        String policyVersion,
        List<AuditRuleOutcome> ruleOutcomes,
        List<EvidenceLocator> evidenceLocators,
        List<String> riskHints,
        List<String> supplementHints,
        List<String> outputSections) {
    public SmartAuditModelRequest {
        capabilityId = required(capabilityId, "capabilityId");
        taskId = required(taskId, "taskId");
        skillId = required(skillId, "skillId");
        modelVersion = required(modelVersion, "modelVersion");
        policyVersion = required(policyVersion, "policyVersion");
        ruleOutcomes = ruleOutcomes == null ? List.of() : List.copyOf(ruleOutcomes);
        evidenceLocators = evidenceLocators == null ? List.of() : List.copyOf(evidenceLocators);
        riskHints = strings(riskHints, "riskHints");
        supplementHints = strings(supplementHints, "supplementHints");
        outputSections = strings(outputSections, "outputSections");
    }

    /**
     * Compatibility constructor for pre-professionalization call sites. Material bodies and rule
     * assets are converted immediately into locators and a policy version, then discarded.
     */
    public SmartAuditModelRequest(String capabilityId, String taskId, String skillId, String modelVersion,
                                  List<Material> materials, Map<String, Object> ruleSubset,
                                  List<String> outputSections) {
        this(capabilityId, taskId, skillId, modelVersion, policyVersion(ruleSubset), List.of(), locators(materials),
                List.of(), List.of(), outputSections);
    }

    /** There is deliberately no material body available beyond this boundary. */
    public List<Material> materials() {
        return List.of();
    }

    /** Legacy helper returns only the already-declared policy version, never rule assets. */
    public Map<String, Object> ruleSubset() {
        return Map.of("policyVersion", policyVersion);
    }

    /** Material content exists only before construction and is not retained in the model request. */
    public record Material(String category, String content, String declaredFormat) {
        public Material {
            category = required(category, "material.category");
            content = required(content, "material.content");
            declaredFormat = required(declaredFormat, "material.declaredFormat");
        }
    }

    /** A task-local locator, not an uploaded document body or a customer identifier. */
    public record EvidenceLocator(String sourceCategory, String locator) {
        public EvidenceLocator {
            sourceCategory = required(sourceCategory, "evidence.sourceCategory");
            locator = required(locator, "evidence.locator");
        }
    }

    private static List<EvidenceLocator> locators(List<Material> materials) {
        if (materials == null || materials.isEmpty()) return List.of();
        List<EvidenceLocator> result = new ArrayList<>();
        for (int index = 0; index < materials.size(); index++) {
            Material material = materials.get(index);
            if (material == null) throw new IllegalArgumentException("materials must not contain null");
            result.add(new EvidenceLocator(material.category(), material.category() + "#" + (index + 1)));
        }
        return List.copyOf(result);
    }

    private static String policyVersion(Map<String, Object> ruleSubset) {
        if (ruleSubset == null) return "UNVERSIONED";
        Object version = ruleSubset.get("version");
        return version instanceof String value && !value.isBlank() ? value.trim() : "UNVERSIONED";
    }

    private static List<String> strings(List<String> values, String field) {
        if (values == null || values.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) result.add(required(value, field));
        return List.copyOf(result);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
}
