package io.github.bitlhk.finance.harness.core;

import java.time.Instant;

/** Minimal evidence needed to replay and explain one provider invocation. */
public record EvidenceReceiptV1(
        String schemaVersion,
        String receiptId,
        String requestId,
        String traceId,
        String capabilityId,
        CapabilityRequestV1.Operation operation,
        String providerId,
        String sourceSystem,
        CapabilityResultV1.Status resultStatus,
        String taskRef,
        Instant startedAt,
        Instant finishedAt) {

    public static final String SCHEMA_VERSION = "evidence-receipt.v1";

    public EvidenceReceiptV1 {
        ContractChecks.schemaVersion(schemaVersion, SCHEMA_VERSION);
        receiptId = ContractChecks.requiredText(receiptId, "receiptId");
        requestId = ContractChecks.requiredText(requestId, "requestId");
        traceId = ContractChecks.requiredText(traceId, "traceId");
        capabilityId = ContractChecks.requiredText(capabilityId, "capabilityId");
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        providerId = ContractChecks.requiredText(providerId, "providerId");
        sourceSystem = ContractChecks.requiredText(sourceSystem, "sourceSystem");
        if (resultStatus == null) {
            throw new IllegalArgumentException("resultStatus is required");
        }
        taskRef = ContractChecks.optionalText(taskRef);
        if (startedAt == null || finishedAt == null) {
            throw new IllegalArgumentException("startedAt and finishedAt are required");
        }
        if (finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt cannot be before startedAt");
        }
    }
}
