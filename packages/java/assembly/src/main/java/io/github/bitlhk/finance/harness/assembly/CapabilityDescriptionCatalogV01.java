package io.github.bitlhk.finance.harness.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.HarnessJson;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Compiles optional, runtime-neutral routing descriptions into Runtime tool text. */
public final class CapabilityDescriptionCatalogV01 {
    public static final String VERSION = "finance-capability-description-catalog.v0.1";
    private static final ObjectMapper JSON = HarnessJson.mapper();

    private final Map<String, Entry> entries;
    private final List<Term> terms;
    private final Snapshot snapshot;

    private CapabilityDescriptionCatalogV01(Map<String, Entry> entries, List<Term> terms, Snapshot snapshot) {
        this.entries = Map.copyOf(entries);
        this.terms = List.copyOf(terms);
        this.snapshot = snapshot;
    }

    public static CapabilityDescriptionCatalogV01 base(List<FinanceCapabilityPackV1> packs) {
        return compile(packs, List.of(), List.of());
    }

    public static CapabilityDescriptionCatalogV01 load(
            List<FinanceCapabilityPackV1> packs, List<Path> descriptionFiles, List<Path> terminologyFiles)
            throws IOException {
        List<DescriptionAsset> descriptions = new ArrayList<>();
        for (Path file : descriptionFiles) descriptions.add(JSON.readValue(file.toFile(), DescriptionAsset.class));
        List<TerminologyAsset> terminology = new ArrayList<>();
        for (Path file : terminologyFiles) terminology.add(JSON.readValue(file.toFile(), TerminologyAsset.class));
        return compile(packs, descriptions, terminology);
    }

    public static CapabilityDescriptionCatalogV01 compile(
            List<FinanceCapabilityPackV1> packs,
            List<DescriptionAsset> descriptionAssets,
            List<TerminologyAsset> terminologyAssets) {
        Objects.requireNonNull(packs, "packs");
        Map<String, FinanceCapabilityPackV1.Capability> installed = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        for (FinanceCapabilityPackV1 pack : packs) for (FinanceCapabilityPackV1.Capability capability : pack.capabilities()) {
            if (installed.putIfAbsent(capability.capabilityId(), capability) != null)
                throw new IllegalArgumentException("Duplicate installed capability: " + capability.capabilityId());
            owners.put(capability.capabilityId(), pack.packId() + "@" + pack.version());
        }

        Map<String, Description> supplied = new LinkedHashMap<>();
        List<String> descriptionVersions = new ArrayList<>();
        for (DescriptionAsset asset : descriptionAssets) {
            asset.validate();
            String assetOwner = asset.packId() + "@" + asset.packVersion();
            if (!owners.containsValue(assetOwner))
                throw new IllegalArgumentException("Description asset does not match an installed pack: " + assetOwner);
            descriptionVersions.add(asset.assetId() + "@" + asset.version());
            for (Description description : asset.capabilities()) {
                if (!installed.containsKey(description.capabilityId())) continue;
                if (!owners.get(description.capabilityId()).equals(assetOwner))
                    throw new IllegalArgumentException("Description pack reference does not match installed pack: "
                            + description.capabilityId());
                if (supplied.putIfAbsent(description.capabilityId(), description) != null)
                    throw new IllegalArgumentException("Duplicate capability description: " + description.capabilityId());
            }
        }

        Map<String, Term> termsById = new LinkedHashMap<>();
        List<String> terminologyVersions = new ArrayList<>();
        for (TerminologyAsset asset : terminologyAssets) {
            asset.validate();
            terminologyVersions.add(asset.assetId() + "@" + asset.version());
            for (Term term : asset.terms()) {
                List<String> visibleCapabilities = term.capabilityIds().stream().filter(installed::containsKey).toList();
                if (term.supportStatus() == SupportStatus.SUPPORTED && visibleCapabilities.isEmpty()) continue;
                Term visible = visibleCapabilities.equals(term.capabilityIds()) ? term : new Term(term.termId(),
                        term.canonicalTerm(), term.aliases(), term.definition(), term.scope(), term.supportStatus(),
                        visibleCapabilities, term.sourceRef());
                if (termsById.putIfAbsent(visible.termId(), visible) != null)
                    throw new IllegalArgumentException("Duplicate terminology id: " + term.termId());
                if (term.supportStatus() == SupportStatus.KNOWN_UNSUPPORTED && !term.capabilityIds().isEmpty())
                    throw new IllegalArgumentException("Unsupported terminology cannot bind a capability: " + term.termId());
            }
        }

        Map<String, Entry> entries = new LinkedHashMap<>();
        for (var installedEntry : installed.entrySet()) {
            String id = installedEntry.getKey();
            FinanceCapabilityPackV1.Capability capability = installedEntry.getValue();
            Description description = supplied.get(id);
            List<Term> matchedTerms = termsById.values().stream()
                    .filter(term -> term.capabilityIds().contains(id)).toList();
            entries.put(id, new Entry(id, capability.toolName(), capability.operation().name(),
                    capability.description(), description, matchedTerms));
        }
        descriptionVersions.sort(String::compareTo);
        terminologyVersions.sort(String::compareTo);
        List<SnapshotEntry> snapshotEntries = entries.values().stream()
                .map(Entry::snapshot).sorted(Comparator.comparing(SnapshotEntry::capabilityId)).toList();
        List<Term> visibleTerms = new ArrayList<>(termsById.values());
        String descriptionsDigest = digest(snapshotEntries);
        String terminologyPromptDigest = digest(renderTerminologyPrompt(visibleTerms));
        String digest = digest(Map.of(
                "schemaVersion", VERSION,
                "toolDescriptionsDigest", descriptionsDigest,
                "terminologyPromptDigest", terminologyPromptDigest,
                "capabilities", snapshotEntries,
                "descriptionAssets", descriptionVersions,
                "terminologyAssets", terminologyVersions));
        return new CapabilityDescriptionCatalogV01(entries, visibleTerms,
                new Snapshot(VERSION, digest, descriptionsDigest, terminologyPromptDigest, snapshotEntries,
                        List.copyOf(descriptionVersions), List.copyOf(terminologyVersions)));
    }

