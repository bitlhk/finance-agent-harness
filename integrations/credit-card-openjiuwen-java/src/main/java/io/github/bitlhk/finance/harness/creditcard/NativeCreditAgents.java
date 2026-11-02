package io.github.bitlhk.finance.harness.creditcard;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.schema.WorkflowSchema;
import com.openjiuwen.core.common.constants.ControllerType;
import com.openjiuwen.core.workflow.Workflow;
import java.util.List;
import java.util.Map;

/** Native SDK constructors shared by the two credit scenes; never an alternate agent loop. */
public final class NativeCreditAgents {
    private NativeCreditAgents() {}
    private static final java.util.concurrent.ConcurrentMap<String, Runnable> GUARDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String GUARDED_PROVIDER = "CreditGuardedOpenAI";
    static {
        com.openjiuwen.core.foundation.llm.Model.registerFactory(
            new com.openjiuwen.core.foundation.llm.Model.ModelClientFactory() {
                public String providerName() { return GUARDED_PROVIDER; }
                public com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient create(
                        com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig request,
                        com.openjiuwen.core.foundation.llm.schema.ModelClientConfig client) {
                    return new com.openjiuwen.core.foundation.llm.model_clients.OpenAiCompatibleModelClient(request, client) {
                        private void check() {
                            Runnable guard = GUARDS.get(client.getClientId());
                            if (guard == null) throw new java.util.concurrent.CancellationException("MODEL_CONTEXT_CLOSED");
                            guard.run();
                        }
                        @Override public com.openjiuwen.core.foundation.llm.schema.AssistantMessage invoke(
                                Object messages, Object tools, Float temperature, Float topP,
                                String model, Integer maxTokens, String stop,
                                com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser parser,
                                Float timeout, Map<String,Object> kwargs) throws Exception {
                            check();
                            var result = super.invoke(messages, tools, temperature, topP, model,
                                    maxTokens, stop, parser, timeout, kwargs);
                            check(); return result;
                        }
                        @Override public java.util.Iterator<com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk> stream(
                                Object messages, Object tools, Float temperature, Float topP,
                                String model, Integer maxTokens, String stop,
                                com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser parser,
                                Float timeout, Map<String,Object> kwargs) throws Exception {
                            check();
                            var result = super.stream(messages, tools, temperature, topP, model,
                                    maxTokens, stop, parser, timeout, kwargs);
                            return new java.util.Iterator<>() {
                                public boolean hasNext() { check(); return result.hasNext(); }
                                public com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk next() {
                                    check(); var item=result.next(); check(); return item;
                                }
                            };
                        }
                    };
                }
            });
    }
    /** The guard identity is in process only, never a model header or request field. */
    public static void releaseModel(String id) { GUARDS.remove(id); }


    public static ReActAgent limitAgent(String id, String base, String key, String model,
                                        String systemPrompt) {
        return limitAgent(id,base,key,model,systemPrompt,null);
    }
    public static ReActAgent limitAgent(String id, String base, String key, String model,
                                        String systemPrompt, Runnable guard) {
        if(!"deepseek-flash".equals(model))throw new IllegalArgumentException("REFERENCE_MODEL_NOT_ALLOWED");
        var endpoint=java.net.URI.create(base);
        if(!java.util.Set.of("http","https").contains(endpoint.getScheme())||endpoint.getHost()==null
                ||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null||endpoint.getFragment()!=null
                ||key==null||key.isBlank()||key.chars().anyMatch(Character::isWhitespace))
            throw new IllegalArgumentException("INVALID_MODEL_BINDING");
        var agent = new ReActAgent(AgentCard.builder().id(id).name("Credit service")
                .description("Customer manager credit service preparation").build());
        var config = ReActAgentConfig.builder().maxIterations(5)
                .promptTemplate(List.of(Map.of("role", "system", "content", systemPrompt)))
                .build().configureModelClient("OpenAI", key, base, model, true);
        config.getModelConfigObj().setTemperature(0.0);
        config.getModelConfigObj().setMaxTokens(2048);
        if (model.toLowerCase(java.util.Locale.ROOT).contains("deepseek"))
            config.getModelConfigObj().setExtraField("thinking", Map.of("type", "disabled"));
        if (guard != null) {
            GUARDS.put(id,guard);
            config.setModelClientConfig(com.openjiuwen.core.foundation.llm.schema.ModelClientConfig.builder()
                    .clientId(id).clientProvider(GUARDED_PROVIDER).apiBase(base).apiKey(key)
                    .maxRetries(0).timeout(30.0).verifySsl(true).build());
        }
        agent.configure(config);
        return agent;
    }

    /** One native SDK model invocation inside a Workflow node, using the same cancellation guard. */
    public static com.fasterxml.jackson.databind.node.ObjectNode jsonDecision(String base,String key,String model,
            String system, Object payload, Runnable guard, long remainingMillis) throws Exception {
        if (!"deepseek-flash".equals(model)) throw new IllegalArgumentException("REFERENCE_MODEL_NOT_ALLOWED");
        String id="credit-model-"+java.util.UUID.randomUUID();
        GUARDS.put(id,guard);
        try {
            guard.run();
            var client=com.openjiuwen.core.foundation.llm.schema.ModelClientConfig.builder()
                    .clientId(id).clientProvider(GUARDED_PROVIDER).apiBase(base).apiKey(key)
                    .maxRetries(0).timeout(Math.max(0.001,remainingMillis/1000.0)).verifySsl(true).build();
            var request=com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig.builder()
                    .modelName(model).temperature(0.0).maxTokens(2048).build();
            request.setExtraField("thinking",Map.of("type","disabled"));
            var sdk=new com.openjiuwen.core.foundation.llm.Model(client,request);
            var response=sdk.invoke(List.of(Map.of("role","system","content",system),
                    Map.of("role","user","content",CreditContracts.JSON.writeValueAsString(payload))),
                    null,null,null,null,null,null,null,(float)(remainingMillis/1000.0),Map.of());
            guard.run();
            return CreditContracts.object(CreditContracts.JSON.readTree(response.getContentAsString()));
        } finally {releaseModel(id);}
    }

    public static WorkflowAgent benefitsAgent(String id, Workflow workflow) {
        var card = workflow.getCard();
        var schema = WorkflowSchema.builder().id(card.getId()).name(card.getName())
                .version(card.getVersion()).description("Checked credit recommendation")
                .build();
        var config = WorkflowAgentConfig.builder().id(id).version("0.1.0")
                .description("Customer manager benefits workflow")
                .controllerType(ControllerType.WORKFLOW_CONTROLLER)
                .workflows(List.of(schema)).startWorkflow(schema).build();
        var agent = new WorkflowAgent(config);
        agent.addWorkflows(List.of(workflow));
        return agent;
    }
}
