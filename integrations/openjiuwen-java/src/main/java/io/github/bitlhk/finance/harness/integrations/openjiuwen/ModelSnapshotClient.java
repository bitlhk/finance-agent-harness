package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.*;
import com.openjiuwen.core.foundation.llm.schema.*;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import java.util.*;

/** Opt-in, synchronous client boundary. The host owns redaction, storage and permissions. */
public final class ModelSnapshotClient extends OpenAiCompatibleModelClient {
    public static final String PROVIDER = "FinanceSnapshotOpenAI";
    private record ScopeContext(Receiver receiver, Thread owner) { }
    private static final InheritableThreadLocal<ScopeContext> CURRENT = new InheritableThreadLocal<>();
    public interface Receiver extends io.github.bitlhk.finance.harness.assembly.ModelSnapshotReceiver {
        /** Must freeze/filter content synchronously; never enqueue mutable SDK objects. */
        void capture(String callId, String boundary, String state, Map<String, Object> value);
    }
    public static synchronized void register() {
        if (registered) return;
        Model.registerFactory(new Model.ModelClientFactory() {
            public String providerName() { return PROVIDER; }
            public BaseModelClient create(ModelRequestConfig request, ModelClientConfig client) {
                return new ModelSnapshotClient(request, client);
            }
        });
        registered = true;
    }
    private static boolean registered;
    public static AutoCloseable scope(Receiver receiver) {
        if (CURRENT.get() != null) throw new IllegalStateException("SNAPSHOT_SCOPE_ALREADY_ACTIVE");
        Thread owner = Thread.currentThread();
        CURRENT.set(new ScopeContext(Objects.requireNonNull(receiver),owner));
        return () -> {
            if (Thread.currentThread() != owner) throw new IllegalStateException("SNAPSHOT_SCOPE_THREAD_MISMATCH");
            CURRENT.remove();
        };
    }
    public ModelSnapshotClient(ModelRequestConfig request, ModelClientConfig client) { super(request, client); }
    private static void capture(Receiver receiver, String call, String boundary, String state, Map<String,Object> value) {
        try { receiver.capture(call, boundary, state, value); }
        catch (RuntimeException ignored) { /* Optional observation cannot change the business outcome. */ }
    }
    @Override public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP,
            String model, Integer maxTokens, String stop, BaseOutputParser parser, Float frequencyPenalty,
            Map<String,Object> kwargs) throws Exception {
        ScopeContext context=CURRENT.get(); Receiver receiver=context==null?null:context.receiver();
        if (receiver == null) return super.invoke(messages, tools, temperature, topP, model, maxTokens, stop, parser, frequencyPenalty, kwargs);
        String call = UUID.randomUUID().toString();
        try {
            AssistantMessage result = withRequestTraceCallback(
                    request -> capture(receiver, call, "CLIENT_REQUEST_PREPARED", "CAPTURED", request),
                    () -> super.invoke(messages, tools, temperature, topP, model, maxTokens, stop, parser, frequencyPenalty, kwargs));
            var response = new LinkedHashMap<String,Object>();
            // Deliberately exclude reasoningContent and arbitrary vendor metadata.
            var api = result.toApiFormat();
            for (String key : List.of("role", "content", "tool_calls")) if (api.containsKey(key)) response.put(key, api.get(key));
            capture(receiver, call, "PARSED_MODEL_RESPONSE", "CAPTURED", response);
            return result;
        } catch (Exception failed) {
            capture(receiver, call, "PARSED_MODEL_RESPONSE", "NOT_CAPTURED", Map.of("reason", "MODEL_CALL_FAILED"));
            throw failed;
        } finally {
            if(Thread.currentThread()!=context.owner())CURRENT.remove();
        }
    }
    @Override public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature, Float topP,
            String model, Integer maxTokens, String stop, BaseOutputParser parser, Float frequencyPenalty,
            Map<String,Object> kwargs) throws Exception {
        ScopeContext context=CURRENT.get(); Receiver receiver=context==null?null:context.receiver();
        if (receiver == null) return super.stream(messages, tools, temperature, topP, model, maxTokens, stop, parser, frequencyPenalty, kwargs);
        String call = UUID.randomUUID().toString();
        try {
            Iterator<AssistantMessageChunk> delegate = withRequestTraceCallback(
                    request -> capture(receiver, call, "CLIENT_REQUEST_PREPARED", "CAPTURED", request),
                    () -> super.stream(messages, tools, temperature, topP, model, maxTokens, stop, parser, frequencyPenalty, kwargs));
            return new Iterator<>() {
                private final StringBuilder content = new StringBuilder();
                private boolean captured;
                public boolean hasNext() {
                    try {
                        boolean more=delegate.hasNext();
                        if(!more)complete();
                        return more;
                    } catch(RuntimeException failed) {
                        capture(receiver,call,"PARSED_MODEL_RESPONSE","NOT_CAPTURED",Map.of("reason","MODEL_CALL_FAILED"));
                        if(Thread.currentThread()!=context.owner())CURRENT.remove();
                        throw failed;
                    }
                }
                public AssistantMessageChunk next() {
                    try {
                        AssistantMessageChunk chunk=delegate.next();
                        Object value=chunk.toApiFormat().get("content");
                        if(value!=null)content.append(value);
                        return chunk;
                    } catch(RuntimeException failed) {
                        capture(receiver,call,"PARSED_MODEL_RESPONSE","NOT_CAPTURED",Map.of("reason","MODEL_CALL_FAILED"));
                        if(Thread.currentThread()!=context.owner())CURRENT.remove();
                        throw failed;
                    }
                }
                private void complete() {
                    if(captured)return;captured=true;
                    capture(receiver,call,"PARSED_MODEL_RESPONSE","CAPTURED",Map.of("role","assistant","content",content.toString()));
                    if(Thread.currentThread()!=context.owner())CURRENT.remove();
                }
            };
        } catch (Exception failed) {
            capture(receiver, call, "PARSED_MODEL_RESPONSE", "NOT_CAPTURED", Map.of("reason", "MODEL_CALL_FAILED"));
            throw failed;
        }
    }
}
