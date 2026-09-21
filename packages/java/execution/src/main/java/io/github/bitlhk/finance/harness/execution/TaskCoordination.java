package io.github.bitlhk.finance.harness.execution;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;

/** Host-owned journal coordination; no Runtime scheduling or bank state machine. */
public final class TaskCoordination {
    public static final String HANDLE_ARGUMENT = "financeTaskHandle";
    public static final String ATTEMPTED = "financeHarness.attempted";

    /** Host-authenticated event. Constructing this value is not proof of user authorization. */
    public record Approval(String eventId, String bindingFingerprint, Instant confirmedAt, Instant expiresAt) {
        public Approval { TaskConfirmationPolicy.validate(eventId, bindingFingerprint, confirmedAt, expiresAt); }
        public boolean validFor(String binding, Instant now) {
            return TaskConfirmationPolicy.validFor(bindingFingerprint, confirmedAt, expiresAt, binding, now);
        }
    }

    /** The adapter owns the response representation; only checkpoint and access refusal drive coordination. */
    public record Outcome(Map<String, Object> response, Map<String, Object> checkpoint, boolean accessDenied) {
        public Outcome {
            response = Map.copyOf(response);
            checkpoint = freeze(checkpoint);
        }
    }

    public record Saved(String scope, String capabilityId, String taskId, Map<String, Object> arguments,
                        Map<String, Object> checkpoint, Map<String, Object> terminalResponse) {
        public Saved {
            arguments = freeze(arguments);
            checkpoint = freeze(checkpoint);
            terminalResponse = terminalResponse == null ? null : Map.copyOf(terminalResponse);
        }
    }

    /** Must serialize participating processes and durably commit each put BEFORE returning from put. */
    public interface Journal {
        <T> T locked(Function<Access, T> action);
    }
    public interface Access {
        Saved get(String handle);
        void put(String handle, Saved task);
    }
    public static final class NewTaskRejected extends IllegalStateException {
        public NewTaskRejected() { super("TASK_PENDING_USE_ORIGINAL"); }
    }
    public interface Backend {
        Map<String, Object> parameters(Map<String, Object> arguments);
        Map<String, Object> initialCheckpoint(String taskId, Map<String, Object> arguments);
        void authorize(Saved task);
        Outcome execute(Saved task, Approval approval);
        Map<String, Object> denied(Saved task);
        Map<String, Object> declined(Saved task);
    }

    private final String scope;
    private final Journal journal;
    private final Map<String, Backend> backends = new LinkedHashMap<>();

    public TaskCoordination(String scope, Journal journal) {
        this.scope = Objects.requireNonNull(scope);
        this.journal = Objects.requireNonNull(journal);
    }
    public void register(String capabilityId, Backend backend) { backends.put(capabilityId, Objects.requireNonNull(backend)); }
    public Map<String, Object> invoke(String capabilityId, Map<String, Object> arguments) { return run(capabilityId, arguments, null); }
    public String confirmationBinding(String handle) {
        return journal.locked(access -> {
            Saved saved = owned(access, handle);
            backend(saved).authorize(saved);
            Object binding = saved.checkpoint().get(CK_BINDING);
            if (saved.terminalResponse() != null || !(binding instanceof String text) || text.isBlank())
                throw new IllegalStateException("Task is not awaiting a host confirmation");
            return text;
        });
    }
    public Map<String, Object> confirm(String handle, Approval approval) {
        String capability = journal.locked(access -> owned(access, handle).capabilityId());
        return run(capability, Map.of(HANDLE_ARGUMENT, handle), Objects.requireNonNull(approval));
    }
    public Map<String, Object> decline(String handle) {
        return journal.locked(access -> {
            Saved saved = owned(access, handle);
            Backend backend = backend(saved);
            backend.authorize(saved);
            if (saved.terminalResponse() != null) return saved.terminalResponse();
            if (!saved.checkpoint().containsKey(CK_BINDING))
                return error("TASK_NOT_AWAITING_CONFIRMATION", "原任务结果仍需查证，不能作为未提交任务取消。");
            var declined = backend.declined(saved);
            access.put(handle, saved(saved, Map.of(), declined));
            return declined;
        });
    }

