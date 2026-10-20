package io.github.bitlhk.finance.harness.integrations.customermanager;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

final class ConversationConfirmationTest {
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final ConversationConfirmation.Binding BINDING = new ConversationConfirmation.Binding("owner-a", "instance-a", "lock-a", 1);
    private static final ConversationIntent INTENT = new ConversationIntent(ConversationIntent.Kind.PREPARE, "object-a", null, null);
    private static final Set<String> OBJECTS = Set.of("object-a");
    private static final Set<ConversationIntent.Kind> ACTIONS = Set.of(ConversationIntent.Kind.PREPARE);
    private static ConversationConfirmation store() { return new ConversationConfirmation(Clock.fixed(NOW, ZoneOffset.UTC)); }
    @Test void confirmationReturnsOnlyTheImmutableOriginalInputAndIsOneShot() {
        var store = store();
        var plan = store.propose(BINDING, INTENT, "original synthetic query 100");
        var accepted = store.claim(plan.id(), BINDING, OBJECTS, ACTIONS);
        assertEquals("original synthetic query 100", accepted.originalQuery());
        assertEquals(INTENT, accepted.intent());
        assertThrows(SecurityException.class, () -> store.claim(plan.id(), BINDING, OBJECTS, ACTIONS));
    }
    @Test void newInputInvalidatesOldConfirmationEvenForTheSameObject() {
        var store = store(); var old = store.propose(BINDING, INTENT, "amount 100");
        var next = store.propose(BINDING, INTENT, "amount 30");
        assertNotEquals(old.id(), next.id());
        assertThrows(SecurityException.class, () -> store.claim(old.id(), BINDING, OBJECTS, ACTIONS));
        assertThrows(SecurityException.class, () -> store.claim(next.id(), BINDING, OBJECTS, ACTIONS));
    }
    @Test void ownerInstanceLockAndEpochAreEachBound() {
        for (var changed : List.of(
                new ConversationConfirmation.Binding("owner-b", "instance-a", "lock-a", 1),
                new ConversationConfirmation.Binding("owner-a", "instance-b", "lock-a", 1),
                new ConversationConfirmation.Binding("owner-a", "instance-a", "lock-b", 1),
                new ConversationConfirmation.Binding("owner-a", "instance-a", "lock-a", 2))) {
            var store = store(); var plan = store.propose(BINDING, INTENT, "synthetic query");
            assertThrows(SecurityException.class, () -> store.claim(plan.id(), changed, OBJECTS, ACTIONS));
        }
    }
    @Test void revokedObjectOrRemovedCapabilityCannotExecute() {
        var store = store(); var plan = store.propose(BINDING, INTENT, "synthetic query");
        assertThrows(SecurityException.class, () -> store.claim(plan.id(), BINDING, Set.of(), ACTIONS));
        var second = store.propose(BINDING, INTENT, "synthetic query");
        assertThrows(SecurityException.class, () -> store.claim(second.id(), BINDING, OBJECTS, Set.of()));
    }
    @Test void expiryAndExplicitCancelInvalidateWithoutTouchingAnyReport() {
        class AdvancingClock extends Clock {
            Instant now = NOW;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new AdvancingClock(); var store = new ConversationConfirmation(clock);
        var plan = store.propose(BINDING, INTENT, "synthetic query"); clock.now = NOW.plusSeconds(300);
        assertThrows(SecurityException.class, () -> store.claim(plan.id(), BINDING, OBJECTS, ACTIONS));
        var next = store.propose(BINDING, INTENT, "synthetic query"); store.invalidate();
        assertThrows(SecurityException.class, () -> store.claim(next.id(), BINDING, OBJECTS, ACTIONS));
    }
    @Test void twoConcurrentConfirmationsCannotBothClaimExecution() throws Exception {
        var store = store(); var plan = store.propose(BINDING, INTENT, "synthetic query");
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> call = () -> {
                ready.countDown(); start.await();
                try { store.claim(plan.id(), BINDING, OBJECTS, ACTIONS); return true; }
                catch (SecurityException denied) { return false; }
            };
            var first = pool.submit(call); var second = pool.submit(call);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        }
    }
}
