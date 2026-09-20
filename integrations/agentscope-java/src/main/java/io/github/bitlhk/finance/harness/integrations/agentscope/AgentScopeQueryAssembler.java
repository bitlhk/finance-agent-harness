package io.github.bitlhk.finance.harness.integrations.agentscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.github.bitlhk.finance.harness.assembly.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

/** Build-time QUERY admission only. AgentScope owns all reasoning and tool iteration. */
public final class AgentScopeQueryAssembler {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    public record AssetRef(String id, String version, String source, String digest) {}
    public record RuntimeRef(String family, String language, String templateRef, String integrationRef,
                             Map<String, String> resolvedArtifacts) {}
    public record SolutionSpec(String schemaVersion, String solutionId, String displayName, RuntimeRef runtime,
                               AssetRef profileRef, List<AssetRef> domainPackRefs, List<AssetRef> capabilityPackRefs,
                               List<AssetRef> providerRefs, Map<String, String> capabilityBindings, String modelRef,
                               AssetRef evaluationPackRef) {
        public SolutionSpec {
            domainPackRefs = domainPackRefs == null ? List.of() : List.copyOf(domainPackRefs);
            capabilityPackRefs = capabilityPackRefs == null ? List.of() : List.copyOf(capabilityPackRefs);
            providerRefs = providerRefs == null ? List.of() : List.copyOf(providerRefs);
            capabilityBindings = capabilityBindings == null ? Map.of() : Map.copyOf(capabilityBindings);
        }
    }
    public record Support(String schemaVersion, String family, String language, String sdkVersion, String operation,
                          int modelAttempts, int toolAttempts, boolean stream, boolean fallback,
                          boolean pendingToolRecovery, List<AssetRef> descriptions, List<AssetRef> terminology,
                          List<ObservationRequirementsV01.Reference> observationRequirements,
                          List<ProfileCompatibility> profiles, List<ProviderCompatibility> providers) {
        public Support {
            if (!"agentscope-query-support.v0.1".equals(schemaVersion) || !"agentscope".equals(family)
                    || !"java".equals(language) || !"2.0.3".equals(sdkVersion) || !"QUERY".equals(operation)
                    || modelAttempts != 1 || toolAttempts != 1 || stream || fallback || pendingToolRecovery)
                throw new IllegalArgumentException("UNSUPPORTED_AGENTSCOPE_MODE");
            descriptions = List.copyOf(descriptions); terminology = List.copyOf(terminology);
            observationRequirements = List.copyOf(observationRequirements);
            profiles = List.copyOf(profiles); providers = List.copyOf(providers);
            if (profiles.isEmpty() || providers.isEmpty()) throw new IllegalArgumentException("HOST_COMPATIBILITY_DECLARATIONS_REQUIRED");
            if (observationRequirements.isEmpty()) throw new IllegalArgumentException("OBSERVATION_REQUIREMENTS_REQUIRED");
        }
    }
    public record ProfileCompatibility(AssetRef profile, Set<String> allowedCapabilityIds, Set<String> domainProfiles,
                                       Set<FinanceContextV1.PrincipalType> principalTypes, Set<String> purposes) {
        public ProfileCompatibility {
            Objects.requireNonNull(profile); allowedCapabilityIds = Set.copyOf(allowedCapabilityIds); domainProfiles = Set.copyOf(domainProfiles);
            principalTypes = Set.copyOf(principalTypes); purposes = Set.copyOf(purposes);
            if (allowedCapabilityIds.isEmpty() || principalTypes.isEmpty() || purposes.isEmpty()) throw new IllegalArgumentException("PROFILE_COMPATIBILITY_EMPTY");
        }
    }
    public record ProviderCompatibility(String id, String version, String artifactRef, String connectionKind) {}
    public record LockedAsset(String kind, String id, String version, String source, String digest) {}
    private record Owner(String id, String version) {}
    private record ProviderSupport(Owner pack, List<String> capabilityIds, String operation) {}
    private record ProviderDeclaration(String schemaVersion, String kind, Owner owner, String artifactRef, String connectionKind, List<ProviderSupport> supports) {}
    private record PackDeclaration(String schemaVersion, String kind, Owner owner, Set<String> requiredFeatures, List<Owner> defaultDomainPacks) {}
    record Binding(FinanceCapabilityPackV1.Capability capability, DescribedFinanceProvider provider) {}

