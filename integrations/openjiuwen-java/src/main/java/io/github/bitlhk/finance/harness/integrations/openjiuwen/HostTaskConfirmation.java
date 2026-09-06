package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import java.time.Duration;
import java.time.Instant;

/** Supplied only by an authenticated host confirmation handler, never by a model tool. */
public record HostTaskConfirmation(
        String eventId,
        String bindingFingerprint,
        Instant confirmedAt,
        Instant expiresAt) {
    public HostTaskConfirmation {
        if (eventId == null || eventId.isBlank() || bindingFingerprint == null
                || !bindingFingerprint.matches("[a-f0-9]{64}")
                || confirmedAt == null || expiresAt == null
                || !expiresAt.isAfter(confirmedAt)
                || Duration.between(confirmedAt, expiresAt).compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("A bound host approval with a maximum five-minute lifetime is required");
        }
    }

    boolean validFor(String binding, Instant now) {
        return bindingFingerprint.equals(binding) && !confirmedAt.isAfter(now) && expiresAt.isAfter(now);
    }
}