    public Set<String> capabilityIds() { return entries.keySet(); }
    public boolean allReadOnly() {
        return entries.values().stream().allMatch(entry -> entry.operation().equals("QUERY"));
    }
    public Snapshot snapshot() { return snapshot; }
    public String description(String capabilityId) {
        Entry entry = entries.get(capabilityId);
        if (entry == null) throw new IllegalArgumentException("Capability description is not installed: " + capabilityId);
        return entry.render();
    }

    public String terminologyPrompt() {
        return renderTerminologyPrompt(terms);
    }

    private static String renderTerminologyPrompt(List<Term> terms) {
        if (terms.isEmpty()) return "";
        StringBuilder prompt = new StringBuilder("机构术语仅用于理解用户表达，不授予权限，也不新增工具。"
                + "用户要求的目标能力未安装时，应说明当前不支持，不得用输出范围不同的相邻能力替代。\n");
        for (Term term : terms) {
            prompt.append("- ").append(term.canonicalTerm());
            if (!term.aliases().isEmpty()) prompt.append("（别名：").append(String.join("、", term.aliases())).append("）");
            prompt.append("：").append(term.definition()).append("；");
            if (term.supportStatus() == SupportStatus.KNOWN_UNSUPPORTED)
                prompt.append("当前没有对应能力，不得调用其他工具代替。\n");
            else prompt.append("对应能力：").append(String.join("、", term.capabilityIds())).append("。\n");
        }
        return prompt.toString().trim();
    }

    public void requireExactInstallation(Set<String> actualCapabilityIds) {
        if (!new LinkedHashSet<>(entries.keySet()).equals(new LinkedHashSet<>(actualCapabilityIds)))
            throw new IllegalStateException("CAPABILITY_DESCRIPTION_INSTALLATION_MISMATCH: catalog="
                    + entries.keySet() + ", installed=" + actualCapabilityIds);
    }

    private static String digest(Object value) {
        try {
            byte[] bytes = JSON.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsBytes(value);
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest capability description catalog", exception);
        }
    }

    private record Entry(String capabilityId, String toolName, String operation, String baseDescription,
                         Description description, List<Term> terms) {
        String render() {
            if (description == null) return baseDescription + " [capabilityId=" + capabilityId + "]";
            StringBuilder value = new StringBuilder(baseDescription);
            value.append("\n适用：").append(String.join("；", description.useCases()));
            if (!description.outputBoundaries().isEmpty())
                value.append("\n输出边界：").append(String.join("；", description.outputBoundaries()));
            value.append("\n典型问法：").append(String.join("；", description.exampleUtterances()));
            value.append("\n不适用：").append(String.join("；", description.negativeExamples()));
            if (!terms.isEmpty()) value.append("\n相关机构术语：").append(terms.stream()
                    .map(Term::canonicalTerm).sorted().reduce((a, b) -> a + "、" + b).orElse(""));
            value.append("\n[capabilityId=").append(capabilityId).append(']');
            return value.toString();
        }
        SnapshotEntry snapshot() {
            return new SnapshotEntry(capabilityId, toolName, operation, render());
        }
    }

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