    public Prepared prepare(Path root, Path solution, Path supportFile, Map<String, DescribedFinanceProvider> providers,
                            RuntimeQualification qualification) throws IOException {
        return prepare(root, root, solution, supportFile, providers, qualification);
    }

    /** Candidate control files and repo-relative assets have separate, explicitly bounded roots. */
    public Prepared prepare(Path candidateRoot, Path root, Path solution, Path supportFile,
                            Map<String, DescribedFinanceProvider> providers, RuntimeQualification qualification) throws IOException {
        Objects.requireNonNull(qualification, "Host-pinned runtime qualification is required");
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)
                || !root.toRealPath().startsWith(candidateRoot.toRealPath()))
            throw new IllegalArgumentException("ASSET_ROOT_OUTSIDE_CANDIDATE");
        SolutionSpec spec = YAML.readValue(bounded(candidateRoot, solution), SolutionSpec.class);
        if (!"finance-agent-solution-build.v1".equals(spec.schemaVersion()) || spec.solutionId() == null
                || spec.solutionId().isBlank() || spec.runtime() == null
                || !"agentscope".equals(spec.runtime().family()) || !"java".equals(spec.runtime().language())
                || !"maven://io.agentscope:agentscope-core:2.0.3".equals(spec.runtime().templateRef())
                || !("maven://io.github.bitlhk.finance:finance-harness-agentscope-java:" + RuntimeInventory.INTEGRATION_VERSION).equals(spec.runtime().integrationRef()))
            throw new IllegalArgumentException("AGENTSCOPE_RUNTIME_REFERENCE_MISMATCH");
        if (spec.runtime().resolvedArtifacts() != null) {
            if (!Map.of("templateRef", spec.runtime().templateRef(), "integrationRef", spec.runtime().integrationRef()).equals(spec.runtime().resolvedArtifacts()))
                throw new IllegalArgumentException("AGENTSCOPE_RESOLVED_REFERENCE_MISMATCH");
        }
        Support support = HarnessJson.mapper().readValue(bounded(candidateRoot, supportFile), Support.class);
        List<LockedAsset> assets = new ArrayList<>();
        assets.add(new LockedAsset("SOLUTION", spec.solutionId(), "1", relative(candidateRoot, solution), AssetDigests.sha256(solution)));
        assets.add(new LockedAsset("SUPPORT_PROFILE", "agentscope-query-single", "0.1", relative(candidateRoot, supportFile), AssetDigests.sha256(supportFile)));
        List<FinanceCapabilityPackV1> packs = new ArrayList<>();
        Set<String> packIds = new HashSet<>();
        Map<String, FinanceCapabilityPackV1.Capability> capabilities = new LinkedHashMap<>();
        Set<String> tools = new HashSet<>();
        for (AssetRef ref : spec.capabilityPackRefs()) {
            Path file = resolve(root, ref, false, "CAPABILITY_PACK", assets);
            var pack = new FinanceCapabilityPackLoader().load(file);
            identity(ref, pack.packId(), pack.version());
            Path sidecar = file.getParent().resolve("assembly.v0.1.json");
            var declaration = HarnessJson.mapper().readValue(bounded(root, sidecar), PackDeclaration.class);
            if (!"finance-component-assembly.v0.1".equals(declaration.schemaVersion()) || !"capability-pack".equals(declaration.kind())
                    || !ref.id().equals(declaration.owner().id()) || !ref.version().equals(declaration.owner().version())
                    || declaration.requiredFeatures() == null || !Set.of("query.v1", "trusted-grants.v1", "observation.v0.1").containsAll(declaration.requiredFeatures()))
                throw new IllegalArgumentException("PACK_ASSEMBLY_NOT_SUPPORTED");
            assets.add(new LockedAsset("CAPABILITY_ASSEMBLY", pack.packId(), pack.version(), relative(root, sidecar), AssetDigests.sha256(sidecar)));
            if (!packIds.add(pack.packId())) throw new IllegalArgumentException("DUPLICATE_CAPABILITY_PACK");
            for (var capability : pack.capabilities()) {
                if (capability.operation() != FinanceCapabilityPackV1.Operation.QUERY) throw new IllegalArgumentException("QUERY_ONLY");
                new QueryInputValidator(capability.inputSchema());
                if (capabilities.putIfAbsent(capability.capabilityId(), capability) != null || !tools.add(capability.toolName()))
                    throw new IllegalArgumentException("DUPLICATE_CAPABILITY_OR_TOOL");
            }
            packs.add(pack);
        }
        if (capabilities.isEmpty()) throw new IllegalArgumentException("QUERY_CAPABILITIES_REQUIRED");
        List<FinanceDomainPackLoader.LoadedDomainPack> domains = new ArrayList<>();
        Set<String> domainIds = new HashSet<>();
        for (AssetRef ref : spec.domainPackRefs()) {
            Path file = resolve(root, ref, true, "DOMAIN_PACK", assets);
            var loaded = new FinanceDomainPackLoader().load(file); identity(ref, loaded.pack().packId(), loaded.pack().version());
            if (!domainIds.add(loaded.pack().packId()) || !capabilities.keySet().containsAll(loaded.pack().capabilities()))
                throw new IllegalArgumentException("DOMAIN_CAPABILITY_COVERAGE_MISMATCH");
            for (var dependency : loaded.pack().capabilityPackRefs())
                if (packs.stream().noneMatch(p -> p.packId().equals(dependency.id()) && p.version().equals(dependency.version())))
                    throw new IllegalArgumentException("DOMAIN_PACK_DEPENDENCY_MISMATCH");
            domains.add(loaded);
        }
        Map<String, DescribedFinanceProvider> selected = new LinkedHashMap<>();
        Map<String, ProviderDeclaration> declarations = new LinkedHashMap<>();
        for (AssetRef ref : spec.providerRefs()) {
            Path source = resolveSource(root, ref, "PROVIDER_SOURCE", assets);
            Path file = Files.isDirectory(source) ? source.resolve("assembly.v0.1.json") : source;
            var declaration = HarnessJson.mapper().readValue(bounded(root, file), ProviderDeclaration.class);
            if (!"finance-component-assembly.v0.1".equals(declaration.schemaVersion()) || !"provider".equals(declaration.kind())
                    || !ref.id().equals(declaration.owner().id()) || !ref.version().equals(declaration.owner().version()))
                throw new IllegalArgumentException("PROVIDER_DECLARATION_MISMATCH");
            declarations.put(ref.id(), declaration);
            var provider = providers.get(ref.id());
            if (provider == null || !ref.id().equals(provider.descriptor().providerId()) || !ref.version().equals(provider.descriptor().version())
                    || selected.putIfAbsent(ref.id(), provider) != null) throw new IllegalArgumentException("PROVIDER_IDENTITY_MISMATCH");
            var compatible = support.providers().stream().filter(p -> ref.id().equals(p.id()) && ref.version().equals(p.version())).toList();
            if (compatible.size() != 1 || !declaration.artifactRef().equals(compatible.getFirst().artifactRef())
                    || !declaration.connectionKind().equals(compatible.getFirst().connectionKind())) throw new IllegalArgumentException("HOST_PROVIDER_COMPATIBILITY_MISMATCH");
            RuntimeInventory.requireProviderArtifact(provider, compatible.getFirst().artifactRef());
        }
        if (selected.isEmpty() || !capabilities.keySet().containsAll(spec.capabilityBindings().keySet()))
            throw new IllegalArgumentException("PROVIDER_BINDINGS_INVALID");
        Map<String, Binding> bindings = new LinkedHashMap<>();
        Set<String> used = new HashSet<>();
        for (var capability : capabilities.values()) {
            String id = spec.capabilityBindings().get(capability.capabilityId());
            if (id == null && selected.size() == 1) id = selected.keySet().iterator().next();
            var provider = selected.get(id);
            if (provider == null || !provider.descriptor().supports(capability.capabilityId(), CapabilityRequestV1.Operation.QUERY))
                throw new IllegalArgumentException("PROVIDER_COVERAGE_OR_AMBIGUITY");
            var pack = packs.stream().filter(p -> p.capabilities().contains(capability)).findFirst().orElseThrow();
            if (declarations.get(id).supports().stream().noneMatch(s -> "QUERY".equals(s.operation()) && pack.packId().equals(s.pack().id())
                    && pack.version().equals(s.pack().version()) && s.capabilityIds().contains(capability.capabilityId())))
                throw new IllegalArgumentException("PROVIDER_PACK_VERSION_NOT_SUPPORTED");
            bindings.put(capability.capabilityId(), new Binding(capability, provider)); used.add(id);
        }
        if (!used.equals(selected.keySet())) throw new IllegalArgumentException("UNUSED_PROVIDER_REFERENCE");
        var inventory = RuntimeInventory.inspect(selected.values());
        var collector = AgentScopeObservationCollector.declaration(true);
        qualification.requireCurrent(inventory, collector.identityDigest());
        assets.add(new LockedAsset("RUNTIME_QUALIFICATION", RuntimeQualification.VERSION, "0.1", qualification.source(), qualification.digest()));
        assets.add(new LockedAsset("OBSERVATION_COLLECTOR_DECLARATION", collector.collectorId(), collector.version(), "host-declaration://" + collector.collectorId(), collector.identityDigest()));
        for (var artifact : inventory) {
            boolean provider = artifact.role().startsWith("PROVIDER:");
            String kind = provider ? "PROVIDER_RUNTIME" : artifact.role();
            String id = provider ? artifact.coordinate().substring(0, artifact.coordinate().lastIndexOf(':')) : "maven://" + artifact.coordinate();
            assets.add(new LockedAsset(kind, id, artifact.version(), artifact.source(), artifact.digest()));
        }
        List<Path> descriptions = new ArrayList<>(), terms = new ArrayList<>();
        for (var ref : support.descriptions()) descriptions.add(description(root, ref, "CAPABILITY_DESCRIPTIONS", assets));
        for (var ref : support.terminology()) terms.add(description(root, ref, "TERMINOLOGY_PACK", assets));
        var catalog = CapabilityDescriptionCatalogV01.load(packs, descriptions, terms);
        catalog.requireExactInstallation(capabilities.keySet());
        List<Requirement> requirements = new ArrayList<>(AgentScopeObservationCollector.baseline());
        for (var ref : support.observationRequirements()) {
            Path file = resolve(root, new AssetRef(ref.id(), ref.version(), ref.source(), ref.digest()), false, "OBSERVATION_REQUIREMENTS", assets);
            var declared = HarnessJson.mapper().readValue(Files.readAllBytes(file), ObservationRequirementsV01.class);
            if (!ref.id().equals(declared.id()) || !ref.version().equals(declared.version())) throw new IllegalArgumentException("OBSERVATION_REFERENCE_MISMATCH");
            requirements.addAll(declared.requirements());
        }
        var assessed = ObservationAssessmentV01.assess(merge(requirements), List.of(collector), capabilities.keySet());
        assessed.requireReady();
        var profile = validateProfile(root, spec.profileRef(), support.profiles(), capabilities.keySet(), domains, assets);
        if (spec.evaluationPackRef() != null) throw new IllegalArgumentException("EVALUATION_PACK_VERSION_NOT_QUALIFIED");
        String prompt = String.join("\n\n", packs.stream().map(FinanceCapabilityPackV1::systemPrompt).toList()) + "\n\n"
                + String.join("\n\n", domains.stream().flatMap(d -> d.skills().stream()).map(FinanceDomainPackLoader.LoadedSkill::instructions).toList())
                + "\n\n" + catalog.terminologyPrompt();
        return new Prepared(spec, List.copyOf(assets), Map.copyOf(bindings), catalog, prompt, assessed, domains, packs, profile);
    }

    private static Path description(Path root, AssetRef ref, String kind, List<LockedAsset> assets) throws IOException {
        Path file = resolve(root, ref, false, kind, assets);
        var document = HarnessJson.mapper().readTree(Files.readAllBytes(file));
        identity(ref, document.path("assetId").asText(), document.path("version").asText());
        return file;
    }

    private static ProfileCompatibility validateProfile(Path root, AssetRef ref, List<ProfileCompatibility> supported, Set<String> ids,
            List<FinanceDomainPackLoader.LoadedDomainPack> domains, List<LockedAsset> assets) throws IOException {
        if (ref == null) throw new IllegalArgumentException("PROFILE_VERSION_NOT_QUALIFIED");
        var matches = supported.stream().filter(p -> p.profile().equals(ref)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("PROFILE_REFERENCE_NOT_SUPPORTED");
        var profile = matches.getFirst();
        resolve(root, ref, false, "PROFILE", assets);
        if (!profile.allowedCapabilityIds().containsAll(ids) || domains.stream().anyMatch(d -> !profile.domainProfiles().contains(d.pack().profile())))
            throw new IllegalArgumentException("PROFILE_CAPABILITY_OR_DOMAIN_MISMATCH");
        Path catalog = root.resolve("catalog/finance-assets.v1.json");
        var directory = HarnessJson.mapper().readTree(bounded(root, catalog));
        boolean found = false;
        for (var entry : directory.path("profiles")) if (ref.id().equals(entry.path("id").asText())
                && ref.version().equals(entry.path("version").asText()) && ref.source().equals(entry.path("source").asText())) found = true;
        if (!found) throw new IllegalArgumentException("PROFILE_DECLARATION_MISMATCH");
        assets.add(new LockedAsset("CATALOG", "finance-assets", directory.path("catalogVersion").asText(), relative(root, catalog), AssetDigests.sha256(catalog)));
        return profile;
    }

    private static ObservationRequirementsV01 merge(List<Requirement> source) {
        Map<String, Requirement> merged = new LinkedHashMap<>();
        for (var r : source) {
            var old = merged.get(r.semanticId());
            if (old == null) { merged.put(r.semanticId(), r); continue; }
            if (!old.semanticVersion().equals(r.semanticVersion()) || old.boundary() != r.boundary() || old.content() != r.content())
                throw new IllegalArgumentException("CONFLICTING_OBSERVATION_REQUIREMENT");
            var fields = new HashSet<>(old.fields()); fields.addAll(r.fields());
            var ids = new HashSet<>(old.capabilityIds()); ids.addAll(r.capabilityIds());
            var obligation = old.obligation() == Obligation.REQUIRED || r.obligation() == Obligation.REQUIRED ? Obligation.REQUIRED : r.obligation();
            merged.put(r.semanticId(), new Requirement(r.semanticId(), r.semanticVersion(), obligation, r.boundary(), r.content(), fields, ids));
        }
        return new ObservationRequirementsV01(ObservationRequirementsV01.VERSION, "agentscope-query-effective", "0.1", List.copyOf(merged.values()));
    }

    private static void identity(AssetRef ref, String id, String version) {
        if (!Objects.equals(ref.id(), id) || !Objects.equals(ref.version(), version)) throw new IllegalArgumentException("ASSET_REFERENCE_MISMATCH");
    }
    private static byte[] bounded(Path root, Path path) throws IOException {
        Path base = root.toRealPath(), file = path.toRealPath();
        if (!file.startsWith(base) || Files.isSymbolicLink(path) || !Files.isRegularFile(file) || Files.size(file) > 65536)
            throw new IllegalArgumentException("ASSET_PATH_OR_SIZE_INVALID");
        return Files.readAllBytes(file);
    }
    private static String relative(Path root, Path file) throws IOException { return "repo://" + root.toRealPath().relativize(file.toRealPath()).toString().replace('\\', '/'); }
    private static Path resolveSource(Path root, AssetRef ref, String kind, List<LockedAsset> assets) throws IOException {
        if (ref == null || ref.source() == null || !ref.source().startsWith("repo://") || ref.digest() == null || !ref.digest().matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("PINNED_REPO_ASSET_REQUIRED");
        Path base = root.toRealPath(), source = base.resolve(ref.source().substring(7)).normalize();
        if (!source.startsWith(base) || !source.toRealPath().startsWith(base) || Files.isSymbolicLink(source)) throw new IllegalArgumentException("ASSET_PATH_INVALID");
        AssetDigests.rejectSymlinks(source);
        String actual = Files.isDirectory(source) ? AssetDigests.sha256Tree(source) : AssetDigests.sha256(source);
        if (!actual.equals(ref.digest())) throw new IllegalArgumentException("ASSET_DIGEST_MISMATCH:" + ref.id());
        assets.add(new LockedAsset(kind, ref.id(), ref.version(), ref.source(), actual)); return source;
    }
    private static Path resolve(Path root, AssetRef ref, boolean tree, String kind, List<LockedAsset> assets) throws IOException {
        if (ref == null || ref.source() == null || !ref.source().startsWith("repo://") || ref.digest() == null || !ref.digest().matches("sha256:[a-f0-9]{64}"))
            throw new IllegalArgumentException("PINNED_REPO_ASSET_REQUIRED");
        Path file = root.toRealPath().resolve(ref.source().substring(7)).normalize();
        if (Files.isDirectory(file)) file = file.resolve("pack.yaml");
        bounded(root, file); AssetDigests.rejectSymlinks(file.getParent());
        String actual = tree ? AssetDigests.sha256Tree(file.getParent()) : AssetDigests.sha256(file);
        if (!actual.equals(ref.digest())) throw new IllegalArgumentException("ASSET_DIGEST_MISMATCH:" + ref.id());
        assets.add(new LockedAsset(kind, ref.id(), ref.version(), ref.source(), actual)); return file;
    }

    public static final class Prepared {
        final SolutionSpec spec;
        final List<LockedAsset> assets;
        final Map<String, Binding> bindings;
        final CapabilityDescriptionCatalogV01 catalog;
        final String prompt;
        final ObservationAssessmentV01 assessment;
        final List<FinanceDomainPackLoader.LoadedDomainPack> domains;
        final List<FinanceCapabilityPackV1> packs;
        final ProfileCompatibility profile;
        Prepared(SolutionSpec spec, List<LockedAsset> assets, Map<String, Binding> bindings, CapabilityDescriptionCatalogV01 catalog,
                 String prompt, ObservationAssessmentV01 assessment, List<FinanceDomainPackLoader.LoadedDomainPack> domains, List<FinanceCapabilityPackV1> packs, ProfileCompatibility profile) {
            this.spec = spec; this.assets = assets; this.bindings = bindings; this.catalog = catalog;
            this.prompt = prompt; this.assessment = assessment; this.domains = List.copyOf(domains);
            this.packs = List.copyOf(packs);
            this.profile = profile;
        }
        public Set<String> capabilityIds() { return bindings.keySet(); }
        public ObservationAssessmentV01 observationAssessment() { return assessment; }
        public List<LockedAsset> assets() { return assets; }
        /** Read-only installation evidence; no Agent, grant or model connection is created. */
        public Map<String, Object> snapshot() {
            List<Map<String, Object>> capabilities = new ArrayList<>();
            for (var pack : packs) for (var capability : pack.capabilities()) {
                var provider = bindings.get(capability.capabilityId()).provider().descriptor();
                capabilities.add(Map.of("packId", pack.packId(), "packVersion", pack.version(),
                        "definition", capability, "providerId", provider.providerId(), "providerVersion", provider.version()));
            }
            var value = new LinkedHashMap<String, Object>();
            value.put("schemaVersion", "finance-installation-plan.v0.2");
            value.put("solutionId", spec.solutionId());
            value.put("status", "VALIDATED_NOT_RUNTIME_REGISTERED");
            value.put("bridgeRuntime", spec.runtime());
            value.put("assets", assets);
            value.put("capabilities", List.copyOf(capabilities));
            value.put("methods", domains.stream().map(d -> Map.of("packId", d.pack().packId(),
                    "version", d.pack().version(), "capabilities", d.pack().capabilities(), "skills", d.skills())).toList());
            value.put("capabilityPrompts", packs.stream().map(FinanceCapabilityPackV1::systemPrompt).toList());
            value.put("capabilityDescriptions", catalog.snapshot());
            value.put("observationAssessment", assessment);
            return Collections.unmodifiableMap(value);
        }
        public io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01 displayMetadata() {
            var labels = new ArrayList<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.CapabilityLabel>();
            var sources = new TreeMap<String, io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel>();
            for (var pack : packs) for (var capability : pack.capabilities()) {
                labels.add(new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.CapabilityLabel(
                        capability.capabilityId(), pack.packId(), pack.version(), capability.name()));
                for (var source : bindings.get(capability.capabilityId()).provider().observationLabels()) {
                    var previous = sources.putIfAbsent(source.providerId(), source);
                    if (previous != null && !previous.equals(source)) throw new IllegalStateException("AMBIGUOUS_SOURCE_DISPLAY");
                }
            }
            return new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01(
                    io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.VERSION, labels, new ArrayList<>(sources.values()));
        }
        public AgentScopeQuerySession openSession(AgentScopeQuerySession.Identity identity,
                java.util.function.Supplier<BoundFinanceProvider.Grant> grants, ModelConnection connection) {
            if (!profile.principalTypes().contains(identity.principalType()) || !profile.purposes().contains(identity.purpose()))
                throw new IllegalArgumentException("PROFILE_SESSION_MISMATCH");
            return new AgentScopeQuerySession(this, identity, grants, connection);
        }
        public Map<String, Object> lock(Set<String> installedTools) {
            var expected = bindings.values().stream().map(b -> b.capability().toolName()).collect(java.util.stream.Collectors.toSet());
            if (!expected.equals(installedTools)) throw new IllegalStateException("INSTALLATION_TOOL_SET_MISMATCH");
            List<Map<String, Object>> installations = new ArrayList<>();
            for (var pack : packs) {
                var groups = pack.capabilities().stream().collect(java.util.stream.Collectors.groupingBy(c -> bindings.get(c.capabilityId()).provider().descriptor().providerId()));
                groups.forEach((provider, caps) -> installations.add(Map.of("packId", pack.packId(), "packVersion", pack.version(),
                        "providerId", provider, "integrationId", "agentscope-java", "integrationVersion", RuntimeInventory.INTEGRATION_VERSION,
                        "capabilities", caps.stream().map(c -> Map.of("capabilityId", c.capabilityId(), "toolName", c.toolName(), "operation", "QUERY",
                                "providerId", provider, "providerVersion", bindings.get(c.capabilityId()).provider().descriptor().version())).toList())));
            }
            return Map.of("schemaVersion", "finance-solution-lock.v1", "solutionId", spec.solutionId(), "resolvedAt", Instant.now().toString(),
                    "runtime", spec.runtime(), "assets", assets, "capabilityDescriptions", catalog.snapshot(),
                    "domainInstallations", domains.stream().map(d -> Map.of("packId", d.pack().packId(), "packVersion", d.pack().version(),
                            "profile", d.pack().profile(), "capabilities", d.pack().capabilities(), "skills", d.skills().stream().map(s ->
                                    Map.of("directory", s.directory(), "entry", s.entry(), "digest", s.digest())).toList())).toList(), "installations", List.copyOf(installations));
        }
    }
}
