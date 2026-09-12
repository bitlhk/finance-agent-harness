package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import io.github.bitlhk.finance.harness.core.observation.ObservationRun;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.*;

/** SDK-specific callback translation. One adapter per serialized Agent conversation. */
public final class OpenJiuwenObservationAdapter {
    private ObservationRun run;
    private String parent;
    private final Map<String, ObservationRun.Span> pending = new HashMap<>();
    private final Map<String, ObservationRun.ParentScope> scopes = new HashMap<>();

    public synchronized void attach(ObservationRun run, String parent) {
        detach(); this.run = run; this.parent = parent;
    }
    public synchronized void detach() {
        pending.values().forEach(ObservationRun.Span::close);
        scopes.values().forEach(ObservationRun.ParentScope::close); scopes.clear();
        pending.clear(); run = null; parent = null;
    }

    /** Call after existing authorization callbacks; never consumes force-finish or retry requests. */
    public synchronized void observe(AgentCallbackContext context) {
        if (run == null) return;
        try {
            var links = new LinkedHashMap<String, String>();
            links.put("attempt", Integer.toString(context.getRetryAttempt()));
            if (context.getSession() instanceof AgentSessionApi session) {
                String trace = session.getInner().tracerTyped().getTraceId();
                if (trace != null) links.put("sdkTraceId", trace);
            }
            String key = "model";
            if (context.getInputs() instanceof ToolCallInputs tool) {
                String id = tool.getToolCall() == null ? null : tool.getToolCall().getId();
                if (id != null) links.put("toolCallId", id);
                key = "tool:" + (id == null ? "unobserved" : id);
            }
            String event = context.getEvent().name();
            if (event.equals("BEFORE_MODEL_CALL") || event.equals("BEFORE_TOOL_CALL")) {
                var previous = pending.remove(key);
                if (previous != null) previous.close();
                var previousScope = scopes.remove(key);
                if (previousScope != null) previousScope.close();
                var span = run.start(parent, key.startsWith("tool:") ? "runtime-tool" : "model",
                        "attempt", links);
                pending.put(key, span);
                if (key.startsWith("tool:")) scopes.put(key, run.bindParent(span.id()));
                if (context.hasForceFinishRequest()) {
                    span.notSent(); span.finish(Outcome.DENIED, Map.of());
                    pending.remove(key);
                    var scope = scopes.remove(key); if (scope != null) scope.close();
                }
            } else if (event.equals("ON_MODEL_EXCEPTION") || event.equals("ON_TOOL_EXCEPTION")) {
                var span = pending.remove(key);
                var scope = scopes.remove(key); if (scope != null) scope.close();
                if (span != null) span.finish(Outcome.ERROR, context.getException() == null ? Map.of()
                        : Map.of("exceptionType", context.getException().getClass().getSimpleName()));
            } else if (event.equals("AFTER_MODEL_CALL") || event.equals("AFTER_TOOL_CALL")) {
                var span = pending.remove(key);
                var scope = scopes.remove(key); if (scope != null) scope.close();
                if (span != null) span.finish(Outcome.RETURNED, Map.of());
            }
        } catch (RuntimeException ignored) {
            // A malformed SDK observation must not change authorization or business execution.
        }
    }
}
