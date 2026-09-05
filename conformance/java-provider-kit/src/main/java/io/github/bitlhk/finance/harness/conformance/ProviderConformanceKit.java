package io.github.bitlhk.finance.harness.conformance;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic build-time checks that a bank Provider obeys the Finance Harness SPI. */
public final class ProviderConformanceKit {
    private ProviderConformanceKit() {
    }

    public static Report verify(
            DescribedFinanceProvider provider,
            List<Case> cases) {
        Objects.requireNonNull(provider, "provider");
        ProviderDescriptorV1 descriptor = Objects.requireNonNull(
                provider.descriptor(), "provider descriptor");
        List<CaseResult> results = new ArrayList<>();
        for (Case testCase : cases == null ? List.<Case>of() : cases) {
            results.add(runCase(provider, descriptor, Objects.requireNonNull(testCase, "case")));
        }
        Set<String> declared = new java.util.TreeSet<>();
        descriptor.capabilities().forEach(c -> declared.add(c.capabilityId() + ":" + c.operation()));
        Set<String> tested = new java.util.TreeSet<>();
        if (cases != null) cases.forEach(c -> tested.add(c.request().capabilityId() + ":" + c.request().operation()));
        Set<String> missing = new java.util.TreeSet<>(declared);
        missing.removeAll(tested);
        Status status = !results.isEmpty() && missing.isEmpty()
                && results.stream().allMatch(result -> result.status() == Status.PASS)
                ? Status.PASS
                : Status.FAIL;
        return new Report(
                "finance-provider-conformance.v1",
                descriptor.providerId(),
                descriptor.version(),
                status,
                results, List.copyOf(declared), List.copyOf(tested), List.copyOf(missing));
    }

    private static CaseResult runCase(
            DescribedFinanceProvider provider,
            ProviderDescriptorV1 descriptor,
            Case testCase) {
        CapabilityRequestV1 request = testCase.request();
        List<Finding> findings = new ArrayList<>();
        if (!descriptor.supports(request.capabilityId(), request.operation())) {
            findings.add(new Finding(
                    "CAPABILITY_NOT_DECLARED",
                    request.capabilityId() + ":" + request.operation()
                            + " is not declared by the Provider"));
            return result(testCase, null, findings);
        }

        CapabilityResultV1 response;
        try {
            response = request.operation() == CapabilityRequestV1.Operation.QUERY
                    ? provider.query(request)
                    : provider.executeTask(request);
        } catch (RuntimeException exception) {
            findings.add(new Finding(
                    "PROVIDER_THROWN",
                    "Provider must return a structured failure instead of throwing: "
                            + exception.getClass().getSimpleName()));
            return result(testCase, null, findings);
        }
        if (response == null) {
            findings.add(new Finding("RESULT_MISSING", "Provider returned null"));
            return result(testCase, null, findings);
        }
        if (!request.context().requestId().equals(response.requestId())) {
            findings.add(new Finding("REQUEST_ID_MISMATCH", "Result requestId does not match the request"));
        }
        if (!request.context().traceId().equals(response.traceId())) {
            findings.add(new Finding("TRACE_ID_MISMATCH", "Result traceId does not match the request"));
        }
        if (!testCase.allowedStatuses().contains(response.status())) {
            findings.add(new Finding(
                    "UNEXPECTED_STATUS",
                    "Expected one of " + testCase.allowedStatuses() + " but received " + response.status()));
        }
        if (response.status() == CapabilityResultV1.Status.NEED_CONFIRMATION
                && (response.task() == null || !response.task().confirmationRequired())) {
            findings.add(new Finding(
                    "CONFIRMATION_STATE_MISSING",
                    "NEED_CONFIRMATION requires a task state marked confirmationRequired"));
        }
        if ((response.status() == CapabilityResultV1.Status.PROCESSING
                || response.status() == CapabilityResultV1.Status.UNKNOWN)
                && response.task() == null) {
            findings.add(new Finding(
                    "TASK_STATE_MISSING",
                    response.status() + " requires a Provider-owned task state"));
        }
        return result(testCase, response, findings);
    }

    private static CaseResult result(
            Case testCase,
            CapabilityResultV1 response,
            List<Finding> findings) {
        return new CaseResult(
                testCase.caseId(),
                findings.isEmpty() ? Status.PASS : Status.FAIL,
                response == null ? null : response.status(),
                findings);
    }

    public enum Status {
        PASS,
        FAIL
    }

    public record Case(
            String caseId,
            CapabilityRequestV1 request,
            Set<CapabilityResultV1.Status> allowedStatuses) {
        public Case {
            if (caseId == null || caseId.isBlank()) {
                throw new IllegalArgumentException("caseId is required");
            }
            caseId = caseId.trim();
            Objects.requireNonNull(request, "request");
            allowedStatuses = allowedStatuses == null ? Set.of() : Set.copyOf(allowedStatuses);
            if (allowedStatuses.isEmpty()) {
                throw new IllegalArgumentException("allowedStatuses must not be empty");
            }
        }
    }

    public record Finding(String code, String message) {
        public Finding {
            if (code == null || code.isBlank() || message == null || message.isBlank()) {
                throw new IllegalArgumentException("finding code and message are required");
            }
        }
    }

    public record CaseResult(
            String caseId,
            Status status,
            CapabilityResultV1.Status resultStatus,
            List<Finding> findings) {
        public CaseResult {
            findings = List.copyOf(findings);
        }
    }

    public record Report(
            String schemaVersion,
            String providerId,
            String providerVersion,
            Status status,
            List<CaseResult> cases,
            List<String> declaredCapabilities,
            List<String> testedCapabilities,
            List<String> missingCapabilities) {
        public Report {
            cases = List.copyOf(cases);
        }
    }
}
