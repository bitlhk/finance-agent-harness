package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.*;
import io.agentscope.core.event.*;
import io.agentscope.core.message.*;
import io.agentscope.core.middleware.*;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import reactor.core.publisher.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationEventV01.Outcome;

/** One mutable native Agent per trusted session generation. No business planner or alternate loop. */
public final class AgentScopeQuerySession implements AutoCloseable {
    public record Identity(String principalId, FinanceContextV1.PrincipalType principalType, String subjectRef,
                           String sessionRef, String purpose, String channel, FinanceContextV1.AuthenticationLevel authenticationLevel,
                           long generation) {
        public Identity {
            if (principalId == null || principalId.isBlank() || principalType == null || subjectRef == null || subjectRef.isBlank()
                    || sessionRef == null || sessionRef.isBlank() || purpose == null || purpose.isBlank() || channel == null
                    || authenticationLevel == null || generation < 1) throw new IllegalArgumentException("TRUSTED_SESSION_IDENTITY_REQUIRED");
        }
    }
    public record Input(String text, String requestId, String traceId, TrustedBusinessTimeV01 businessTime,
                        ObservationRun observation, String parentId, ModelDiagnostics diagnostics) {
        public Input(String text, String requestId, String traceId, TrustedBusinessTimeV01 businessTime, ObservationRun observation, String parentId) {
            this(text, requestId, traceId, businessTime, observation, parentId, ModelDiagnostics.disabled());
        }
        public Input {
            Objects.requireNonNull(diagnostics);
            if (text == null || text.isBlank() || text.length() > 16384 || businessTime == null || observation == null
                    || !Objects.equals(requestId, observation.requestId()) || traceId == null || traceId.isBlank())
                throw new IllegalArgumentException("TRUSTED_QUERY_INPUT_REQUIRED");
        }
    }
    public record QueryExecution(String rootRequestId, String toolCallId, CapabilityRequestV1 request, CapabilityResultV1 result, EvidenceReceiptV1 receipt) {}
    public record Reply(String text, String requestId, long generation, List<QueryExecution> executions, long droppedObservationEvents, long diagnosticFailures) {}
    private final AgentScopeQueryAssembler.Prepared prepared;
    private final Identity identity;
    private final Supplier<BoundFinanceProvider.Grant> grants;
    private final Map<String, BoundFinanceProvider> providers;
    private final ReActAgent agent;
    private final Map<String, Object> installationLock;
    private final AtomicBoolean valid = new AtomicBoolean(true), busy = new AtomicBoolean();
    private final Set<String> requestIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    AgentScopeQuerySession(AgentScopeQueryAssembler.Prepared prepared, Identity identity, Supplier<BoundFinanceProvider.Grant> grants, ModelConnection model) {
        this.prepared = prepared; this.identity = Objects.requireNonNull(identity); this.grants = Objects.requireNonNull(grants);
        Map<String, Set<String>> requirements = new LinkedHashMap<>();
        prepared.bindings.forEach((id, binding) -> requirements.put(id, binding.capability().requiredScopes()));
        Map<String, BoundFinanceProvider> bound = new LinkedHashMap<>();
        prepared.bindings.forEach((id, binding) -> bound.put(id, new BoundFinanceProvider(binding.provider(), grants, requirements)));
        providers = Map.copyOf(bound);
        Toolkit toolkit = new Toolkit();
        prepared.bindings.forEach((id, binding) -> toolkit.registerAgentTool(new QueryTool(id, binding)));
        Model nativeFactory = new Model() {
            public String getModelName() { return model.model(); }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions ignored) {
                return Flux.deferContextual(context -> {
                    Invocation call = context.get(Invocation.class);
                    call.requireAuthorized("model-create");
                    String callId = UUID.randomUUID().toString();
                    var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .connectTimeout(model.timeout()).build();
                    var transport = new CheckedTransport(JdkHttpTransport.builder().client(http).build(), call, model, callId);
                    return OpenAIChatModel.builder().apiKey(model.apiKey()).baseUrl(model.baseUri().toString()).modelName(model.model())
                            .stream(false).httpTransport(transport).build()
                            .stream(messages, tools, options(model.timeout()))
                            .doOnNext(response -> {
                                call.requireAuthorized("parsed-model-response");
                                var text = new StringBuilder(); var calls = new ArrayList<Map<String, Object>>();
                                for (var block : response.getContent()) {
                                    if (block instanceof TextBlock t) text.append(t.getText());
                                    if (block instanceof ToolUseBlock t) calls.add(Map.of("id", t.getId(), "type", "function", "function", Map.of("name", t.getName(), "arguments", t.getInput())));
                                }
                                call.capture(callId, "PARSED_MODEL_RESPONSE", Map.of("role", "assistant", "content", text.toString(), "tool_calls", calls));
                            }).doFinally(signal -> { transport.close(); http.shutdownNow(); clearIfInvalid(); });
                });
            }
        };
        agent = ReActAgent.builder().name("finance-query").sysPrompt(prepared.prompt).toolkit(toolkit).model(nativeFactory)
                .maxRetries(1).maxIters(6).enablePendingToolRecovery(false).fallbackModel((Model) null)
                .generateOptions(options(model.timeout())).toolExecutionConfig(execution(Duration.ofSeconds(30)))
                .middleware(new Middleware()).build();
        installationLock = prepared.lock(agent.getToolkit().getToolNames());
    }
    private static ExecutionConfig execution(Duration timeout) { return ExecutionConfig.builder().maxAttempts(1).timeout(timeout).build(); }
    private static GenerateOptions options(Duration timeout) { return GenerateOptions.builder().stream(false).temperature(0.0).executionConfig(execution(timeout)).build(); }
    public Set<String> installedTools() { return Set.copyOf(agent.getToolkit().getToolNames()); }
    public Map<String, Object> lock() { return installationLock; }
    public boolean valid() { return valid.get(); }
    public Mono<Reply> invoke(Input input) {
        return Mono.defer(() -> {
            if (!valid.get()) return Mono.error(new SecurityException("SESSION_GENERATION_INVALID"));
            if (!busy.compareAndSet(false, true)) return Mono.error(new IllegalStateException("SESSION_BUSY"));
            final Invocation call;
            try {
                if (!requestIds.add(input.requestId())) throw new IllegalArgumentException("DUPLICATE_REQUEST_ID");
                call = new Invocation(input); call.requireAuthorized("host-entry");
            } catch (RuntimeException failure) { invalidate(); busy.set(false); return Mono.error(failure); }
            var context = RuntimeContext.builder().sessionId(identity.sessionRef()).userId(identity.principalId()).put(Invocation.class, call).build();
            try (var installed = call.run.start(call.parent, "host", "installation", Map.of())) {
                installed.finish(Outcome.RETURNED, Map.of());
            }
            var terminated = new AtomicBoolean();
            return agent.call(input.text(), context)
                    .map(message -> {
                        call.requireAuthorized("host-delivery");
                        return new Reply(message.getTextContent(), input.requestId(), identity.generation(), List.copyOf(call.executions), call.run.droppedEvents(), call.diagnosticFailures.get());
                    })
                    // Release before delivering a terminal signal: downstream may start the next turn inline.
                    .doOnSuccess(reply -> { if (terminated.compareAndSet(false, true)) busy.set(false); })
                    .doOnError(failure -> {
                        if (terminated.compareAndSet(false, true)) { invalidate(); busy.set(false); }
                    })
                    .doOnCancel(() -> {
                        if (terminated.compareAndSet(false, true)) { invalidate(); busy.set(false); }
                    })
                    .doFinally(signal -> clearIfInvalid()).contextWrite(ctx -> ctx.put(Invocation.class, call));
        });
    }
    public void invalidate() {
        valid.set(false);
        clearIfInvalid();
    }
    private void clearIfInvalid() {
        if (valid.get()) return;
        var state = agent.getAgentState(identity.principalId(), identity.sessionRef());
        state.contextMutable().clear(); state.setSummary("");
    }
    public void cancel() { invalidate(); agent.interrupt(identity.principalId(), identity.sessionRef()); }
    @Override public void close() { cancel(); agent.close(); }

    final class Invocation {
        final Input input;
        final FinanceContextV1 context;
        final ObservationRun run;
        final String parent;
        final List<QueryExecution> executions = new CopyOnWriteArrayList<>();
        final Set<String> toolCallIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicLong diagnosticFailures = new AtomicLong();
        void capture(String callId, String boundary, Map<String, Object> value) {
            if (!input.diagnostics().capture(callId, boundary, value)) diagnosticFailures.incrementAndGet();
        }
        Invocation(Input input) {
            this.input = input; run = input.observation(); parent = input.parentId();
            var current = grants.get();
            if (current == null) throw new SecurityException("HOST_GRANT_REJECTED");
            context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, input.requestId(), input.traceId(), identity.principalId(),
                    identity.principalType(), identity.channel(), identity.purpose(), identity.subjectRef(), identity.sessionRef(), identity.authenticationLevel(), current.scopes());
        }
        void requireAuthorized(String point) {
            try (var span = run.start(parent, "host", "authorization/" + point, Map.of("harnessRequestId", context.requestId()))) {
                try {
                    if (!valid.get()) throw new SecurityException("SESSION_GENERATION_INVALID");
                    for (var entry : providers.entrySet()) entry.getValue().checkAccess(request(entry.getKey(), Map.of()));
                    span.finish(Outcome.RETURNED, Map.of());
                } catch (RuntimeException failure) { valid.set(false); span.notSent(); span.finish(Outcome.DENIED, Map.of()); throw failure; }
            }
        }
        CapabilityRequestV1 request(String id, Map<String, Object> arguments) {
            return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, id, CapabilityRequestV1.Operation.QUERY, context, arguments, null, null, Map.of(), null);
        }
        CapabilityRequestV1 toolRequest(String id, String toolCallId, Map<String, Object> arguments) {
            if (toolCallId == null || !toolCallId.matches("[A-Za-z0-9_.:/{}-]{1,200}") || !toolCallIds.add(toolCallId))
                throw new SecurityException("NATIVE_TOOL_CALL_ID_INVALID_OR_DUPLICATE");
            var child = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "query-" + UUID.randomUUID(), context.traceId(), context.principalId(),
                    context.principalType(), context.channel(), context.purpose(), context.subjectRef(), context.sessionRef(), context.authenticationLevel(), context.scopes());
            return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, id, CapabilityRequestV1.Operation.QUERY, child, arguments, null, null, Map.of(), null);
        }
    }

    private final class QueryTool implements AgentTool {
        private final String id;
        private final AgentScopeQueryAssembler.Binding binding;
        private final QueryInputValidator validator;
        QueryTool(String id, AgentScopeQueryAssembler.Binding binding) { this.id = id; this.binding = binding; validator = new QueryInputValidator(binding.capability().inputSchema()); }
        public String getName() { return binding.capability().toolName(); }
        public String getDescription() { return prepared.catalog.description(id); }
        public Map<String, Object> getParameters() { return binding.capability().inputSchema(); }
        public boolean isReadOnly() { return true; }
        public Mono<ToolResultBlock> callAsync(ToolCallParam parameter) {
            Invocation call = parameter.getRuntimeContext().get(Invocation.class);
            return Mono.fromCallable(() -> {
                call.requireAuthorized("provider-entry"); validator.validate(parameter.getInput());
                String toolCallId = parameter.getToolUseBlock().getId();
                var request = call.toolRequest(id, toolCallId, parameter.getInput());
                try (var span = call.run.start(call.parent, "runtime-tool", "query", Map.of("toolCallId", toolCallId, "capabilityId", id,
                        "harnessRequestId", request.context().requestId(), "harnessTraceId", call.context.traceId()))) {
                    try (var scope = AgentScopeObservationCollector.bind(call.run, span.id())) {
                        Instant started = Instant.now();
                        var observed = new ObservedFinanceProvider(providers.get(id), binding.provider().descriptor().providerId(), () -> call.run);
                        var result = observed.query(request);
                        call.requireAuthorized("provider-return");
                        if (result.error() != null && Set.of("NOT_AUTHORIZED", "NOT_AUTHENTICATED").contains(result.error().failureClass()))
                            throw new SecurityException("PROVIDER_AUTHORIZATION_DENIED");
                        if (result.task() != null || !(result.status() == CapabilityResultV1.Status.COMPLETED || result.status() == CapabilityResultV1.Status.NEED_USER || result.status() == CapabilityResultV1.Status.FAILED))
                            throw new SecurityException("QUERY_RESULT_CONTRACT_VIOLATION");
                        var receipt = EvidenceReceipts.from(request, result, binding.provider().descriptor().providerId(), started, Instant.now());
                        call.executions.add(new QueryExecution(call.input.requestId(), toolCallId, request, result, receipt));
                        span.finish(Outcome.RETURNED, Map.of("businessStatus", result.status().name()));
                        return ToolResultBlock.of(TextBlock.builder().text(HarnessJson.mapper().writeValueAsString(result)).build(), Map.of("receipt", receipt, "businessResult", result));
                    } catch (RuntimeException failure) { span.finish(failure instanceof SecurityException ? Outcome.DENIED : Outcome.ERROR, Map.of()); throw failure; }
                }
            }).doOnError(error -> invalidate()).doFinally(signal -> clearIfInvalid());
        }
    }

    private final class Middleware implements MiddlewareBase {
        public Flux<AgentEvent> onAgent(Agent ignored, RuntimeContext context, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
            return next.apply(input).doFinally(signal -> clearIfInvalid());
        }
        public Mono<String> onSystemPrompt(Agent ignored, RuntimeContext context, String prompt) {
            var time = context.get(Invocation.class).input.businessTime();
            return Mono.just(prompt + "\nTrusted business date: " + time.businessDate() + "\nBusiness timezone: " + time.timeZone()
                    + "\nBusiness time source: " + time.source());
        }
        public Flux<AgentEvent> onModelCall(Agent ignored, RuntimeContext context, ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                Invocation call = context.get(Invocation.class); call.requireAuthorized("model-hook");
                if (!Boolean.FALSE.equals(input.options().getStream()) || input.options().getExecutionConfig().getMaxAttempts() != 1)
                    return Flux.error(new SecurityException("UNSUPPORTED_MODEL_OPTIONS"));
                var span = call.run.start(call.parent, "model", "client-prepared-call", Map.of("harnessRequestId", call.context.requestId()));
                return next.apply(input).doOnComplete(() -> span.finish(Outcome.RETURNED, Map.of()))
                        .doOnError(e -> span.finish(Outcome.ERROR, Map.of())).doFinally(signal -> span.close());
            });
        }
        public Flux<AgentEvent> onActing(Agent ignored, RuntimeContext context, ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
            return Flux.defer(() -> {
                context.get(Invocation.class).requireAuthorized("before-tool");
                for (var tool : input.toolCalls()) if (!installedTools().contains(tool.getName()))
                    return Flux.error(new SecurityException("TOOL_NOT_INSTALLED"));
                return next.apply(input).doOnNext(event -> {
                    if (event instanceof ToolResultEndEvent end && end.getState() == ToolResultState.ERROR) {
                        invalidate(); throw new SecurityException("NATIVE_TOOL_ERROR_STOP");
                    }
                });
            });
        }
    }
}
