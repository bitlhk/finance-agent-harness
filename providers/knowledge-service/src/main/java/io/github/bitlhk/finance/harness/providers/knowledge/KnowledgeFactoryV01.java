package io.github.bitlhk.finance.harness.providers.knowledge;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;

/** Reviewed ServiceLoader entry; no asset-provided class loading. */
public final class KnowledgeFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND = "knowledge-http.v0.1";
    private KnowledgeBinding binding;
    public KnowledgeFactoryV01() { }
    KnowledgeFactoryV01(KnowledgeBinding binding) { this.binding = binding; }
    private synchronized KnowledgeBinding binding() {
        if (binding == null) binding = KnowledgeBinding.load(System.getenv("FH_KNOWLEDGE_BINDING"),
            System.getenv("FH_KNOWLEDGE_BINDING_SHA256"));
        return binding;
    }
    public String providerId() { return KnowledgeProvider.ID; }
    public String connectionKind() { return KIND; }
    public Map<String,Set<String>> requirements() {
        return Map.of(KnowledgeProvider.SEARCH, Set.of("knowledge:read"),
            KnowledgeProvider.RULE, Set.of("knowledge:read", "knowledge:rule"));
    }
    public Set<String> sourceScopes(Set<String> capabilities) {
        if (!requirements().keySet().containsAll(capabilities)) throw new IllegalArgumentException("KNOWLEDGE_CAPABILITY_INVALID");
        return capabilities.contains(KnowledgeProvider.RULE) ? Set.of("knowledge:read", "knowledge:rule") : Set.of("knowledge:read");
    }
    public Map<String,String> configurationDigests() { return Map.of("binding-sha256", binding().sha256()); }
    public DescribedFinanceProvider create(Connection c, Map<String,BoundFinanceProvider> dependencies) {
        // The isolated probe intentionally allows no arbitrary network destinations.
        if (!KIND.equals(c.kind()) || !dependencies.isEmpty() || !"http".equals(c.endpoint().getScheme())
                || !"127.0.0.1".equals(c.endpoint().getHost()) || c.endpoint().getPort() < 1
                || c.endpoint().getUserInfo() != null || c.endpoint().getQuery() != null
                || c.endpoint().getFragment() != null || !"/query".equals(c.endpoint().getPath()))
            throw new IllegalArgumentException("KNOWLEDGE_CONNECTION_INVALID");
        return new KnowledgeProvider(binding(), c);
    }
}
