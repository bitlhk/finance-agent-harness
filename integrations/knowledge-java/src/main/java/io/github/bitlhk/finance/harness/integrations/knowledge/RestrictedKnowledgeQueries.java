package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;

/** Host-selected public questions only. Rejection happens before any Provider/session/network call. */
public final class RestrictedKnowledgeQueries {
    private final FinanceProvider provider;
    private final Set<String> questions;

    public RestrictedKnowledgeQueries(FinanceProvider provider, Set<String> questions) {
        this.provider = Objects.requireNonNull(provider);
        this.questions = Set.copyOf(questions);
        if (this.questions.isEmpty() || this.questions.stream().anyMatch(q -> q.isBlank() || q.length() > 1000))
            throw new IllegalArgumentException("KNOWLEDGE_PUBLIC_QUESTIONS_REQUIRED");
    }

    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        if (!"knowledge.evidence.search".equals(request.capabilityId())
                || request.operation() != CapabilityRequestV1.Operation.QUERY
                || request.idempotencyKey() != null || request.taskRef() != null
                || request.confirmation() != null || !request.continuation().isEmpty()
                || !request.parameters().keySet().equals(Set.of("question"))
                || !(request.parameters().get("question") instanceof String question)
                || !questions.contains(question))
            throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED");
        return provider.query(request);
    }
}
