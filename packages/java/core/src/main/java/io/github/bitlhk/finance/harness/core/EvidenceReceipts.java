package io.github.bitlhk.finance.harness.core;

import java.time.Instant;
import java.util.UUID;

/** Creates a minimal receipt from one completed provider invocation. */
public final class EvidenceReceipts {
    private EvidenceReceipts() {
    }

    public static EvidenceReceiptV1 from(
            CapabilityRequestV1 request,
            CapabilityResultV1 result,
            String providerId,
            Instant startedAt,
            Instant finishedAt) {
        if (request == null || result == null) {
            throw new IllegalArgumentException("request and result are required");
        }
        if (!request.context().requestId().equals(result.requestId())
                || !request.context().traceId().equals(result.traceId())) {
            throw new IllegalArgumentException("result identity must match request context");
        }
        String taskRef = result.task() == null ? request.taskRef() : result.task().taskRef();
        return new EvidenceReceiptV1(
                EvidenceReceiptV1.SCHEMA_VERSION,
                "receipt-" + UUID.randomUUID(),
                result.requestId(),
                result.traceId(),
                request.capabilityId(),
                request.operation(),
                providerId,
                result.sourceSystem(),
                result.status(),
                taskRef,
                startedAt,
                finishedAt);
    }
}
