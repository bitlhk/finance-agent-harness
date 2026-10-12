package io.github.bitlhk.finance.harness.providers.smartaudit;

import java.util.List;

/** Fixed-schema, advisory-only model output. Rules, evidence and review requirements are not model fields. */
public record SmartAuditModelResponse(Explanation explanation, String modelVersion) {
    public SmartAuditModelResponse {
        modelVersion = modelVersion == null ? "" : modelVersion.trim();
    }

    /**
     * Legacy constructor retained so an existing Candidate can compile during the coordinated
     * migration. Its old control-like fields are intentionally discarded.
     */
    public SmartAuditModelResponse(String report, String completeness, List<String> missingInputs,
                                   List<String> recommendations, List<EvidenceReference> evidence,
                                   String modelVersion) {
        this(new Explanation(report, recommendations, List.of()), modelVersion);
    }

    /** Fixed JSON payload: narrative, supplementHints and manualReviewHints only. */
    public record Explanation(String narrative, List<String> supplementHints, List<String> manualReviewHints) {
        public Explanation {
            narrative = narrative == null ? "" : narrative.trim();
            supplementHints = supplementHints == null ? List.of() : List.copyOf(supplementHints);
            manualReviewHints = manualReviewHints == null ? List.of() : List.copyOf(manualReviewHints);
        }
    }

    /** Legacy accessors return no control-plane model fields. */
    public String report() { return explanation == null ? "" : explanation.narrative(); }
    public String completeness() { return "不可判断"; }
    public List<String> missingInputs() { return List.of(); }
    public List<String> recommendations() { return List.of(); }
    public List<EvidenceReference> evidence() { return List.of(); }

    public record EvidenceReference(String sourceCategory, String locator, String ruleReference) {
        public EvidenceReference {
            sourceCategory = clean(sourceCategory);
            locator = clean(locator);
            ruleReference = clean(ruleReference);
        }
        private static String clean(String value) { return value == null ? "" : value.trim(); }
    }

}
