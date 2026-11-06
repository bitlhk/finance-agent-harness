package io.github.bitlhk.finance.harness.taskinteraction;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Current Host authority, not an identity parsed from a model request or retained history. */
public final class TaskInteractionAccess {
    public record Authority(String principalId, String customerId, String taskRef, Set<String> roles,
            String grantVersion, Instant validUntil) {
        public Authority {
            for (var id : new String[]{principalId, customerId, taskRef, grantVersion}) TaskInteractionMapper.id(id);
            roles = Set.copyOf(roles); Objects.requireNonNull(validUntil);
            if (roles.isEmpty() || !Set.of("reader", "operator", "reviewer", "executor").containsAll(roles)) TaskInteractionMapper.invalid();
        }
    }
    private final String customer;
    private final String task;
    private final Supplier<Authority> current;
    private final Supplier<Instant> clock;
    public TaskInteractionAccess(String customer, String task, Supplier<Authority> current, Supplier<Instant> clock) {
        TaskInteractionMapper.id(customer); TaskInteractionMapper.id(task);
        this.customer=customer; this.task=task; this.current=Objects.requireNonNull(current); this.clock=Objects.requireNonNull(clock);
    }
    public Authority require(String caller, String action) {
        Authority value;
        try { value=current.get(); }
        catch (Exception failure) { throw new SecurityException("TASK_AUTHORITY_UNAVAILABLE"); }
        if (value==null || !value.principalId().equals(caller) || !customer.equals(value.customerId())
                || !task.equals(value.taskRef()) || !clock.get().isBefore(value.validUntil())) throw new SecurityException("TASK_ACCESS_DENIED");
        if (action!=null && !value.roles().contains(TaskInteractionActions.role(action))) throw new SecurityException("TASK_ACTION_DENIED");
        return value;
    }
}
