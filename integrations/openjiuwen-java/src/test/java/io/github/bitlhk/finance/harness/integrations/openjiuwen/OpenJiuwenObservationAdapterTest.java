package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.singleagent.rail.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

class OpenJiuwenObservationAdapterTest {
    @Test void forceFinishPreservedAndAfterDoesNotInventCall() {
        var events = new ArrayList<ObservationEventV01>();
        var adapter = new OpenJiuwenObservationAdapter();
        adapter.attach(new ObservationRun("req", "boot", "instance", events::add), null);
        var context = AgentCallbackContext.builder().event(AgentCallbackEvent.BEFORE_MODEL_CALL).build();
        context.requestForceFinish(Map.of("error", "AUTHORIZATION_REJECTED"));
        adapter.observe(context);
        assertTrue(context.hasForceFinishRequest());
        context.setEvent(AgentCallbackEvent.AFTER_MODEL_CALL); adapter.observe(context);
        adapter.detach();
        assertEquals(2, events.size());
        assertEquals(Dispatch.NOT_SENT, events.getLast().dispatch());
        assertEquals(Outcome.DENIED, events.getLast().outcome());
    }
    @Test void errorsAndReturnedCallbacksDoNotClaimBusinessSuccess() {
        var events = new ArrayList<ObservationEventV01>();
        var adapter = new OpenJiuwenObservationAdapter();
        adapter.attach(new ObservationRun("req", "boot", "instance", events::add), null);
        var context = AgentCallbackContext.builder().event(AgentCallbackEvent.BEFORE_MODEL_CALL).build();
        adapter.observe(context);
        context.setEvent(AgentCallbackEvent.ON_MODEL_EXCEPTION);
        context.setException(new IllegalStateException("private body must not be copied"));
        adapter.observe(context);
        context.setEvent(AgentCallbackEvent.AFTER_MODEL_CALL); adapter.observe(context);
        assertEquals(2, events.size());
        assertEquals(Outcome.ERROR, events.getLast().outcome());
        assertEquals(Dispatch.NOT_OBSERVED, events.getLast().dispatch());
        assertFalse(events.toString().contains("private body"));
        context.setEvent(AgentCallbackEvent.BEFORE_MODEL_CALL); context.setException(null); adapter.observe(context);
        context.setEvent(AgentCallbackEvent.AFTER_MODEL_CALL); adapter.observe(context);
        assertEquals(Outcome.RETURNED, events.getLast().outcome());
        adapter.detach();
    }
}
