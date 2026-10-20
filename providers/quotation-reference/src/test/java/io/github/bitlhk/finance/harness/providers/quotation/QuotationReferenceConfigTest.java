package io.github.bitlhk.finance.harness.providers.quotation;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.net.URI;
import java.time.Duration;
import io.github.bitlhk.finance.harness.core.spi.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QuotationReferenceConfigTest {
    @TempDir Path tmp;
    static Path fixture() { return Path.of("../../conformance/fixtures/quotation/v0.1/reference-config.v0.1.json"); }
    static String hash(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }
    Path copy(String name, String content) throws Exception { return Files.writeString(tmp.resolve(name), content); }
    @Test void actualCoreRegistryAcceptsIdentityAndRejectsMissingOrWrongLock() throws Exception {
        var factory = new QuotationReferenceFactoryV01(fixture().toAbsolutePath().toString(), hash(fixture()));
        var registry = new ManagedProviderFactoriesV01(List.of(factory));
        var selected = Set.of(QuotationReferenceProvider.ID);
        var expected = Map.of(QuotationReferenceProvider.ID, Map.of("configuration-file-sha256", hash(fixture())));
        assertEquals(expected, registry.configurationDigests(selected));
        var connection = new ManagedProviderFactoryV01.Connection(factory.connectionKind(), URI.create("reference://quotation"),
                Duration.ofSeconds(1), () -> { throw new AssertionError("Installation must not fetch session"); },
                () -> null, ignored -> fail("Installation must not dispatch HTTP"));
        assertEquals(selected, registry.create(connection,
                () -> { throw new AssertionError("Installation must not grant access"); }, selected, expected).keySet());
        var missing = assertThrows(IllegalArgumentException.class,
                () -> registry.create(connection, () -> null, selected));
        assertEquals("PROVIDER_CONFIGURATION_LOCK_MISMATCH", missing.getMessage());
        var wrong = assertThrows(IllegalArgumentException.class, () -> registry.create(connection, () -> null, selected,
                Map.of(QuotationReferenceProvider.ID, Map.of("configuration-file-sha256", "0".repeat(64)))));
        assertEquals("PROVIDER_CONFIGURATION_LOCK_MISMATCH", wrong.getMessage());
    }
    @Test void freezesActualBytesAndDoesNotReloadChangedSource() throws Exception {
        Path p = copy("source.json", Files.readString(fixture()));
        var config = QuotationReferenceConfig.load(p, hash(p));
        assertTrue(config.permits("demo-customer-a", "demo-service-001"));
        assertFalse(config.permits("other", "demo-service-001"));
        var facts = config.find("demo-customer-a", "demo-service-001", "CNY").orElseThrow();
        assertEquals(hash(p), facts.source().snapshotSha256());
        Files.writeString(p, "{}");
        assertEquals("900.00", facts.quote().referencePrice().value());
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(p, config.sha256()));
        var factory = new QuotationReferenceFactoryV01(fixture().toAbsolutePath().toString(), hash(fixture()));
        assertEquals(hash(fixture()), factory.configurationDigests().get("configuration-file-sha256"));
    }
    @Test void rejectsDigestSizeDuplicateUnknownAndAmbiguousRecords() throws Exception {
        String original = Files.readString(fixture());
        for (String content : new String[] {
                original.replace("\"sourceId\":", "\"unexpected\":true,\"sourceId\":"),
                original.replace("\"sourceId\":", "\"sourceId\":\"duplicate\",\"sourceId\":"),
                original.replace("\"schemaVersion\":", "\"grantedScopes\":[\"write\"],\"schemaVersion\":"),
                original.replace("\"productRefs\": [", "\"productRefs\": [\"other\","),
                " ".repeat(QuotationReferenceConfig.MAX_BYTES + 1) }) {
            Path p = copy("invalid.json", content);
            if (content.contains("\"other\"")) {
                // An additional configured product is a mapping, not authority; it is valid but has no record.
                var c = QuotationReferenceConfig.load(p, hash(p));
                assertTrue(c.find("demo-customer-a", "other", "CNY").isEmpty());
            } else assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(p, hash(p)));
        }
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(fixture(), "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(tmp, "0".repeat(64)));
        var mapper = io.github.bitlhk.finance.harness.quotation.QuotationJson.mapper();
        var tree = mapper.readTree(original);
        ((com.fasterxml.jackson.databind.node.ArrayNode) tree.get("records")).add(tree.get("records").get(0).deepCopy());
        Path duplicate = copy("records.json", tree.toString());
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(duplicate, hash(duplicate)));
    }
    @Test void rejectsFileAndParentSymlinks() throws Exception {
        Path source = copy("real.json", Files.readString(fixture()));
        try {
            Files.createSymbolicLink(tmp.resolve("link.json"), source);
            Files.createSymbolicLink(tmp.resolve("alias"), tmp);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unavailable) {
            Assumptions.abort("Platform cannot create symbolic links: " + unavailable.getClass().getSimpleName());
        }
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(tmp.resolve("link.json"), hash(source)));
        assertThrows(IllegalArgumentException.class, () -> QuotationReferenceConfig.load(tmp.resolve("alias/real.json"), hash(source)));
    }
}
