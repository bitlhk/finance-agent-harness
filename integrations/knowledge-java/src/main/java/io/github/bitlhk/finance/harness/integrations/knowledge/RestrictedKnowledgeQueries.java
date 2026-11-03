package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;

/** Build-installed public document questions only. Rejection happens before any Provider/session/network call. */
public final class RestrictedKnowledgeQueries implements DescribedFinanceProvider, InvocationAccess {
    private final FinanceProvider provider;
    private final Set<String> questions;
    private final Set<String> approvedRuntimeQuestions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final List<String> PRIVATE_MARKERS = List.of("CUST-", "CRM", "crm", "客户编号", "身份证",
            "手机号", "账户号", "客户资料", "客户画像", "持仓明细", "会话令牌", "token", "profiles");

    public RestrictedKnowledgeQueries(FinanceProvider provider, Set<String> questions) {
        this.provider = Objects.requireNonNull(provider);
        this.questions = Set.copyOf(questions);
        if (this.questions.isEmpty() || this.questions.stream().anyMatch(q -> q.isBlank() || q.length() > 1000))
            throw new IllegalArgumentException("KNOWLEDGE_PUBLIC_QUESTIONS_REQUIRED");
    }

    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        validate(request);
        return provider.query(request);
    }

    public String publicQuestion(String question, Collection<String> protectedAliases) {
        if (question == null || !question.equals(question.strip()) || question.length() < 2 || question.length() > 500
                || question.chars().anyMatch(c -> Character.isISOControl(c))
                || PRIVATE_MARKERS.stream().anyMatch(question::contains)
                || protectedAliases.stream().filter(Objects::nonNull).map(String::strip)
                    .filter(alias -> !alias.isEmpty()).anyMatch(question::contains))
            throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED");
        approvedRuntimeQuestions.add(question);
        return question;
    }

    private void validate(CapabilityRequestV1 request) {
        if (!"knowledge.evidence.search".equals(request.capabilityId())
                || request.operation() != CapabilityRequestV1.Operation.QUERY
                || request.idempotencyKey() != null || request.taskRef() != null
                || request.confirmation() != null || !request.continuation().isEmpty()
                || !request.parameters().keySet().equals(Set.of("question"))
                || !(request.parameters().get("question") instanceof String question)
                || (!questions.contains(question) && !approvedRuntimeQuestions.contains(question)))
            throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED");
    }
    public void checkAccess(CapabilityRequestV1 request) {
        validate(request);
        if (!(provider instanceof InvocationAccess access)) throw new SecurityException("KNOWLEDGE_ACCESS_REQUIRED");
        access.checkAccess(request);
    }
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new SecurityException("KNOWLEDGE_EGRESS_REJECTED"); }
    public ProviderDescriptorV1 descriptor() { return ((DescribedFinanceProvider) provider).descriptor(); }
    public List<Class<?>> artifactTypes() { return ((DescribedFinanceProvider) provider).artifactTypes(); }
}
