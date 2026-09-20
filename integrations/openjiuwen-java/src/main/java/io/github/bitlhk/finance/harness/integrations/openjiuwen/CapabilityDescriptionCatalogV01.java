package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
/** Retains the OpenJiuwen API/JSON records while delegating compilation to neutral assembly. */
public final class CapabilityDescriptionCatalogV01 {
    public static final String VERSION = io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.VERSION;
    private final io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01 delegate;
    private CapabilityDescriptionCatalogV01(io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01 delegate) { this.delegate = delegate; }
    private static List<io.github.bitlhk.finance.harness.assembly.FinanceCapabilityPackV1> packs(List<FinanceCapabilityPackV1> packs) {
        return packs.stream().map(p -> HarnessJson.mapper().convertValue(p, io.github.bitlhk.finance.harness.assembly.FinanceCapabilityPackV1.class)).toList();
    }
    public static CapabilityDescriptionCatalogV01 base(List<FinanceCapabilityPackV1> packs) {
        return new CapabilityDescriptionCatalogV01(io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.base(packs(packs)));
    }
    public static CapabilityDescriptionCatalogV01 load(List<FinanceCapabilityPackV1> packs, List<Path> descriptions, List<Path> terms) throws IOException {
        return new CapabilityDescriptionCatalogV01(io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.load(packs(packs), descriptions, terms));
    }
    public static CapabilityDescriptionCatalogV01 compile(List<FinanceCapabilityPackV1> packs, List<DescriptionAsset> descriptions, List<TerminologyAsset> terms) {
        return new CapabilityDescriptionCatalogV01(io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.compile(packs(packs),
            descriptions.stream().map(d -> HarnessJson.mapper().convertValue(d, io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.DescriptionAsset.class)).toList(),
            terms.stream().map(t -> HarnessJson.mapper().convertValue(t, io.github.bitlhk.finance.harness.assembly.CapabilityDescriptionCatalogV01.TerminologyAsset.class)).toList()));
    }
    public Set<String> capabilityIds() { return delegate.capabilityIds(); }
    public boolean allReadOnly() { return delegate.allReadOnly(); }
    public Snapshot snapshot() { return HarnessJson.mapper().convertValue(delegate.snapshot(), Snapshot.class); }
    public String description(String id) { return delegate.description(id); }
    public String terminologyPrompt() { return delegate.terminologyPrompt(); }
    public void requireExactInstallation(Set<String> actual) { delegate.requireExactInstallation(actual); }
    public record DescriptionAsset(String schemaVersion, String assetId, String version,
                                   String packId, String packVersion, List<Description> capabilities) {
        public DescriptionAsset { capabilities = capabilities == null ? List.of() : List.copyOf(capabilities); }
        void validate() {
            require("finance-capability-descriptions.v0.1".equals(schemaVersion), "Unsupported description schema");
            require(text(assetId) && text(version) && text(packId) && text(packVersion), "Description identity is required");
            require(!capabilities.isEmpty(), "Description capabilities must not be empty");
        }
    }
    public record Description(String capabilityId, List<String> useCases, List<String> outputBoundaries,
                              List<String> exampleUtterances, List<String> negativeExamples) {
        public Description {
            useCases = clean(useCases); outputBoundaries = clean(outputBoundaries);
            exampleUtterances = clean(exampleUtterances); negativeExamples = clean(negativeExamples);
            require(text(capabilityId), "Description capabilityId is required");
            require(!useCases.isEmpty() && !exampleUtterances.isEmpty() && !negativeExamples.isEmpty(),
                    "Description use cases, utterances and negative examples are required");
        }
    }
    public record TerminologyAsset(String schemaVersion, String assetId, String version, String institution,
                                   List<Term> terms) {
        public TerminologyAsset { terms = terms == null ? List.of() : List.copyOf(terms); }
        void validate() {
            require("finance-terminology-pack.v0.1".equals(schemaVersion), "Unsupported terminology schema");
            require(text(assetId) && text(version) && text(institution), "Terminology identity is required");
            require(!terms.isEmpty(), "Terminology terms must not be empty");
        }
    }
    public record Term(String termId, String canonicalTerm, List<String> aliases, String definition,
                       String scope, SupportStatus supportStatus, List<String> capabilityIds, String sourceRef) {
        public Term {
            aliases = clean(aliases); capabilityIds = clean(capabilityIds);
            require(text(termId) && text(canonicalTerm) && text(definition) && text(scope) && text(sourceRef),
                    "Terminology fields are required");
            Objects.requireNonNull(supportStatus, "supportStatus");
            if (supportStatus == SupportStatus.SUPPORTED) require(!capabilityIds.isEmpty(), "Supported term needs capabilityIds");
        }
    }
    public enum SupportStatus { SUPPORTED, KNOWN_UNSUPPORTED }
    public record Snapshot(String schemaVersion, String digest, String toolDescriptionsDigest,
                           String terminologyPromptDigest, List<SnapshotEntry> capabilities,
                           List<String> descriptionAssets, List<String> terminologyAssets) {
        public Snapshot {
            require(text(digest) && text(toolDescriptionsDigest) && text(terminologyPromptDigest),
                    "Snapshot digests are required");
            capabilities = List.copyOf(capabilities); descriptionAssets = List.copyOf(descriptionAssets);
            terminologyAssets = List.copyOf(terminologyAssets);
        }
    }
    public record SnapshotEntry(String capabilityId, String toolName, String operation, String description) { }

    private static List<String> clean(List<String> values) {
        if (values == null) return List.of();
        List<String> result = values.stream().filter(Objects::nonNull).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
        require(result.size() == values.size(), "Lists must contain unique non-blank values");
        return result;
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
}
