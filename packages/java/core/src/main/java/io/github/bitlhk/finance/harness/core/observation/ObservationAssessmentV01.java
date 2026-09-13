package io.github.bitlhk.finance.harness.core.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

/** Deterministic preflight, not a statement that any per-request evidence was captured. */
public record ObservationAssessmentV01(String schemaVersion, Status status, List<Item> items) {
    public enum Status { LEGACY_UNASSESSED, READY, OPTIONAL_GAPS, REJECTED }
    public enum Readiness { READY, UNSUPPORTED, UNKNOWN, UNVERIFIED, DISABLED, NOT_AUTHORIZED, FAILED, MISMATCH }
    public enum Capture { NOT_COLLECTED, CAPTURED, FILTERED, TRUNCATED, FAILED }
    public record Item(Requirement requirement, String collectorId, String collectorVersion,
                       Capability descriptor, Readiness readiness, Capture capture) { }

    public ObservationAssessmentV01 {
        if (!"finance-observation-assessment.v0.1".equals(schemaVersion) || status == null)
            throw new IllegalArgumentException("Observation assessment version/status");
        items = List.copyOf(items);
    }

    public static ObservationAssessmentV01 assess(ObservationRequirementsV01 requirements,
            List<ObservationCapabilitiesV01> collectors, Set<String> installedCapabilities) {
        if (requirements == null) return result(Status.LEGACY_UNASSESSED, List.of());
        var collectorIds = new java.util.HashSet<String>();
        for (var collector : collectors) if (!collectorIds.add(collector.collectorId()))
            throw new IllegalArgumentException("Duplicate observation collector identity");
        List<Item> items = new ArrayList<>();
        for (var requirement : requirements.requirements()) {
            if (!installedCapabilities.containsAll(requirement.capabilityIds()))
                throw new IllegalArgumentException("Observation requirement references an uninstalled capability");
            var matches = new ArrayList<Item>();
            for (var collector : collectors) for (var capability : collector.capabilities())
                if (requirement.semanticId().equals(capability.semanticId()))
                    matches.add(new Item(requirement, collector.collectorId(), collector.version(), capability,
                            readiness(requirement, capability), Capture.NOT_COLLECTED));
            // Ambiguous claims are not silently combined into fictitious end-to-end coverage.
            if (matches.size() > 1) throw new IllegalArgumentException("Ambiguous observation collector binding");
            items.add(matches.isEmpty() ? new Item(requirement, null, null, null,
                    Readiness.UNKNOWN, Capture.NOT_COLLECTED) : matches.getFirst());
        }
        boolean rejected = items.stream().anyMatch(item -> item.requirement().obligation() == Obligation.REQUIRED
                && item.readiness() != Readiness.READY);
        return result(rejected ? Status.REJECTED : items.stream().anyMatch(item -> item.readiness() != Readiness.READY)
                ? Status.OPTIONAL_GAPS : Status.READY, items);
    }

    public void requireReady() {
        if (status == Status.REJECTED) throw new UnmetRequirements(this);
    }

    public static final class UnmetRequirements extends IllegalStateException {
        private final ObservationAssessmentV01 assessment;
        private UnmetRequirements(ObservationAssessmentV01 assessment) {
            super("OBSERVATION_REQUIREMENTS_UNMET");
            this.assessment = assessment;
        }
        public ObservationAssessmentV01 assessment() { return assessment; }
    }

    private static Readiness readiness(Requirement required, Capability actual) {
        if (!required.semanticVersion().equals(actual.semanticVersion()) || required.boundary() != actual.boundary()
                || !actual.fields().containsAll(required.fields())) return Readiness.MISMATCH;
        if (actual.support() == Support.UNSUPPORTED) return Readiness.UNSUPPORTED;
        if (actual.support() == Support.UNKNOWN) return Readiness.UNKNOWN;
        if (actual.verification() != Verification.VERIFIED) return Readiness.UNVERIFIED;
        if (actual.authorization() == Authorization.NOT_AUTHORIZED) return Readiness.NOT_AUTHORIZED;
        if (actual.authorization() == Authorization.UNKNOWN) return Readiness.UNKNOWN;
        if ((required.obligation() == Obligation.AUTHORIZED_DIAGNOSTIC || required.content() == Content.DIAGNOSTIC_BODY)
                && actual.authorization() != Authorization.AUTHORIZED) return Readiness.NOT_AUTHORIZED;
        return switch (actual.availability()) {
            case ENABLED -> Readiness.READY;
            case DISABLED -> Readiness.DISABLED;
            case FAILED -> Readiness.FAILED;
            case UNKNOWN -> Readiness.UNKNOWN;
        };
    }

    private static ObservationAssessmentV01 result(Status status, List<Item> items) {
        return new ObservationAssessmentV01("finance-observation-assessment.v0.1", status, items);
    }
}
