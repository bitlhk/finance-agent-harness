package io.github.bitlhk.finance.harness.integrations.customermanager;

import java.time.*;
import java.util.*;

/** Session-local one-shot confirmation, never a replacement for execution-time authorization. */
final class ConversationConfirmation {
    record Binding(String owner, String instance, String lockDigest, long epoch, String scopeDigest) {
        Binding {
            if (owner == null || owner.isBlank() || instance == null || instance.isBlank()
                    || lockDigest == null || lockDigest.isBlank() || epoch < 0 || scopeDigest == null || scopeDigest.isBlank())
                throw new IllegalArgumentException("CONVERSATION_BINDING_REQUIRED");
        }
    }
    record Plan(String id, Binding binding, ConversationIntent intent, String originalQuery,
                Map<String,Object> parameters, Instant expiresAt) {
        Plan { parameters=Map.copyOf(parameters); }
    }
    private final Clock clock;
    private Plan pending;
    ConversationConfirmation(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    synchronized Plan propose(Binding binding, ConversationIntent intent, String originalQuery) {
        return propose(binding,intent,originalQuery,Map.of());
    }
    synchronized Plan propose(Binding binding, ConversationIntent intent, String originalQuery, Map<String,Object> parameters) {
        pending = null;
        if (!intent.requiresExecutionConfirmation() || intent.objectRef() == null
                || originalQuery == null || originalQuery.isBlank() || originalQuery.length() > 8000)
            throw new IllegalArgumentException("CONVERSATION_PLAN_INCOMPLETE");
        pending = new Plan(UUID.randomUUID().toString(), Objects.requireNonNull(binding), intent,
                originalQuery, parameters, clock.instant().plusSeconds(300));
        return pending;
    }

    synchronized Plan claim(String id, Binding current, Set<String> currentlyAuthorizedObjects,
            Set<ConversationIntent.Kind> currentlyInstalledActions) {
        Plan plan = pending;
        // A failed confirmation cannot be replayed after authority or context is changed back.
        pending = null;
        if (plan == null || !plan.id().equals(id) || !plan.binding().equals(current)
                || !clock.instant().isBefore(plan.expiresAt())
                || !currentlyAuthorizedObjects.contains(plan.intent().objectRef())
                || !currentlyInstalledActions.contains(plan.intent().kind()))
            throw new SecurityException("CONVERSATION_CONFIRMATION_REJECTED");
        return plan;
    }

    synchronized void invalidate() { pending = null; }
}
