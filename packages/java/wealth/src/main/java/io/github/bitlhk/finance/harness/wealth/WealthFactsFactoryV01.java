package io.github.bitlhk.finance.harness.wealth;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.time.*;
import java.util.*;

/** Existing mobile simulator mapping, owned by the wealth artifact rather than a Builder recipe. */
public final class WealthFactsFactoryV01 implements ManagedProviderFactoryV01 {
    private static final String SOURCE = "non-ai-banking-reference";
    private static final String SOURCE_CAPABILITY = "wealth.customer-context.query";
    private static final Set<String> SOURCE_SCOPES = Set.of("wealth:customer:read");
    public String providerId() { return WealthFactsProvider.PROVIDER; }
    public String connectionKind() { return "synthetic-bank-http.v1"; }
    public Map<String, Set<String>> requirements() {
        return Map.of(WealthFactsProvider.CAPABILITY, Set.of(WealthFactsProvider.SCOPE));
    }
    public Set<String> dependencies() { return Set.of(SOURCE); }
    public Set<String> sourceScopes(Set<String> selected) {
        return selected.contains(WealthFactsProvider.CAPABILITY) ? SOURCE_SCOPES : Set.of();
    }
    public DescribedFinanceProvider create(Connection connection, Map<String, BoundFinanceProvider> dependencies) {
        var semantics = new WealthSourceSemantics("non-ai-banking-simulator", "bank-api-v1", "simulator-interface-v01",
                null, null, null, null, ZoneId.of("Asia/Shanghai"), false);
        return new WealthFactsProvider(Objects.requireNonNull(dependencies.get(SOURCE)), SOURCE_CAPABILITY,
                SOURCE_SCOPES, WealthFactsMapper.Format.MOBILE, semantics,
                WealthFactsV1.SourceKind.CONTROLLED_FIXTURE, Clock.systemUTC());
    }
}
