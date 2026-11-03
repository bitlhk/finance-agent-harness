package io.github.bitlhk.finance.harness.integrations.knowledge;

import io.github.bitlhk.finance.harness.core.spi.ManagedProviderRuntimeAuthorityV01;
import io.github.bitlhk.finance.harness.providers.knowledge.KnowledgeFactoryV01;

/** Discovers knowledge authority through the generic Host runtime SPI. */
public final class KnowledgeRuntimeAuthorityFactory implements ManagedProviderRuntimeAuthorityV01 {
    @Override public String connectionKind() {
        return KnowledgeFactoryV01.KIND;
    }

    @Override public Session open(Context context) throws Exception {
        return ManagedKnowledgeAuthority.open(context.artifactRoot(), context.ownerRef(),
                context.instanceId(), context.sessionRef());
    }
}
