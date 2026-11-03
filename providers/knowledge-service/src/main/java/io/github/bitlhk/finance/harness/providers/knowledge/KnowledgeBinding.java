package io.github.bitlhk.finance.harness.providers.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Host-owned, digest-pinned instance binding. Never supplied by a tool call. */
public record KnowledgeBinding(String productId, String manifest, LocalDate businessDate,
        String businessBindingDigest, String sha256) {
    static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public KnowledgeBinding {
        if (productId == null || !productId.matches("[a-z0-9-]{1,80}")
                || manifest == null || !manifest.matches("[a-f0-9]{64}") || businessDate == null
                || (businessBindingDigest != null && !businessBindingDigest.matches("[a-f0-9]{64}"))
                || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("KNOWLEDGE_BINDING_INVALID");
    }
    static KnowledgeBinding load(String filename, String expected) {
        try {
            Path file = Path.of(filename);
            if (!file.isAbsolute() || Files.isSymbolicLink(file) || Files.size(file) > 8192)
                throw new IllegalArgumentException();
            byte[] bytes = Files.readAllBytes(file);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expected)) throw new IllegalArgumentException();
            var node = JSON.readTree(bytes);
            String version = node.path("schemaVersion").asText();
            if (!(node.size() == 4 && "knowledge-binding.v0.1".equals(version))
                    && !(node.size() == 5 && "knowledge-binding.v0.2".equals(version)
                        && node.path("businessBindingDigest").asText().matches("[a-f0-9]{64}")))
                throw new IllegalArgumentException();
            return new KnowledgeBinding(node.get("productId").textValue(), node.get("manifest").textValue(),
                LocalDate.parse(node.get("businessDate").textValue()),
                "knowledge-binding.v0.2".equals(version) ? node.get("businessBindingDigest").textValue() : null,
                actual);
        } catch (Exception error) { throw new IllegalArgumentException("KNOWLEDGE_BINDING_INVALID"); }
    }
}
