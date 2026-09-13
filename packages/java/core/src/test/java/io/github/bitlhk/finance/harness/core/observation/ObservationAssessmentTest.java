package io.github.bitlhk.finance.harness.core.observation;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationAssessmentV01.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationAssessmentTest {
    @Test void frozenSetsSerializeInCanonicalOrder() throws Exception {
        var mapper = HarnessJson.mapper();
        var requirement = requirements(Obligation.REQUIRED, Content.METADATA);
        assertEquals(List.of("outcome", "requestId"), mapper.convertValue(
                mapper.valueToTree(requirement).at("/requirements/0/fields"), List.class));
        var actual = collector("0.1", Availability.ENABLED, Authorization.AUTHORIZED, Verification.VERIFIED);
        assertEquals(List.of("outcome", "requestId"), mapper.convertValue(
                mapper.valueToTree(actual).at("/capabilities/0/fields"), List.class));
    }
    @Test void sharedSchemaCasesAlsoMatchJavaLoader() throws Exception {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (!java.nio.file.Files.isDirectory(root.resolve("contracts/fixtures/observation"))) root = root.getParent();
        var mapper = HarnessJson.mapper();
        var original = mapper.readTree(root.resolve("contracts/fixtures/observation/readonly-requirements.v0.1.json").toFile());
        var cases = mapper.readTree(root.resolve("contracts/fixtures/observation/requirements-cases.json").toFile());
        for (var item : cases) {
            var value = original.deepCopy();
            if (item.has("field")) ((com.fasterxml.jackson.databind.node.ObjectNode) value).set(item.path("field").asText(), item.get("value"));
            boolean valid;
            try { mapper.treeToValue(value, ObservationRequirementsV01.class); valid = true; }
            catch (Exception invalid) { valid = false; }
            assertEquals(item.path("valid").asBoolean(), valid, item.path("name").asText());
        }
    }
    private ObservationRequirementsV01 requirements(Obligation obligation, Content content) {
        return new ObservationRequirementsV01(ObservationRequirementsV01.VERSION, "readonly-observation", "0.1",
                List.of(new Requirement("execution.history", "0.1", obligation, Boundary.HOST, content,
                        Set.of("requestId", "outcome"), Set.of("account.balance.query"))));
    }
    private ObservationCapabilitiesV01 collector(String semanticVersion, Availability availability,
                                                 Authorization authorization, Verification verification) {
        return new ObservationCapabilitiesV01(ObservationCapabilitiesV01.VERSION, "host.archive", "0.1",
                List.of(new Capability("execution.history", semanticVersion, Boundary.HOST,
                        Set.of("requestId", "outcome"), Support.SUPPORTED, verification,
                        verification == Verification.VERIFIED ? List.of("test:archive-roundtrip") : List.of(),
                        availability, authorization)));
    }
    private ObservationAssessmentV01 check(Obligation obligation, Content content, ObservationCapabilitiesV01 collector) {
        return assess(requirements(obligation, content), List.of(collector), Set.of("account.balance.query"));
    }
    @Test void legacyIsNotVerified() {
        assertEquals(Status.LEGACY_UNASSESSED, assess(null, List.of(), Set.of()).status());
    }
    @Test void readinessIsNotCapture() {
        var result = check(Obligation.REQUIRED, Content.METADATA,
                collector("0.1", Availability.ENABLED, Authorization.NOT_REQUIRED, Verification.VERIFIED));
        assertEquals(Status.READY, result.status());
        assertEquals(Capture.NOT_COLLECTED, result.items().getFirst().capture());
    }
    @Test void disabledRequiredHistoryRejectsBeforeUse() {
        var result = check(Obligation.REQUIRED, Content.METADATA,
                collector("0.1", Availability.DISABLED, Authorization.NOT_REQUIRED, Verification.VERIFIED));
        assertEquals(Readiness.DISABLED, result.items().getFirst().readiness());
        assertThrows(IllegalStateException.class, result::requireReady);
    }
    @Test void optionalFailureDoesNotRejectBusiness() {
        var result = check(Obligation.OPTIONAL, Content.METADATA,
                collector("0.1", Availability.FAILED, Authorization.NOT_REQUIRED, Verification.VERIFIED));
        assertEquals(Status.OPTIONAL_GAPS, result.status());
        assertDoesNotThrow(result::requireReady);
    }
    @Test void requiredBodyCannotBypassAuthorizationWithMetadataPolicy() {
        var result = check(Obligation.REQUIRED, Content.DIAGNOSTIC_BODY,
                collector("0.1", Availability.ENABLED, Authorization.NOT_REQUIRED, Verification.VERIFIED));
        assertEquals(Readiness.NOT_AUTHORIZED, result.items().getFirst().readiness());
        assertEquals(Status.REJECTED, result.status());
    }
    @Test void supportedButUnverifiedIsNotEnough() {
        var result = check(Obligation.REQUIRED, Content.METADATA,
                collector("0.1", Availability.ENABLED, Authorization.AUTHORIZED, Verification.NOT_CHECKED));
        assertEquals(Readiness.UNVERIFIED, result.items().getFirst().readiness());
    }
    @Test void semanticVersionMismatchRejects() {
        assertEquals(Status.REJECTED, check(Obligation.REQUIRED, Content.METADATA,
                collector("9", Availability.ENABLED, Authorization.AUTHORIZED, Verification.VERIFIED)).status());
    }
    @Test void unsupportedMissingClaimsRemainUnknown() {
        var result = assess(requirements(Obligation.REQUIRED, Content.METADATA), List.of(), Set.of("account.balance.query"));
        assertEquals(Readiness.UNKNOWN, result.items().getFirst().readiness());
        assertEquals(Status.REJECTED, result.status());
    }
    @Test void fakeVerificationWithoutEvidenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Capability("execution.history", "0.1", Boundary.HOST,
                Set.of("requestId"), Support.SUPPORTED, Verification.VERIFIED, List.of(),
                Availability.ENABLED, Authorization.AUTHORIZED));
    }
    @Test void uninstalledCapabilityAndAmbiguousCollectorsReject() {
        var requirements = requirements(Obligation.REQUIRED, Content.METADATA);
        assertThrows(IllegalArgumentException.class, () -> assess(requirements, List.of(), Set.of()));
        var collector = collector("0.1", Availability.ENABLED, Authorization.AUTHORIZED, Verification.VERIFIED);
        assertThrows(IllegalArgumentException.class, () -> assess(requirements, List.of(collector, collector), Set.of("account.balance.query")));
    }
    @Test void strictUnknownExtensionRejected() throws Exception {
        var mapper = HarnessJson.mapper();
        var tree = mapper.valueToTree(requirements(Obligation.REQUIRED, Content.METADATA));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree).put("trusted", true);
        assertThrows(Exception.class, () -> mapper.treeToValue(tree, ObservationRequirementsV01.class));
    }
}
