package io.github.bitlhk.finance.harness.execution;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceipts;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.InputCollectionProvider;
import io.github.bitlhk.finance.harness.core.spi.InvocationAccess;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;

/** Single authorized TASK attempt. Use TaskCoordination for durable confirmation and recovery. */
public final class ProviderTaskExecution {
    public enum Decision { BUSINESS_RESULT, OWNER_MISMATCH, PARAMETERS_CHANGED, CONFIRMATION_INVALID, ACCESS_DENIED, UNOBSERVED }
    public record Invocation(String taskId, String capabilityId, String ownerBinding,
                             Map<String, Object> arguments, Map<String, Object> checkpoint) {
        public Invocation { arguments = freeze(arguments); checkpoint = freeze(checkpoint); }
    }
    public record Outcome(Decision decision, CapabilityRequestV1 request, CapabilityResultV1 result,
                          Map<String, Object> checkpoint, boolean observationRequired, Instant startedAt, Instant finishedAt) {
        public Outcome { checkpoint = Map.copyOf(checkpoint); }
    }
    private ProviderTaskExecution() { }

    /** The supplier must rebuild current trusted context/scopes, not cache a previous authorization. */
    public static Outcome execute(FinanceProvider provider, Supplier<CapabilityRequestV1> requests,
                                  Invocation invocation, TaskCoordination.Approval approval) {
        Instant started = Instant.now();
        var prior = invocation.checkpoint();
        try {
            var accessRequest = requireTask(requests.get(), invocation.capabilityId());
            if (provider instanceof InvocationAccess access) access.checkAccess(accessRequest);
            if (!matchesOwner(prior, invocation.capabilityId(), invocation.ownerBinding()))
                return local(Decision.OWNER_MISMATCH, started, Map.of());
            if (!acceptsParameters(prior, TaskProtocol.parameters(invocation.arguments())))
                return local(Decision.PARAMETERS_CHANGED, started, Map.of());
            Object binding = prior.get(CK_BINDING);
            if (approval != null && !approval.validFor(binding == null ? "" : String.valueOf(binding).trim(), started))
                return local(Decision.CONFIRMATION_INVALID, started, Map.of());
            var request = TaskProtocol.request(requireTask(requests.get(), invocation.capabilityId()), invocation.taskId(),
                    invocation.arguments(), prior, approval);
            var result = provider.executeTask(request);
            EvidenceReceipts.requireMatchingIdentity(request, result);
            Map<String, Object> checkpoint = Map.of();
            boolean recovery = false;
            if (result.status() != CapabilityResultV1.Status.COMPLETED) {
                if (TaskProtocol.pending(result)) checkpoint = TaskProtocol.checkpoint(result, request, prior,
                        invocation.ownerBinding(), provider instanceof InputCollectionProvider collector ? collector : null);
                else if (TaskProtocol.retainForObservation(result, prior)) { checkpoint = recoveryCheckpoint(prior); recovery = true; }
            }
            return new Outcome(Decision.BUSINESS_RESULT, request, result, checkpoint, recovery, started, Instant.now());
        } catch (SecurityException denied) {
            return local(Decision.ACCESS_DENIED, started, Map.of());
        } catch (RuntimeException failure) {
            return local(Decision.UNOBSERVED, started, prior.isEmpty() ? Map.of() : recoveryCheckpoint(prior));
        }
    }
    private static CapabilityRequestV1 requireTask(CapabilityRequestV1 request, String capabilityId) {
        if (request.operation() != CapabilityRequestV1.Operation.TASK || !capabilityId.equals(request.capabilityId()))
            throw new SecurityException("TASK_REQUEST_BINDING_MISMATCH");
        return request;
    }
    private static Outcome local(Decision decision, Instant started, Map<String, Object> checkpoint) {
        return new Outcome(decision, null, null, checkpoint, decision == Decision.UNOBSERVED && !checkpoint.isEmpty(), started, Instant.now());
    }
}
