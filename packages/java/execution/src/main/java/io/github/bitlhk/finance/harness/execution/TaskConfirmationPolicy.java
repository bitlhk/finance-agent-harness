package io.github.bitlhk.finance.harness.execution;

import java.time.Duration;
import java.time.Instant;

/** Value checks only. The host must authenticate and bind the confirmation event. */
public final class TaskConfirmationPolicy {
    private TaskConfirmationPolicy() { }

    public static void validate(String eventId, String binding, Instant confirmedAt, Instant expiresAt) {
        if (eventId == null || eventId.isBlank() || binding == null
                || !binding.matches("[a-f0-9]{64}")
                || confirmedAt == null || expiresAt == null
                || !expiresAt.isAfter(confirmedAt)
                || Duration.between(confirmedAt, expiresAt).compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("A bound host approval with a maximum five-minute lifetime is required");
        }
    }

    /** Requires previously validated event fields; expiration is exclusive. */
    public static boolean validFor(String eventBinding, Instant confirmedAt, Instant expiresAt,
                                   String checkpointBinding, Instant now) {
        return eventBinding.equals(checkpointBinding) && !confirmedAt.isAfter(now) && expiresAt.isAfter(now);
    }
}
