package io.github.bitlhk.finance.harness.providers.quotation;

import io.github.bitlhk.finance.harness.quotation.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Frozen synthetic data, not a grant or a live banking source. */
public final class QuotationReferenceConfig {
    public static final int MAX_BYTES = 262144;
    public record Subject(String subjectRef, List<String> productRefs) {
        public Subject {
            QuoteFactsV01.id(subjectRef); productRefs = List.copyOf(productRefs);
            QuoteFactsV01.require(!productRefs.isEmpty() && productRefs.size() <= 128
                && new HashSet<>(productRefs).size() == productRefs.size());
            productRefs.forEach(QuoteFactsV01::id);
        }
    }
    public record Entry(String subjectRef, String productRef, String currency, QuoteFactsV01.State state,
            QuoteFactsV01.Quote quote, List<String> gaps, String recordRef, String observedAt) {
        public Entry {
            QuoteFactsV01.id(subjectRef); QuoteFactsV01.id(productRef); QuoteFactsV01.currency(currency);
            Objects.requireNonNull(state); gaps = List.copyOf(gaps);
            QuoteFactsV01.id(recordRef); QuoteFactsV01.instant(observedAt);
        }
    }
    public record Wire(String schemaVersion, String sourceId, String sourceVersion,
            List<Subject> subjects, List<Entry> records) {
        public Wire {
            QuoteFactsV01.require("quotation-reference-config.v0.1".equals(schemaVersion));
            QuoteFactsV01.id(sourceId); QuoteFactsV01.id(sourceVersion);
            subjects = List.copyOf(subjects); records = List.copyOf(records);
            QuoteFactsV01.require(!subjects.isEmpty() && subjects.size() <= 128
                && !records.isEmpty() && records.size() <= 128);
        }
    }
    private record Key(String subjectRef, String productRef, String currency) { }
    private final Map<String, Set<String>> products;
    private final Map<Key, QuoteFactsV01> facts;
    private final String sha256;
    private QuotationReferenceConfig(Wire wire, String digest) {
        sha256 = digest;
        Map<String,Set<String>> scopes = new HashMap<>();
        for (Subject subject : wire.subjects()) {
            QuoteFactsV01.require(scopes.put(subject.subjectRef(), Set.copyOf(subject.productRefs())) == null);
        }
        Map<Key,QuoteFactsV01> values = new HashMap<>();
        Set<String> recordIds = new HashSet<>(), quoteIds = new HashSet<>();
        for (Entry entry : wire.records()) {
            QuoteFactsV01.require(scopes.getOrDefault(entry.subjectRef(), Set.of()).contains(entry.productRef())
                && recordIds.add(entry.recordRef()));
            if (entry.quote() != null) QuoteFactsV01.require(quoteIds.add(entry.quote().quoteId()));
            var source = new QuoteFactsV01.Source("CONTROLLED_FIXTURE", wire.sourceId(), wire.sourceVersion(),
                entry.recordRef(), entry.observedAt(), digest);
            var value = new QuoteFactsV01(QuoteFactsV01.VERSION, entry.subjectRef(), entry.productRef(),
                entry.currency(), entry.state(), entry.quote(), entry.gaps(), source);
            QuoteFactsV01.require(values.put(new Key(entry.subjectRef(), entry.productRef(), entry.currency()), value) == null);
        }
        products = Map.copyOf(scopes); facts = Map.copyOf(values);
    }
    public String sha256() { return sha256; }
    public boolean permits(String subject, String product) { return products.getOrDefault(subject, Set.of()).contains(product); }
    public Optional<QuoteFactsV01> find(String subject, String product, String currency) {
        return Optional.ofNullable(facts.get(new Key(subject, product, currency)));
    }
    public QuoteDisclosureV01.ExpectedBinding binding(QuoteFactsV01 value) {
        return new QuoteDisclosureV01.ExpectedBinding(value.subjectRef(), value.productRef(), value.currency(),
            QuoteFactsV01.VERSION, value.source().sourceId(), value.source().sourceVersion(), sha256);
    }
    private static void regularPath(Path path) throws Exception {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path cursor = absolute; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IllegalArgumentException();
            // Resolves directory junctions too; protects against non-symbolic reparse aliases.
            if (!cursor.toRealPath().equals(cursor)) throw new IllegalArgumentException();
        }
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException();
    }
    public static QuotationReferenceConfig load(Path path, String expectedSha256) {
        try {
            QuoteFactsV01.digest(expectedSha256); regularPath(path);
            var before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() > MAX_BYTES) throw new IllegalArgumentException();
            byte[] bytes;
            try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException();
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!actual.equals(expectedSha256)) throw new IllegalArgumentException();
            regularPath(path);
            var after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IllegalArgumentException();
            var wire = QuotationJson.mapper().readValue(bytes, Wire.class);
            return new QuotationReferenceConfig(wire, actual);
        } catch (Exception invalid) { throw new IllegalArgumentException("QUOTATION_CONFIG_INVALID"); }
    }
}
