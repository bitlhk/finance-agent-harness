package io.github.bitlhk.finance.harness.providers.creditaudit;

import io.github.bitlhk.finance.harness.core.spi.ManagedProviderFactoryV01;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnterpriseCreditMaterialAuditFactoryV01Test {
    @Test
    void installationCanInspectDescriptorBeforeRuntimeWorkspaceIsBound() {
        var factory = new EnterpriseCreditMaterialAuditFactoryV01(Map.of());
        var connection = new ManagedProviderFactoryV01.Connection(
                "local-credit-materials.v1", URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1),
                () -> { throw new AssertionError("installation must not request a session"); },
                () -> null, ignored -> {});
        var provider = factory.create(connection, Map.of());

        assertEquals(EnterpriseCreditMaterialAuditProvider.ID, provider.descriptor().providerId());
        assertEquals(factory.requirements().keySet(), provider.descriptor().capabilities().stream()
                .map(item -> item.capabilityId()).collect(java.util.stream.Collectors.toSet()));
    }
}