    private Map<String, Object> run(String capabilityId, Map<String, Object> current, Approval approval) {
        return journal.locked(access -> {
            Object requested = current.get(HANDLE_ARGUMENT);
            String handle = requested == null ? "" : String.valueOf(requested).trim();
            Saved prior = null;
            Backend backend = backends.get(capabilityId);
            if (!handle.isBlank()) {
                try { prior = owned(access, handle); }
                catch (IllegalArgumentException exception) {
                    return error("FINANCE_TASK_HANDLE_NOT_FOUND", "无法找到有权续办的原任务，请联系人工查证，勿重复提交。");
                }
                if (!capabilityId.equals(prior.capabilityId()))
                    return error("TASK_CAPABILITY_MISMATCH", "此句柄不属于当前业务能力。");
                try { backend(prior).authorize(prior); }
                catch (SecurityException denied) {
                    if (backend == null) throw denied;
                    return backend.denied(prior);
                }
                if (prior.terminalResponse() != null) return prior.terminalResponse();
                Map<String, Object> supplied = backend.parameters(current);
                Map<String, Object> merged = new LinkedHashMap<>(prior.arguments());
                merged.putAll(supplied);
                boolean supplement = canSupplement(prior.checkpoint(), merged);
                for (var field : supplied.entrySet()) {
                    if (!supplement && (!prior.arguments().containsKey(field.getKey())
                            || !fingerprint(field.getValue()).equals(fingerprint(prior.arguments().get(field.getKey())))))
                        return error("TASK_PARAMETERS_CHANGED", "原任务金额、收款人等参数不可在续办时变更。");
                }
                if (supplement) prior = new Saved(prior.scope(), prior.capabilityId(), prior.taskId(), merged, prior.checkpoint(), null);
                else if (!prior.checkpoint().containsKey(CK_TASK_REF) && Boolean.TRUE.equals(prior.checkpoint().get(ATTEMPTED)))
                    return Map.of("status", "NEED_USER", "requiresUserAction", true,
                            "errorCode", "ORIGINAL_TASK_REQUIRES_VERIFICATION", HANDLE_ARGUMENT, handle,
                            "content", "原调用结果未确认且缺少可自动查证的银行引用，请由宿主查证，勿重复创建任务。");
            }
            if (backend == null) return error("CAPABILITY_NOT_INSTALLED", "当前未安装此能力。");
            if (prior == null) {
                handle = "fh-task-handle-" + UUID.randomUUID();
                String taskId = "fh-task-" + UUID.randomUUID();
                Map<String, Object> business = backend.parameters(current);
                prior = new Saved(scope, capabilityId, taskId, business, backend.initialCheckpoint(taskId, business), null);
                try { access.put(handle, prior); }
                catch (NewTaskRejected rejected) {
                    return error("TASK_PENDING_USE_ORIGINAL", "宿主仍保留原待办，请先处理或查证原任务，不能重复创建。");
                }
            }
            if (approval != null && !approval.validFor(String.valueOf(prior.checkpoint().get(CK_BINDING)), Instant.now()))
                return error("HOST_CONFIRMATION_INVALID", "确认已过期或与原任务不一致。");
            try { backend.authorize(prior); }
            catch (SecurityException denied) { return backend.denied(prior); }
            Map<String, Object> attempted = new LinkedHashMap<>(recoveryCheckpoint(prior.checkpoint()));
            attempted.put(ATTEMPTED, true);
            access.put(handle, saved(prior, attempted, null));
            // Each write is durable before the bank call; a crash must never replay approval.
            if (approval != null) access.put(handle, saved(prior, uncertain(recoveryCheckpoint(prior.checkpoint())), null));
            Outcome execution = backend.execute(prior, approval);
            if (execution.accessDenied()) return execution.response();
            if (!execution.checkpoint().isEmpty()) {
                Map<String, Object> checkpoint = new LinkedHashMap<>(execution.checkpoint());
                checkpoint.put(ATTEMPTED, true);
                checkpoint.put(CK_PARAMETERS, prior.arguments());
                if (approval != null) checkpoint = uncertain(checkpoint);
                access.put(handle, saved(prior, checkpoint, null));
                Map<String, Object> response = new LinkedHashMap<>(execution.response());
                response.put(HANDLE_ARGUMENT, handle);
                return Map.copyOf(response);
            }
            access.put(handle, saved(prior, Map.of(), execution.response()));
            return execution.response();
        });
    }

    private static Map<String, Object> uncertain(Map<String, Object> source) {
        Map<String, Object> checkpoint = new LinkedHashMap<>(source);
        checkpoint.remove(CK_BINDING);
        Map<String, Object> continuation = new LinkedHashMap<>();
        if (checkpoint.get(CK_CONTINUATION) instanceof Map<?, ?> existing)
            existing.forEach((key, value) -> continuation.put(String.valueOf(key), value));
        continuation.put("submitOutcomeUnknown", true);
        checkpoint.put(CK_CONTINUATION, Map.copyOf(continuation));
        return Map.copyOf(checkpoint);
    }
    private Backend backend(Saved saved) {
        Backend backend = backends.get(saved.capabilityId());
        if (backend == null) throw new SecurityException("CAPABILITY_NOT_INSTALLED");
        return backend;
    }
    private Saved owned(Access access, String handle) {
        if (handle == null || !handle.matches("fh-task-handle-[a-f0-9-]{36}")) throw new IllegalArgumentException("Unknown task handle");
        Saved saved = access.get(handle);
        if (saved == null || !scope.equals(saved.scope())) throw new IllegalArgumentException("Unknown task handle");
        return saved;
    }
    private static Saved saved(Saved previous, Map<String, Object> checkpoint, Map<String, Object> terminal) {
        return new Saved(previous.scope(), previous.capabilityId(), previous.taskId(), previous.arguments(), checkpoint, terminal);
    }
    private static Map<String, Object> error(String code, String message) {
        return Map.of("status", "REJECTED", "errorCode", code, "content", message, "requiresUserAction", false);
    }
}
