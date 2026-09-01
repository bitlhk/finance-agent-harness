package io.github.bitlhk.finance.harness.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A normalized business result returned by a financial capability provider. */
public record CapabilityResultV1(
        String schemaVersion,
        String requestId,
        String traceId,
        Status status,
        String content,
        Map<String, Object> data,
        List<String> missingInputs,
        List<String> nextActions,
        String sourceSystem,
        Instant asOf,
        TaskState task,
        Failure error) {

    public static final String SCHEMA_VERSION = "capability-result.v1";

    public CapabilityResultV1 {
        ContractChecks.schemaVersion(schemaVersion, SCHEMA_VERSION);
        requestId = ContractChecks.requiredText(requestId, "requestId");
        traceId = ContractChecks.requiredText(traceId, "traceId");
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        content = content == null ? "" : content;
        data = ContractChecks.immutableMap(data);
        missingInputs = ContractChecks.immutableList(missingInputs);
        nextActions = ContractChecks.immutableList(nextActions);
        sourceSystem = ContractChecks.requiredText(sourceSystem, "sourceSystem");
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        if (status == Status.FAILED && error == null) {
            throw new IllegalArgumentException("FAILED result requires error");
        }
    }

    public enum Status {
        COMPLETED,
        NEED_USER,
        NEED_CONFIRMATION,
        PROCESSING,
        FAILED,
        UNKNOWN
    }

    /** Provider-owned continuation state. The Harness transports it without interpreting bank rules. */
    public record TaskState(
            String taskRef,
            String status,
            boolean confirmationRequired,
            Map<String, Object> continuation) {
        public TaskState {
            taskRef = ContractChecks.requiredText(taskRef, "task.taskRef");
            status = ContractChecks.requiredText(status, "task.status");
            continuation = ContractChecks.immutableMap(continuation);
        }
    }

    public record Failure(
            String failureClass,
            String sourceCode,
            boolean retryable,
            String message,
            List<String> fieldErrors) {
        public Failure {
            failureClass = ContractChecks.requiredText(failureClass, "error.failureClass");
            sourceCode = ContractChecks.requiredText(sourceCode, "error.sourceCode");
            message = ContractChecks.requiredText(message, "error.message");
            fieldErrors = ContractChecks.immutableList(fieldErrors);
        }
    }
}
