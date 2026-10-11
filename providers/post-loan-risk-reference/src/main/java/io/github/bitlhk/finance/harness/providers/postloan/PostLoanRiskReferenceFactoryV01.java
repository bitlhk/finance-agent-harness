package io.github.bitlhk.finance.harness.providers.postloan;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.util.*;
public final class PostLoanRiskReferenceFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String CONNECTION_KIND="post-loan-risk-readonly.v1";
    @Override public String providerId(){return PostLoanRiskReferenceProvider.PROVIDER_ID;}
    @Override public String connectionKind(){return CONNECTION_KIND;}
    @Override public Map<String,Set<String>> requirements(){return Map.of(PostLoanRiskReferenceProvider.SNAPSHOT,Set.of("postloan-risk:read"),PostLoanRiskReferenceProvider.ESCALATION,Set.of("postloan-risk:read"));}
    @Override public DescribedFinanceProvider create(Connection connection,Map<String,BoundFinanceProvider> dependencies){if(!dependencies.isEmpty()||!CONNECTION_KIND.equals(connection.kind()))throw new IllegalArgumentException("POSTLOAN_CONNECTION_INVALID");return new PostLoanRiskReferenceProvider();}
}
