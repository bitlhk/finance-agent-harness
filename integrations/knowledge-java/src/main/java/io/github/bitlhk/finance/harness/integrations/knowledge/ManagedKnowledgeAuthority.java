package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import io.github.bitlhk.finance.harness.core.spi.ManagedProviderRuntimeAuthorityV01;
import java.nio.file.Path;

/** Host-owned knowledge authority for a managed business-agent session. */
public final class ManagedKnowledgeAuthority implements ManagedProviderRuntimeAuthorityV01.Session {
    private final KnowledgeAuthority authority;

    private ManagedKnowledgeAuthority(KnowledgeAuthority authority) {
        this.authority = authority;
    }

    static ManagedKnowledgeAuthority open(Path artifactRoot, String ownerRef,
            String instanceId, String sessionRef) throws Exception {
        if (ownerRef == null || ownerRef.isBlank() || instanceId == null || instanceId.isBlank()
                || sessionRef == null || sessionRef.isBlank())
            throw new SecurityException("KNOWLEDGE_HOST_IDENTITY_REQUIRED");
        return new ManagedKnowledgeAuthority(
                new KnowledgeAuthority(artifactRoot, ownerRef, instanceId, sessionRef));
    }

    public ManagedProviderFactoryV01.SourceSession sourceSession() {
        return authority.source();
    }

    @Override public String channel() {
        return "knowledge-isolated-host";
    }

    public BoundFinanceProvider.Grant grant() {
        return authority.grant();
    }

    /** Rechecks both the private Host lease and current Factory source access. */
    public void current() {
        authority.currentKnowledgeAccess();
    }

    @Override public void close() {
        authority.close();
    }
}
