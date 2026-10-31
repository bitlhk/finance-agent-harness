package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenCapabilityInstaller.ProviderInvocation;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenFinanceBridge.InvocationDefaults;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.TaskCheckpointStore;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import io.github.bitlhk.finance.harness.providers.knowledge.*;
import java.util.*;

/** Reuses the knowledge Host lease in a business scenario; never borrows a CRM session. */
public final class ManagedKnowledgeQueries implements AutoCloseable {
    private final KnowledgeAuthority authority;
    private final RestrictedKnowledgeQueries queries;
    private final Set<String> questions;
    private final OpenJiuwenSolutionAssembler assembler = new OpenJiuwenSolutionAssembler();
    private final OpenJiuwenSolutionAssembler.PreparedSolution prepared;
    private final ReActAgent executionAgent;
    private OpenJiuwenSolutionAssembler.SolutionLock lock;
    private Map<String, ProviderInvocation> currentContexts;

    public ManagedKnowledgeQueries(ManagedScenarioExtensionV01.Context context,
            Map<String, FinanceProvider> peers, Set<String> publicQuestions) throws Exception {
        questions = Set.copyOf(publicQuestions);
        if (questions.isEmpty()) throw new IllegalArgumentException("KNOWLEDGE_PUBLIC_QUESTIONS_REQUIRED");
        InstalledKnowledgeProvider.prepare(context.artifactRoot(), peers);
        authority = new KnowledgeAuthority(context.artifactRoot(), context.ownerRef(), context.instanceId(),
                UUID.randomUUID().toString());
        try {
            if (context.trustedCollectors().isEmpty()) throw new SecurityException("TRUSTED_COLLECTORS_REQUIRED");
            var providers = InstalledKnowledgeProvider.create(context.artifactRoot(),
                    authority::source, authority::grant, context.currentObservation(), ignored -> {}, peers);
            queries = new RestrictedKnowledgeQueries(providers.get(KnowledgeProvider.ID), questions);
            var all = new HashMap<>(peers); all.put(KnowledgeProvider.ID, queries);
            var reference = HarnessJson.mapper().readValue(context.artifactRoot().resolve("observation-reference.json").toFile(),
                    ObservationRequirementsV01.Reference.class);
            prepared = assembler.prepare(context.artifactRoot().resolve("assets"),
                    context.artifactRoot().resolve("solution.json"), all, reference, context.trustedCollectors());
            executionAgent = new ReActAgent(AgentCard.builder().id("manager-tools-" + authority.session)
                    .name("Installed customer-manager tools").build());
        } catch (Exception failure) { authority.close(); throw failure; }
    }

    /** Existing Host SOP dispatches the same actual Provider objects covered by this SDK installation. */
    public <T> T withInstalledProviders(Map<String, ProviderInvocation> peers, java.util.function.Supplier<T> action) {
        synchronized (KnowledgeScenario.Managed.SDK_LOCK) {
            current();
            var contexts = new HashMap<>(peers);
            var grant = authority.grant();
            if (contexts.put(KnowledgeProvider.ID, new ProviderInvocation(new InvocationDefaults(
                    grant.principalId(), authority.text("subjectRef"), grant.principalType(), "knowledge-isolated-host",
                    authority.frozen.path("context").path("purpose").asText(), FinanceContextV1.AuthenticationLevel.BASIC,
                    grant.scopes(), () -> authority.grant().scopes(), true), authority.session)) != null)
                throw new SecurityException("KNOWLEDGE_CONTEXT_MUST_BE_INDEPENDENT");
            try (var installed = assembler.installWithProviderContexts(prepared, executionAgent, contexts,
                    true, new TaskCheckpointStore.Memory())) {
                if (lock == null) lock = installed.lock();
                else KnowledgeScenario.requireSameInstallation(lock, installed.lock());
                currentContexts = Map.copyOf(peers);
                return action.get();
            }
        }
    }

    public OpenJiuwenSolutionAssembler.SolutionLock installationLock() {
        if (lock == null) throw new IllegalStateException("JOINT_INSTALLATION_REQUIRED");
        return lock;
    }
    public String manifest() { return authority.text("manifest"); }
    public String productId() { return authority.text("productId"); }

    public CapabilityResultV1 query(String requestId, String traceId, String publicQuestion) {
        if (!questions.contains(publicQuestion)) throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED");
        if (currentContexts == null) throw new SecurityException("JOINT_INSTALLATION_REQUIRED");
        return withInstalledProviders(currentContexts, () -> queryInstalled(requestId, traceId, publicQuestion));
    }
    private CapabilityResultV1 queryInstalled(String requestId, String traceId, String publicQuestion) {
        current();
        var grant = authority.grant();
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, requestId, traceId,
                grant.principalId(), grant.principalType(), "knowledge-isolated-host",
                authority.frozen.path("context").path("purpose").asText(), authority.text("subjectRef"),
                authority.session, FinanceContextV1.AuthenticationLevel.BASIC, grant.scopes());
        var result = queries.query(new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,
                KnowledgeProvider.SEARCH, CapabilityRequestV1.Operation.QUERY, context,
                Map.of("question", publicQuestion), null, null, Map.of(), null));
        current();
        if (!requestId.equals(result.requestId()) || !traceId.equals(result.traceId()))
            throw new SecurityException("KNOWLEDGE_RESULT_BINDING_MISMATCH");
        return result;
    }

    public void current() { authority.currentKnowledgeAccess(); }
    @Override public void close() { authority.close(); }
}
