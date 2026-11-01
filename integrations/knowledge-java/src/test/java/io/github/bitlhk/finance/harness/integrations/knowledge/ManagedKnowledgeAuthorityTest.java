package io.github.bitlhk.finance.harness.integrations.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;

final class ManagedKnowledgeAuthorityTest {
    @Test void rejectsMissingTrustedHostIdentityBeforeReadingAuthority() {
        var error = assertThrows(SecurityException.class,
                () -> new KnowledgeRuntimeAuthorityFactory().open(
                        new io.github.bitlhk.finance.harness.core.spi.ManagedProviderRuntimeAuthorityV01.Context(
                                Path.of("."), "", "instance", "session")));
        assertEquals("PROVIDER_HOST_IDENTITY_REQUIRED", error.getMessage());
    }
}
