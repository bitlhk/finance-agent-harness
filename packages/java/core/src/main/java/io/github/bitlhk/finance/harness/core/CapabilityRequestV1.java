package io.github.bitlhk.finance.harness.core;

import java.time.Instant;
import java.util.Map;

/** A runtime-neutral request to query or execute a financial capability. */
public record CapabilityRequestV1(
        String schemaVersion,
        String capabilityId,
        Operation operation,
        FinanceContextV1 context,
        Map<String, Object> parameters,
        String idempotencyKey,
        String taskRef,
        Map<String, Object> continuation,
        Confirmation confirmation) {

    public static final String SCHEMA_VERSION = "capability-request.v1";

    public CapabilityRequestV1 {
        ContractChecks.schemaVersion(schemaVersion, SCHEMA_VERSION);
        capabilityId = ContractChecks.requiredText(capabilityId, "capabilityId");
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        parameters = ContractChecks.immutableMap(parameters);
        idempotencyKey = ContractChecks.optionalText(idempotencyKey);
        taskRef = ContractChecks.optionalText(taskRef);
        continuation = ContractChecks.immutableMap(continuation);
    }

    public enum Operation {
        QUERY,
        TASK
    }

    /** Explicit user confirmation carried only when resuming a task. */
    public record Confirmation(boolean confirmed, String confirmationText, Instant confirmedAt) {
        public Confirmation {
            confirmationText = ContractChecks.optionalText(confirmationText);
            if (confirmed && (confirmationText == null || confirmedAt == null)) {
                throw new IllegalArgumentException(
                        "confirmed confirmation requires confirmationText and confirmedAt");
            }
        }
    }
}
