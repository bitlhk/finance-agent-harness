package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.execution.TaskConfirmationPolicy;
import java.time.Instant;

/** Supplied only by an authenticated host confirmation handler, never by a model tool. */
public record HostTaskConfirmation(
        String eventId,
        String bindingFingerprint,
        Instant confirmedAt,
        Instant expiresAt) {
    public HostTaskConfirmation {
        TaskConfirmationPolicy.validate(eventId, bindingFingerprint, confirmedAt, expiresAt);
    }

    boolean validFor(String binding, Instant now) {
        return TaskConfirmationPolicy.validFor(bindingFingerprint, confirmedAt, expiresAt, binding, now);
    }
}
