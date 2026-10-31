package io.github.bitlhk.finance.harness.providers.knowledge;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class InstalledKnowledgeProviderTest {
    @TempDir Path root;
    Map<String,String> files;
    final String configuration = "provider-configuration/knowledge-service/knowledge-binding.json";
    void write(String name, Object value) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent());
        byte[] raw = KnowledgeBinding.JSON.writeValueAsBytes(value); Files.write(file, raw);
        files.put(name, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)));
    }
    @BeforeEach void prepare() throws Exception {
        files = new HashMap<>();
        write(configuration, Map.of("schemaVersion", "knowledge-binding.v0.1", "productId", "test-product",
                "manifest", "a".repeat(64), "businessDate", "2026-10-30"));
        var reference = Map.of("connectionRef", "knowledge-local", "kind", KnowledgeFactoryV01.KIND);
        write("component-plan.json", Map.of("capabilityBindings", Map.of(KnowledgeProvider.SEARCH, KnowledgeProvider.ID),
                "providerConnections", Map.of(KnowledgeProvider.ID, reference)));
        write("provider-bindings.json", Map.of("schemaVersion", "finance-host-provider-bindings.v0.1", "providers",
                Map.of(KnowledgeProvider.ID, Map.of("connectionRef", "knowledge-local", "kind", KnowledgeFactoryV01.KIND,
                "endpoint", "http://127.0.0.1:1/query", "timeoutMillis", 1000,
                "configuration", Map.of("knowledge-binding", Map.of("path", configuration, "sha256", files.get(configuration)))))));
        lock(Set.of(KnowledgeProvider.SEARCH));
    }
    void lock(Set<String> capabilities) throws Exception {
        KnowledgeBinding.JSON.writeValue(root.resolve("service-lock.json").toFile(), Map.of("files", files, "capabilities", capabilities));
    }
    @Test void preparationDoesNotAcquireAuthorityOrContactEndpoint() {
        var installed = InstalledKnowledgeProvider.prepare(root);
        assertEquals(Set.of(KnowledgeProvider.ID), installed.keySet());
        assertInstanceOf(DescribedFinanceProvider.class, installed.get(KnowledgeProvider.ID));
    }
    @Test void runtimeConstructionDoesNotAcquireAuthority() {
        var installed = InstalledKnowledgeProvider.create(root,
            () -> { throw new AssertionError("Session requested during construction"); },
            () -> { throw new AssertionError("Grant requested during construction"); }, () -> null,
            ignored -> { throw new AssertionError("HTTP during construction"); });
        assertEquals(1, installed.size());
    }
    @Test void mutatedBindingRejected() throws Exception {
        Files.writeString(root.resolve(configuration), "{}");
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root));
    }
    @Test void missingLockRejected() throws Exception {
        Files.delete(root.resolve("service-lock.json"));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root));
    }
    @Test void ruleCapabilityCannotEnterEvidenceOnlyInstallation() throws Exception {
        lock(Set.of(KnowledgeProvider.SEARCH, "knowledge.rule.evaluate"));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root));
    }
    @Test void tamperedConnectionRejectedEvenWhenFileIsRehashed() throws Exception {
        var bindings = (com.fasterxml.jackson.databind.node.ObjectNode) KnowledgeBinding.JSON.readTree(root.resolve("provider-bindings.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)bindings.path("providers").path(KnowledgeProvider.ID))
            .put("endpoint", "http://127.0.0.1:1/unreviewed");
        write("provider-bindings.json", bindings); lock(Set.of(KnowledgeProvider.SEARCH));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root));
    }
}
