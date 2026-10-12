package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.time.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalSessionTest {
    static class Time extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    TerminalSession create(Time time) throws Exception {
        return new TerminalSession(HarnessJson.mapper().readTree("{\"sessionPolicy\":{\"idleSeconds\":120,\"warningSeconds\":15,\"absoluteSeconds\":600}}"), time);
    }
    @Test public void pollingAndProcessingDoNotExtendIdle() throws Exception {
        Time t = new Time(); var s = create(t); s.begin("run-a");
        t.now = 120000; s.processing(); assertEquals("IDLE_WARNING", s.status().get("status"));
        t.now = 135000; assertEquals("SESSION_EXPIRED", assertThrows(SecurityException.class, s::processing).getMessage());
    }
    @Test public void activityCannotExtendAbsoluteDeadline() throws Exception {
        Time t = new Time(); var s = create(t);
        for (int i=1;i<6;i++) { t.now=i*100000; s.touch(); }
        t.now=600000; assertThrows(SecurityException.class, s::check);
    }
    @Test public void cancellationAndEndAreDifferent() throws Exception {
        Time t = new Time(); var s = create(t); s.begin("a");
        assertEquals("CANCEL_REQUESTED", s.cancel("a").get("status"));
        assertEquals("RUN_CANCELLED", assertThrows(SecurityException.class, s::processing).getMessage());
        assertEquals("AUTHORIZED", s.status().get("status")); s.finish("FAILED");
        s.begin("b"); s.processing(); s.close("SESSION_ENDED");
        assertFalse(s.cancelled());
        assertEquals("SESSION_ENDED", assertThrows(SecurityException.class, s::processing).getMessage());
    }
    @Test public void finishedRunCannotBeReplayed() throws Exception {
        var s = create(new Time()); s.begin("a"); s.finish("COMPLETED"); s.begin("b"); s.finish("COMPLETED");
        assertThrows(IllegalArgumentException.class, () -> s.begin("a"));
    }
    @Test public void cancelledOrClosedRunCannotCommitHistory() throws Exception {
        var cancelled = create(new Time()); cancelled.begin("cancelled"); cancelled.cancel("cancelled");
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(SecurityException.class, () -> cancelled.complete(writes::incrementAndGet));
        assertEquals(0, writes.get());
        var closed = create(new Time()); closed.begin("closed"); closed.close("SESSION_REVOKED");
        assertThrows(SecurityException.class, () -> closed.complete(writes::incrementAndGet));
        assertEquals(0, writes.get());
    }
    @Test public void committedRunIsTerminalBeforeNextAction() throws Exception {
        var session = create(new Time()); session.begin("first");
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        session.complete(writes::incrementAndGet);
        assertEquals(1, writes.get());
        assertEquals("COMPLETED", session.status().get("runStatus"));
        assertEquals("NOT_RUNNING", session.cancel("first").get("status"));
        session.begin("second"); session.processing();
        assertEquals("RUNNING", session.status().get("runStatus"));
    }
}
