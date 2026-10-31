package io.github.bitlhk.finance.harness.providers.knowledge;

import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    static final String PEER = "fixture-customer-facts";
    static final String PEER_CAP = "fixture.customer.read";
    FinanceProvider peer(String id, CapabilityRequestV1.Operation operation) {
        return new DescribedFinanceProvider() {
            public ProviderDescriptorV1 descriptor() {
                return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION, id, "0.1.0",
                    List.of(new ProviderDescriptorV1.CapabilitySupport(PEER_CAP, operation)));
            }
            public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError("No peer query during assembly"); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { throw new AssertionError("No peer task during assembly"); }
        };
    }
    ObjectNode document(String file) throws Exception {
        return (ObjectNode) KnowledgeBinding.JSON.readTree(root.resolve(file).toFile());
    }
    void joint() throws Exception {
        var plan = document("component-plan.json");
        ((ObjectNode) plan.path("capabilityBindings")).put(PEER_CAP, PEER);
        ((ObjectNode) plan.path("providerConnections")).putObject(PEER)
            .put("connectionRef", "customer-current").put("kind", "fixture-customer.v1");
        write("component-plan.json", plan);
        var bindings = document("provider-bindings.json");
        ((ObjectNode) bindings.path("providers")).putObject(PEER)
            .put("connectionRef", "customer-current").put("kind", "fixture-customer.v1")
            .put("endpoint", "http://127.0.0.1:2/customer").put("timeoutMillis", 1000).putObject("configuration");
        write("provider-bindings.json", bindings);
        lock(Set.of(PEER_CAP, KnowledgeProvider.SEARCH));
    }
    @Test void explicitPeerRegistryAllowsOnlyItsInstalledQuerySubset() throws Exception {
        joint();
        var peers = Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY));
        var installed = InstalledKnowledgeProvider.prepare(root, peers);
        assertEquals(Set.of(KnowledgeProvider.ID), installed.keySet());
        assertEquals(Set.of(PEER), peers.keySet());
        // Legacy knowledge-only callers do not inherit authority to compose other adapters.
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root));
        var runtime = InstalledKnowledgeProvider.create(root,
            () -> { throw new AssertionError("No knowledge session during assembly"); },
            () -> { throw new AssertionError("No knowledge grant during assembly"); }, () -> null,
            ignored -> { throw new AssertionError("No knowledge HTTP during assembly"); }, peers);
        assertEquals(Set.of(KnowledgeProvider.ID), runtime.keySet());
    }
    @Test void peerIdentityAndOperationAreNotTrustedFromBindingNames() throws Exception {
        joint();
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer("another-provider", CapabilityRequestV1.Operation.QUERY))));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.TASK))));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY),
                   KnowledgeProvider.ID, peer(PEER, CapabilityRequestV1.Operation.QUERY))));
    }
    @Test void peerCannotClaimAnUnboundOrUnknownCapability() throws Exception {
        joint();
        var plan = document("component-plan.json");
        ((ObjectNode) plan.path("capabilityBindings")).put("fixture.unknown.read", PEER);
        write("component-plan.json", plan);
        lock(Set.of(PEER_CAP, "fixture.unknown.read", KnowledgeProvider.SEARCH));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY))));
    }
    @Test void globalCapabilitySetMustMatchWithoutDuplicates() throws Exception {
        joint();
        var peers = Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY));
        lock(Set.of(KnowledgeProvider.SEARCH));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root, peers));
        KnowledgeBinding.JSON.writeValue(root.resolve("service-lock.json").toFile(),
            Map.of("files", files, "capabilities", List.of(PEER_CAP, PEER_CAP, KnowledgeProvider.SEARCH)));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root, peers));
    }
    @Test void aliasingKnowledgeConnectionToPeerTransportIsRejected() throws Exception {
        joint();
        var plan = document("component-plan.json");
        ((ObjectNode) plan.path("providerConnections").path(PEER)).put("connectionRef", "knowledge-local");
        write("component-plan.json", plan);
        var bindings = document("provider-bindings.json");
        ((ObjectNode) bindings.path("providers").path(PEER)).put("connectionRef", "knowledge-local");
        write("provider-bindings.json", bindings);
        lock(Set.of(PEER_CAP, KnowledgeProvider.SEARCH));
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY))));
    }
    @Test void unselectedPeerAndChangedGlobalFilesRemainRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root,
            Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY))));
        joint();
        var peers = Map.of(PEER, peer(PEER, CapabilityRequestV1.Operation.QUERY));
        Files.writeString(root.resolve("component-plan.json"), "{}");
        assertThrows(IllegalArgumentException.class, () -> InstalledKnowledgeProvider.prepare(root, peers));
    }
}
