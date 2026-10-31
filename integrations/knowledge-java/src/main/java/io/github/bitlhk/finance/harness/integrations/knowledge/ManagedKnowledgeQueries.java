package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.OpenJiuwenSolutionAssembler;
import io.github.bitlhk.finance.harness.providers.knowledge.*;
import java.util.*;

/** Reuses the knowledge Host lease in a business scenario; never borrows a CRM session. */
public final class ManagedKnowledgeQueries implements AutoCloseable {
    private final KnowledgeAuthority authority;
    private final RestrictedKnowledgeQueries queries;
    private final Set<String> questions;

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
            var all = new HashMap<>(peers); all.putAll(providers);
            var reference = HarnessJson.mapper().readValue(context.artifactRoot().resolve("observation-reference.json").toFile(),
                    ObservationRequirementsV01.Reference.class);
            new OpenJiuwenSolutionAssembler().prepare(context.artifactRoot().resolve("assets"),
                    context.artifactRoot().resolve("solution.json"), all, reference, context.trustedCollectors());
            queries = new RestrictedKnowledgeQueries(providers.get(KnowledgeProvider.ID), questions);
        } catch (Exception failure) { authority.close(); throw failure; }
    }

    public CapabilityResultV1 query(String requestId, String traceId, String publicQuestion) {
        if (!questions.contains(publicQuestion)) throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED");
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
