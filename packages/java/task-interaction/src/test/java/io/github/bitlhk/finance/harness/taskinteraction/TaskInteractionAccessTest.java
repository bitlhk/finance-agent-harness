package io.github.bitlhk.finance.harness.taskinteraction;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskInteractionAccessTest {
    @Test void rechecksCurrentAuthorityScopeRolesAndExpiry() {
        var now=Instant.parse("2026-11-06T00:00:00Z");
        var current=new AtomicReference<>(new TaskInteractionAccess.Authority("actor","customer","task_1",Set.of("operator"),"g1",now.plusSeconds(60)));
        var access=new TaskInteractionAccess("customer","task_1",current::get,()->now);
        assertDoesNotThrow(()->access.require("actor","claim"));
        assertThrows(SecurityException.class,()->access.require("actor","approve"));
        assertThrows(SecurityException.class,()->access.require("other",null));
        current.set(null);assertThrows(SecurityException.class,()->access.require("actor",null));
        current.set(new TaskInteractionAccess.Authority("actor","customer","task_1",Set.of("reader"),"g2",now.minusSeconds(1)));
        assertThrows(SecurityException.class,()->access.require("actor",null));
    }
}
