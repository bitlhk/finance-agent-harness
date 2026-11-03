package io.github.bitlhk.finance.harness.core.spi;

import java.nio.file.Path;

/** Host-side authority factory for a Provider connection kind. */
public interface ManagedProviderRuntimeAuthorityV01 {
    String connectionKind();

    Session open(Context context) throws Exception;

    record Context(Path artifactRoot, String ownerRef, String instanceId, String sessionRef) {
        public Context {
            if (artifactRoot == null || ownerRef == null || ownerRef.isBlank()
                    || instanceId == null || instanceId.isBlank()
                    || sessionRef == null || sessionRef.isBlank())
                throw new SecurityException("PROVIDER_HOST_IDENTITY_REQUIRED");
        }
    }

    interface Session extends AutoCloseable {
        String channel();
        ManagedProviderFactoryV01.SourceSession sourceSession();
        BoundFinanceProvider.Grant grant();
        void current();
        @Override void close();
    }
}
