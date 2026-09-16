package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.openjiuwen.core.singleagent.BaseAgent;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01;
import io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01;
import io.github.bitlhk.finance.harness.core.observation.ObservationAssessmentV01;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/** Minimal build-time assembler for a Java OpenJiuwen solution declaration. */
public final class OpenJiuwenSolutionAssembler {
    private static final String SCHEMA_VERSION = "finance-agent-solution-build.v1";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ObjectMapper json = new ObjectMapper();
    private final FinanceCapabilityPackLoader packLoader = new FinanceCapabilityPackLoader();
    private final OpenJiuwenCapabilityInstaller installer = new OpenJiuwenCapabilityInstaller();
    private final FinanceDomainPackLoader domainPackLoader = new FinanceDomainPackLoader();
    private final OpenJiuwenDomainPackInstaller domainPackInstaller = new OpenJiuwenDomainPackInstaller();

    public AssembledSolution assemble(
            Path repositoryRoot,
            Path solutionFile,
            BaseAgent agent,
            Map<String, FinanceProvider> providers,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            boolean simulated) throws IOException {
        return assemble(repositoryRoot, solutionFile, agent, providers, defaults, simulated,
                new TaskCheckpointStore.Memory(), "session-" + java.util.UUID.randomUUID());
    }

    public AssembledSolution assemble(
            Path repositoryRoot,
            Path solutionFile,
            BaseAgent agent,
            Map<String, FinanceProvider> providers,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults,
            boolean simulated,
            TaskCheckpointStore checkpoints,
            String hostSessionRef) throws IOException {
        Objects.requireNonNull(agent, "agent");
        return install(prepare(repositoryRoot, solutionFile, providers), agent, defaults,
                simulated, checkpoints, hostSessionRef);
    }

    /** Resolve and validate once without registering tools or creating a Java Agent. */
    public PreparedSolution prepare(Path repositoryRoot, Path solutionFile,
                                    Map<String, FinanceProvider> providers) throws IOException {
        return prepare(repositoryRoot, solutionFile, providers, null, List.of());
    }

    /** Explicit opt-in: old strict Solution/Pack V1 files keep their original JSON shapes. */
    public PreparedSolution prepare(Path repositoryRoot, Path solutionFile,
            Map<String, FinanceProvider> providers, ObservationRequirementsV01.Reference observationRequirements,
            List<ObservationCapabilitiesV01> trustedCollectors) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(solutionFile, "solutionFile");
        Objects.requireNonNull(providers, "providers");
        SolutionSpec spec = yaml.readValue(solutionFile.toFile(), SolutionSpec.class);
        validate(spec);

        List<FinanceDomainPackLoader.LoadedDomainPack> loadedDomainPacks = new ArrayList<>();
        List<FinanceCapabilityPackV1> loadedCapabilityPacks = new ArrayList<>();
        List<LockedAsset> lockedAssets = new ArrayList<>();
        RuntimeArtifacts observedRuntime = lockRuntime(spec.runtime(), lockedAssets);
        checkOptionalReference(repositoryRoot, spec.profileRef(), "PROFILE", lockedAssets);
        checkOptionalReference(repositoryRoot, spec.evaluationPackRef(), "EVALUATION", lockedAssets);
        for (AssetRef ref : spec.domainPackRefs()) {
            Path packFile = resolvePackFile(repositoryRoot, ref.source(), "Domain Pack");
            String digest = sha256Tree(packFile.getParent());
            verifyDigest(ref.digest(), digest, ref.id());
            FinanceDomainPackLoader.LoadedDomainPack loaded = domainPackLoader.load(packFile);
            if (!ref.id().equals(loaded.pack().packId()) || !ref.version().equals(loaded.pack().version())) {
                throw new IllegalArgumentException(
                        "Domain Pack reference does not match pack content: " + ref.id());
            }
            loadedDomainPacks.add(loaded);
            lockedAssets.add(new LockedAsset(
                    "DOMAIN_PACK", ref.id(), ref.version(), ref.source(), digest));
        }
        for (AssetRef ref : spec.capabilityPackRefs()) {
            Path packFile = resolvePackFile(repositoryRoot, ref.source(), "Capability Pack");
            String digest = sha256(packFile);
            verifyDigest(ref.digest(), digest, ref.id());
            FinanceCapabilityPackV1 pack = packLoader.load(packFile);
            if (!ref.id().equals(pack.packId()) || !ref.version().equals(pack.version())) {
                throw new IllegalArgumentException(
                        "Capability Pack reference does not match pack content: " + ref.id());
            }
            loadedCapabilityPacks.add(pack);
            lockedAssets.add(new LockedAsset(
                    "CAPABILITY_PACK", ref.id(), ref.version(), ref.source(), digest));
        }
        for (ProviderRef ref : spec.providerRefs()) {
            checkOptionalReference(repositoryRoot, new AssetRef(ref.id(), ref.version(), ref.source(), ref.digest()),
                    "PROVIDER_SOURCE", lockedAssets);
            FinanceProvider provider = providers.get(ref.id());
            if (provider != null) {
                List<Class<?>> types = provider instanceof DescribedFinanceProvider described
                        ? described.artifactTypes() : List.of(provider.getClass());
                if (types.isEmpty()) throw new IllegalArgumentException("Provider artifact inventory cannot be empty");
                for (Class<?> type : types) try {
                    Path artifact = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
                    lockedAssets.add(new LockedAsset("PROVIDER_RUNTIME", ref.id(), ref.version(),
                            "codesource://" + type.getName() + "/" + artifact.getFileName(),
                            Files.isDirectory(artifact) ? sha256Tree(artifact) : sha256(artifact)));
                } catch (java.net.URISyntaxException exception) {
                    throw new IllegalArgumentException("Provider runtime artifact cannot be resolved: " + ref.id(), exception);
                }
            }
        }
        validateCapabilityCoverage(loadedDomainPacks, loadedCapabilityPacks);
        Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> providerBindings =
                resolveProviderBindings(spec, providers, loadedCapabilityPacks);
        Set<String> toolNames = new LinkedHashSet<>();
        for (FinanceCapabilityPackV1 pack : loadedCapabilityPacks) {
            for (var capability : pack.capabilities()) {
                if (!toolNames.add(capability.toolName())) throw new IllegalArgumentException("Duplicate installed toolName");
                if (capability.operation() == FinanceCapabilityPackV1.Operation.QUERY)
                    new QueryInputValidator(capability.inputSchema());
            }
        }
        ObservationRequirementsV01 requirements = null;
        if (observationRequirements != null) {
            if (observationRequirements.digest() == null)
                throw new IllegalArgumentException("Observation requirements digest is mandatory");
            Path source = resolveLocalSource(repositoryRoot, observationRequirements.source()).toRealPath();
            if (!source.startsWith(repositoryRoot.toRealPath()) || !Files.isRegularFile(source)
                    || Files.size(source) > 65_536)
                throw new IllegalArgumentException("Observation requirements must be a bounded local asset");
            byte[] bytes = Files.readAllBytes(source);
            String digest;
            try { digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
            verifyDigest(observationRequirements.digest(), digest, observationRequirements.id());
            requirements = HarnessJson.mapper().readValue(bytes, ObservationRequirementsV01.class);
            if (!requirements.id().equals(observationRequirements.id())
                    || !requirements.version().equals(observationRequirements.version()))
                throw new IllegalArgumentException("Observation requirements reference mismatch");
            lockedAssets.add(new LockedAsset("OBSERVATION_REQUIREMENTS", requirements.id(), requirements.version(),
                    observationRequirements.source(), digest));
        }
        var assessment = ObservationAssessmentV01.assess(requirements, List.copyOf(trustedCollectors), providerBindings.keySet());
        assessment.requireReady();
        if (requirements != null) {
            var matched = assessment.items().stream().map(ObservationAssessmentV01.Item::collectorId)
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
            for (var collector : trustedCollectors) if (matched.contains(collector.collectorId()))
                lockedAssets.add(new LockedAsset("OBSERVATION_COLLECTOR_DECLARATION", collector.collectorId(),
                        collector.version(), "host-declaration://" + collector.collectorId(), collector.identityDigest()));
        }
        return new PreparedSolution(spec, loadedDomainPacks, loadedCapabilityPacks,
                lockedAssets, observedRuntime, providerBindings, assessment, null);
    }

    /** Adds optional B-variant descriptions without changing strict Capability Pack V1 files. */
    public PreparedSolution withCapabilityDescriptions(
            Path repositoryRoot,
            PreparedSolution prepared,
            List<Path> descriptionFiles,
            List<Path> terminologyFiles) throws IOException {
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(prepared, "prepared");
        List<Path> descriptions = checkedDescriptionAssets(repositoryRoot, descriptionFiles);
        List<Path> terminology = checkedDescriptionAssets(repositoryRoot, terminologyFiles);
        CapabilityDescriptionCatalogV01 catalog = CapabilityDescriptionCatalogV01.load(
                prepared.capabilityPacks, descriptions, terminology);
        Set<String> installed = new LinkedHashSet<>();
        prepared.capabilityPacks.forEach(pack -> pack.capabilities().forEach(
                capability -> installed.add(capability.capabilityId())));
        catalog.requireExactInstallation(installed);
        List<LockedAsset> assets = new ArrayList<>(prepared.assets);
        for (Path file : descriptions) assets.add(descriptionAsset(repositoryRoot, file, "CAPABILITY_DESCRIPTIONS"));
        for (Path file : terminology) assets.add(descriptionAsset(repositoryRoot, file, "TERMINOLOGY_PACK"));
        return new PreparedSolution(prepared.spec, prepared.domainPacks, prepared.capabilityPacks,
                assets, prepared.observedRuntime, prepared.bindings, prepared.observation, catalog);
    }

    public AssembledSolution install(PreparedSolution prepared, BaseAgent agent,
            OpenJiuwenFinanceBridge.InvocationDefaults defaults, boolean simulated,
            TaskCheckpointStore checkpoints, String hostSessionRef) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(agent, "agent");
        var spec = prepared.spec;
        var loadedDomainPacks = prepared.domainPacks;
        var loadedCapabilityPacks = prepared.capabilityPacks;
        var lockedAssets = prepared.assets;
        var observedRuntime = prepared.observedRuntime;
        var providerBindings = prepared.bindings;
        var capabilityDescriptions = prepared.capabilityDescriptions;

        if (capabilityDescriptions != null) {
            Set<String> installed = new LinkedHashSet<>();
            loadedCapabilityPacks.forEach(pack -> pack.capabilities().forEach(
                    capability -> installed.add(capability.capabilityId())));
            capabilityDescriptions.requireExactInstallation(installed);
        }

        List<OpenJiuwenDomainPackInstaller.Installation> domainInstallations = new ArrayList<>();
        List<OpenJiuwenCapabilityInstaller.Installation> installations = new ArrayList<>();
        try {
            for (FinanceDomainPackLoader.LoadedDomainPack loaded : loadedDomainPacks) {
                domainInstallations.add(domainPackInstaller.install(agent, loaded));
            }
            boolean installTerminology = true;
            for (FinanceCapabilityPackV1 pack : loadedCapabilityPacks) {
                installations.add(installer.install(
                        agent, providerBindings, defaults, pack, simulated, checkpoints, hostSessionRef,
                        capabilityDescriptions, installTerminology));
                installTerminology = false;
            }
        } catch (RuntimeException exception) {
            closeCapabilityReverse(installations);
            closeDomainReverse(domainInstallations);
            throw exception;
        }

        List<InstalledPack> installedPacks = installations.stream()
                .map(OpenJiuwenCapabilityInstaller.Installation::receipt)
                .map(receipt -> new InstalledPack(
                        receipt.packId(),
                        receipt.packVersion(),
                        receipt.providerId(),
                        receipt.integrationId(),
                        receipt.integrationVersion(),
                        receipt.capabilities()))
                .toList();
        List<InstalledDomainPack> installedDomainPacks = domainInstallations.stream()
                .map(OpenJiuwenDomainPackInstaller.Installation::receipt)
                .map(receipt -> new InstalledDomainPack(
                        receipt.packId(),
                        receipt.packVersion(),
                        receipt.profile(),
                        receipt.capabilities(),
                        receipt.skills()))
                .toList();
        SolutionLock lock = new SolutionLock(
                "finance-solution-lock.v1",
                spec.solutionId(),
                Instant.now().toString(),
                new LockedRuntime(
                        spec.runtime().family(),
                        spec.runtime().language(),
                        spec.runtime().templateRef(),
                        spec.runtime().integrationRef(),
                        spec.runtime().resolvedArtifacts() == null ? null : observedRuntime),
                List.copyOf(lockedAssets),
                installedDomainPacks,
                installedPacks,
                capabilityDescriptions == null ? null : capabilityDescriptions.snapshot());
        return new AssembledSolution(spec.solutionId(), domainInstallations, installations, lock, json);
    }

    public static final class PreparedSolution {
        private final SolutionSpec spec;
        private final List<FinanceDomainPackLoader.LoadedDomainPack> domainPacks;
        private final List<FinanceCapabilityPackV1> capabilityPacks;
        private final List<LockedAsset> assets;
        private final RuntimeArtifacts observedRuntime;
        private final Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> bindings;
        private final ObservationAssessmentV01 observation;
        private final CapabilityDescriptionCatalogV01 capabilityDescriptions;

        private PreparedSolution(SolutionSpec spec, List<FinanceDomainPackLoader.LoadedDomainPack> domains,
                List<FinanceCapabilityPackV1> packs, List<LockedAsset> assets, RuntimeArtifacts runtime,
                Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> bindings, ObservationAssessmentV01 observation,
                CapabilityDescriptionCatalogV01 capabilityDescriptions) {
            this.spec = spec;
            this.domainPacks = List.copyOf(domains);
            this.capabilityPacks = List.copyOf(packs);
            this.assets = List.copyOf(assets);
            this.observedRuntime = runtime;
            this.bindings = Map.copyOf(bindings);
            this.observation = observation;
            this.capabilityDescriptions = capabilityDescriptions;
        }

        public List<FinanceCapabilityPackV1> capabilityPacks() { return capabilityPacks; }
        public List<FinanceDomainPackLoader.LoadedDomainPack> domainPacks() { return domainPacks; }
        public ObservationAssessmentV01 observationAssessment() { return observation; }
        public CapabilityDescriptionCatalogV01.Snapshot capabilityDescriptionSnapshot() {
            return capabilityDescriptions == null ? null : capabilityDescriptions.snapshot();
        }

        /** Display snapshot accessor: does not change existing installation-plan JSON shapes. */
        public io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01 displayMetadata() {
            var labels = new ArrayList<io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.CapabilityLabel>();
            var sources = new java.util.TreeMap<String, io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.SourceLabel>();
            for (var pack : capabilityPacks) for (var capability : pack.capabilities()) {
                labels.add(new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.CapabilityLabel(
                        capability.capabilityId(), pack.packId(), pack.version(), capability.name()));
                if (bindings.get(capability.capabilityId()).provider() instanceof io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider described)
                    for (var source : described.observationLabels()) {
                        var previous = sources.putIfAbsent(source.providerId(), source);
                        if (previous != null && !previous.equals(source)) throw new IllegalStateException("AMBIGUOUS_SOURCE_DISPLAY");
                    }
            }
            return new io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01(
                    io.github.bitlhk.finance.harness.core.observation.ObservationDisplayV01.VERSION, labels, new ArrayList<>(sources.values()));
        }

        public Map<String, Object> snapshot() {
            List<Map<String, Object>> capabilities = new ArrayList<>();
            for (var pack : capabilityPacks) for (var capability : pack.capabilities()) {
                var binding = bindings.get(capability.capabilityId());
                capabilities.add(Map.of("packId", pack.packId(), "packVersion", pack.version(),
                        "definition", preparedDefinition(capability), "providerId", binding.providerId(),
                        "providerVersion", binding.providerVersion()));
            }
            List<Map<String, Object>> methods = new ArrayList<>();
            for (var pack : domainPacks) methods.add(Map.of("packId", pack.pack().packId(),
                    "version", pack.pack().version(), "capabilities", pack.pack().capabilities(), "skills", pack.skills()));
            List<String> prompts = new ArrayList<>(capabilityPacks.stream()
                    .map(FinanceCapabilityPackV1::systemPrompt).toList());
            if (capabilityDescriptions != null) {
                if (!capabilityDescriptions.terminologyPrompt().isBlank())
                    prompts.add(capabilityDescriptions.terminologyPrompt());
                if (capabilityDescriptions.allReadOnly()) prompts.add(ReadOnlyTaskCollaborationV01.INSTRUCTION);
            }
            var snapshot = new LinkedHashMap<String, Object>(Map.of("schemaVersion", "finance-installation-plan.v0.1", "solutionId", spec.solutionId(),
                    "status", "VALIDATED_NOT_RUNTIME_REGISTERED", "bridgeRuntime", observedRuntime,
                    "assets", assets, "capabilities", List.copyOf(capabilities), "methods", List.copyOf(methods),
                    "capabilityPrompts", List.copyOf(prompts)));
            if (observation.status() != ObservationAssessmentV01.Status.LEGACY_UNASSESSED) {
                snapshot.put("schemaVersion", "finance-installation-plan.v0.2");
                snapshot.put("observationAssessment", observation);
            }
            if (capabilityDescriptions != null) {
                snapshot.put("capabilityDescriptions", capabilityDescriptions.snapshot());
                if (capabilityDescriptions.allReadOnly()) snapshot.put("taskCollaboration", Map.of(
                            "version", ReadOnlyTaskCollaborationV01.VERSION,
                            "instructionDigest", ReadOnlyTaskCollaborationV01.digest()));
            }
            return Map.copyOf(snapshot);
        }

        @SuppressWarnings("unchecked")
        private Object preparedDefinition(FinanceCapabilityPackV1.Capability capability) {
            if (capabilityDescriptions == null) return capability;
            Map<String, Object> definition = HarnessJson.mapper().convertValue(capability, LinkedHashMap.class);
            definition.put("description", capabilityDescriptions.description(capability.capabilityId()));
            return Map.copyOf(definition);
        }

        public ReadOnlyInstallation openQueries(OpenJiuwenFinanceBridge.InvocationDefaults defaults,
                                               boolean simulated, String hostSessionRef) {
            return new ReadOnlyInstallation(this, defaults, simulated, hostSessionRef);
        }
    }

    /** Owns only QUERY bindings, never a Runtime, task controller, or model client. */
    public static final class ReadOnlyInstallation {
        private final PreparedSolution prepared;
        private final Map<String, OpenJiuwenFinanceBridge> bridges = new LinkedHashMap<>();
        private final Map<String, FinanceCapabilityPackV1.Capability> capabilities = new LinkedHashMap<>();
        private final Map<String, QueryInputValidator> validators = new LinkedHashMap<>();

        private ReadOnlyInstallation(PreparedSolution prepared, OpenJiuwenFinanceBridge.InvocationDefaults defaults,
                                     boolean simulated, String session) {
            this.prepared = prepared;
            if (session == null || session.isBlank()) throw new IllegalArgumentException("Host session required");
            for (var pack : prepared.capabilityPacks) for (var capability : pack.capabilities()) {
                if (capability.operation() != FinanceCapabilityPackV1.Operation.QUERY)
                    throw new IllegalArgumentException("READONLY_INSTALLATION_REJECTS_TASK");
                var binding = prepared.bindings.get(capability.capabilityId());
                capabilities.put(capability.capabilityId(), capability);
                validators.put(capability.capabilityId(), new QueryInputValidator(capability.inputSchema()));
                bridges.put(capability.capabilityId(), new OpenJiuwenFinanceBridge(binding.provider(), binding.providerId(),
                        defaults, Set.of(capability.capabilityId()), simulated).hostSession(session));
            }
        }

        public Set<String> capabilityIds() { return Set.copyOf(capabilities.keySet()); }
        public Set<String> requiredScopes() {
            Set<String> result = new LinkedHashSet<>();
            capabilities.values().forEach(capability -> result.addAll(capability.requiredScopes()));
            return Set.copyOf(result);
        }
        public Map<String, Object> plan() { return prepared.snapshot(); }

        public OpenJiuwenExecutionV1 query(String capabilityId, Map<String, Object> arguments, String requestId) {
            var capability = capabilities.get(capabilityId);
            if (capability == null) throw new SecurityException("CAPABILITY_NOT_INSTALLED");
            validators.get(capabilityId).validate(arguments);
            return bridges.get(capabilityId).query(new OpenJiuwenInvocationV1(requestId, capabilityId,
                    prepared.spec.solutionId(), capability.requiredScopes(), arguments, Map.of()));
        }
    }

    private static RuntimeArtifacts lockRuntime(RuntimeRef declared, List<LockedAsset> assets) throws IOException {
        var template = LoadedRuntimeArtifact.read(BaseAgent.class,
                "META-INF/maven/com.openjiuwen/agent-core-java/pom.properties", "com.openjiuwen", "agent-core-java");
        var integration = LoadedRuntimeArtifact.read(OpenJiuwenSolutionAssembler.class,
                "META-INF/finance-harness/integration.properties", "io.github.bitlhk.finance", "finance-harness-openjiuwen-java");
        requireRuntimeReference(template, declared.templateRef(), declared.resolvedArtifacts());
        requireRuntimeReference(integration, declared.integrationRef(), declared.resolvedArtifacts());
        if (declared.resolvedArtifacts() != null) {
            template.requireReference(declared.resolvedArtifacts().templateRef());
            integration.requireReference(declared.resolvedArtifacts().integrationRef());
        }
        if (!integration.version().equals(OpenJiuwenCapabilityInstaller.INTEGRATION_VERSION))
            throw new IllegalArgumentException("RUNTIME_REFERENCE_MISMATCH: integration receipt version");
        lockRuntimeAsset("RUNTIME_TEMPLATE", template, assets);
        lockRuntimeAsset("RUNTIME_INTEGRATION", integration, assets);
        return new RuntimeArtifacts(template.reference(), integration.reference());
    }

    private static void requireRuntimeReference(LoadedRuntimeArtifact actual, String reference, RuntimeArtifacts resolved) {
        if (reference != null && (reference.startsWith("repo://") || reference.startsWith("git+https://"))) {
            if (resolved == null) throw new IllegalArgumentException(
                    "RUNTIME_ARTIFACT_RESOLUTION_REQUIRED: preserve source references and supply runtime.resolvedArtifacts");
            // A source reference records Builder provenance, not proof that this source was loaded.
        } else {
            actual.requireReference(reference);
        }
    }

    private static void lockRuntimeAsset(String kind, LoadedRuntimeArtifact artifact, List<LockedAsset> assets) throws IOException {
        assets.add(new LockedAsset(kind, artifact.reference(), artifact.version(),
                "codesource://" + artifact.source().getFileName(),
                Files.isDirectory(artifact.source()) ? sha256Tree(artifact.source()) : sha256(artifact.source())));
    }

    private static void validateCapabilityCoverage(
            List<FinanceDomainPackLoader.LoadedDomainPack> domainPacks,
            List<FinanceCapabilityPackV1> capabilityPacks) {
        Set<String> installedCapabilities = new LinkedHashSet<>();
        capabilityPacks.forEach(pack -> pack.capabilities().forEach(
                capability -> installedCapabilities.add(capability.capabilityId())));
        Set<String> missing = new LinkedHashSet<>();
        domainPacks.forEach(pack -> pack.pack().capabilities().forEach(capability -> {
            if (!installedCapabilities.contains(capability)) missing.add(capability);
        }));
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Domain Pack capabilities are not installed: " + missing);
        }
    }

    private static Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> resolveProviderBindings(
            SolutionSpec spec,
            Map<String, FinanceProvider> providers,
            List<FinanceCapabilityPackV1> capabilityPacks) {
        if (spec.providerRefs().isEmpty()) {
            throw new IllegalArgumentException("At least one Provider reference is required");
        }
        Map<String, ProviderRef> references = new LinkedHashMap<>();
        Map<String, DescribedFinanceProvider> describedProviders = new LinkedHashMap<>();
        for (ProviderRef providerRef : spec.providerRefs()) {
            if (references.putIfAbsent(providerRef.id(), providerRef) != null) {
                throw new IllegalArgumentException("Duplicate Provider reference: " + providerRef.id());
            }
            FinanceProvider provider = providers.get(providerRef.id());
            if (provider == null) {
                throw new IllegalArgumentException("Provider binding is missing: " + providerRef.id());
            }
            if (!(provider instanceof DescribedFinanceProvider described)) {
                throw new IllegalArgumentException(
                        "Declarative Solution requires a described Provider: " + providerRef.id());
            }
            ProviderDescriptorV1 descriptor = described.descriptor();
            if (!providerRef.id().equals(descriptor.providerId())) {
                throw new IllegalArgumentException(
                        "Provider reference id does not match descriptor: " + providerRef.id());
            }
            if (!providerRef.version().equals(descriptor.version())) {
                throw new IllegalArgumentException(
                        "Provider reference version does not match descriptor: " + providerRef.id());
            }
            describedProviders.put(providerRef.id(), described);
        }

        Map<String, CapabilityRequestV1.Operation> requiredCapabilities = new LinkedHashMap<>();
        for (FinanceCapabilityPackV1 pack : capabilityPacks) {
            for (FinanceCapabilityPackV1.Capability capability : pack.capabilities()) {
                CapabilityRequestV1.Operation operation = CapabilityRequestV1.Operation.valueOf(
                        capability.operation().name());
                if (requiredCapabilities.putIfAbsent(capability.capabilityId(), operation) != null) {
                    throw new IllegalArgumentException(
                            "Capability is declared by more than one installed pack: "
                                    + capability.capabilityId());
                }
            }
        }

        Set<String> unknownBindings = new LinkedHashSet<>(spec.capabilityBindings().keySet());
        unknownBindings.removeAll(requiredCapabilities.keySet());
        if (!unknownBindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "Capability bindings reference capabilities that are not installed: " + unknownBindings);
        }

        boolean explicitBindingsRequired = references.size() > 1;
        Map<String, OpenJiuwenCapabilityInstaller.ProviderBinding> resolved = new LinkedHashMap<>();
        Set<String> usedProviders = new LinkedHashSet<>();
        for (Map.Entry<String, CapabilityRequestV1.Operation> capability : requiredCapabilities.entrySet()) {
            String providerId = spec.capabilityBindings().get(capability.getKey());
            if (providerId == null && !explicitBindingsRequired) {
                providerId = references.keySet().iterator().next();
            }
            if (providerId == null) {
                throw new IllegalArgumentException(
                        "Multiple Providers require an explicit binding for capability: "
                                + capability.getKey());
            }
            ProviderRef reference = references.get(providerId);
            if (reference == null) {
                throw new IllegalArgumentException(
                        "Capability binding references an unknown Provider: " + providerId);
            }
            DescribedFinanceProvider described = describedProviders.get(providerId);
            ProviderDescriptorV1 descriptor = described.descriptor();
            if (!descriptor.supports(capability.getKey(), capability.getValue())) {
                throw new IllegalArgumentException(
                        "Provider does not implement installed capability: "
                                + providerId + " -> " + capability.getKey() + ":" + capability.getValue());
            }
            usedProviders.add(providerId);
            resolved.put(capability.getKey(), new OpenJiuwenCapabilityInstaller.ProviderBinding(
                    providerId,
                    reference.version(),
                    described));
        }

        Set<String> unusedProviders = new LinkedHashSet<>(references.keySet());
        unusedProviders.removeAll(usedProviders);
        if (!unusedProviders.isEmpty()) {
            throw new IllegalArgumentException(
                    "Provider references are not used by any capability: " + unusedProviders);
        }
        return Map.copyOf(resolved);
    }

    private static void validate(SolutionSpec spec) {
        if (!SCHEMA_VERSION.equals(spec.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported solution schemaVersion: " + spec.schemaVersion());
        }
        if (!"openjiuwen".equals(spec.runtime().family()) || !"java".equals(spec.runtime().language())) {
            throw new IllegalArgumentException("This assembler only supports OpenJiuwen Java solutions");
        }
    }

    private static Path resolvePackFile(Path repositoryRoot, String source, String kind) throws IOException {
        Path resolved = resolveLocalSource(repositoryRoot, source);
        if (Files.isDirectory(resolved)) resolved = resolved.resolve("pack.yaml");
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(kind + " was not found: " + source);
        }
        rejectSymlinks(resolved.getParent());
        return resolved;
    }

    private static Path resolveLocalSource(Path repositoryRoot, String source) throws IOException {
        if (source == null || !source.startsWith("repo://")) {
            throw new IllegalArgumentException("V1 assembler only resolves repo:// assets");
        }
        Path normalizedRoot = repositoryRoot.toRealPath();
        Path resolved = normalizedRoot.resolve(source.substring("repo://".length())).normalize();
        if (!resolved.startsWith(normalizedRoot) || !resolved.toRealPath().startsWith(normalizedRoot)
                || Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("Asset source escapes repository root or is a symlink");
        }
        return resolved;
    }

    private static void checkOptionalReference(Path root, AssetRef ref, String kind, List<LockedAsset> assets)
            throws IOException {
        if (ref == null) return;
        if (ref.source() == null || !ref.source().startsWith("repo://")) {
            if (ref.digest() != null) throw new IllegalArgumentException("Cannot verify unresolved external digest: " + ref.id());
            return;
        }
        Path source = resolveLocalSource(root, ref.source());
        String digest = Files.isDirectory(source) ? sha256Tree(source) : sha256(source);
        verifyDigest(ref.digest(), digest, ref.id());
        assets.add(new LockedAsset(kind, ref.id(), ref.version(), ref.source(), digest));
    }

    private static void verifyDigest(String declared, String actual, String id) {
        if (declared != null && (!declared.matches("sha256:[a-f0-9]{64}") || !declared.equals(actual))) {
            throw new IllegalArgumentException("Declared digest does not match asset: " + id);
        }
    }

    private List<Path> checkedDescriptionAssets(Path repositoryRoot, List<Path> files) throws IOException {
        if (files == null) return List.of();
        Path root = repositoryRoot.toRealPath();
        List<Path> result = new ArrayList<>();
        for (Path configured : files) {
            Objects.requireNonNull(configured, "Capability description asset path");
            Path candidate = (configured.isAbsolute() ? configured : root.resolve(configured)).normalize();
            if (!candidate.startsWith(root) || Files.isSymbolicLink(candidate)) {
                throw new IllegalArgumentException("Capability description asset must be a bounded repository file");
            }
            Path file = candidate.toRealPath();
            if (!file.startsWith(root) || !Files.isRegularFile(file)
                    || Files.size(file) > 65_536) {
                throw new IllegalArgumentException("Capability description asset must be a bounded repository file");
            }
            result.add(file);
        }
        return List.copyOf(result);
    }

    private LockedAsset descriptionAsset(Path repositoryRoot, Path file, String kind) throws IOException {
        var node = json.readTree(file.toFile());
        String id = node.path("assetId").asText();
        String version = node.path("version").asText();
        if (id.isBlank() || version.isBlank()) throw new IllegalArgumentException("Description asset identity is required");
        String source = "repo://" + repositoryRoot.toRealPath().relativize(file.toRealPath())
                .toString().replace('\\', '/');
        return new LockedAsset(kind, id, version, source, sha256(file));
    }

    private static void rejectSymlinks(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            if (paths.anyMatch(Files::isSymbolicLink)) throw new IllegalArgumentException("Asset symlinks are not supported");
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256Tree(Path directory) throws IOException {
        rejectSymlinks(directory);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile)
                        .filter(path -> {
                            for (Path part : directory.relativize(path)) {
                                if (Set.of("target", "build", "logs", ".git").contains(part.toString())) return false;
                            }
                            return true;
                        })
                        .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                        .toList()) {
                    digest.update(directory.relativize(path).toString().replace('\\', '/')
                            .getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void closeCapabilityReverse(List<OpenJiuwenCapabilityInstaller.Installation> installations) {
        for (int index = installations.size() - 1; index >= 0; index--) {
            installations.get(index).close();
        }
    }

    private static void closeDomainReverse(List<OpenJiuwenDomainPackInstaller.Installation> installations) {
        for (int index = installations.size() - 1; index >= 0; index--) {
            installations.get(index).close();
        }
    }

    private record SolutionSpec(
            String schemaVersion,
            String solutionId,
            String displayName,
            RuntimeRef runtime,
            AssetRef profileRef,
            List<AssetRef> domainPackRefs,
            List<AssetRef> capabilityPackRefs,
            List<ProviderRef> providerRefs,
            Map<String, String> capabilityBindings,
            String modelRef,
            AssetRef evaluationPackRef) {
        private SolutionSpec {
            domainPackRefs = domainPackRefs == null ? List.of() : List.copyOf(domainPackRefs);
            capabilityPackRefs = capabilityPackRefs == null ? List.of() : List.copyOf(capabilityPackRefs);
            providerRefs = providerRefs == null ? List.of() : List.copyOf(providerRefs);
            capabilityBindings = capabilityBindings == null ? Map.of() : Map.copyOf(capabilityBindings);
        }
    }

    private record RuntimeRef(String family, String language, String templateRef, String integrationRef,
                              RuntimeArtifacts resolvedArtifacts) {
    }

    public record RuntimeArtifacts(String templateRef, String integrationRef) { }

    private record AssetRef(String id, String version, String source, String digest) {
    }

    private record ProviderRef(String id, String version, String source, String digest) {
    }

    public record LockedRuntime(
            String family,
            String language,
            String templateRef,
            String integrationRef,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            RuntimeArtifacts resolvedArtifacts) {
        public LockedRuntime(String family, String language, String templateRef, String integrationRef) {
            this(family, language, templateRef, integrationRef, null);
        }
    }

    public record LockedAsset(
            String kind,
            String id,
            String version,
            String source,
            String digest) {
    }

    public record InstalledPack(
            String packId,
            String packVersion,
            String providerId,
            String integrationId,
            String integrationVersion,
            List<OpenJiuwenCapabilityInstaller.CapabilityBinding> capabilities) {
        public InstalledPack {
            capabilities = List.copyOf(capabilities);
        }
    }

    public record InstalledDomainPack(
            String packId,
            String packVersion,
            String profile,
            List<String> capabilities,
            List<OpenJiuwenDomainPackInstaller.InstalledSkill> skills) {
        public InstalledDomainPack {
            capabilities = List.copyOf(capabilities);
            skills = List.copyOf(skills);
        }
    }

    public record SolutionLock(
            String schemaVersion,
            String solutionId,
            String resolvedAt,
            LockedRuntime runtime,
            List<LockedAsset> assets,
            List<InstalledDomainPack> domainInstallations,
            List<InstalledPack> installations,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            CapabilityDescriptionCatalogV01.Snapshot capabilityDescriptions) {
        public SolutionLock {
            assets = List.copyOf(assets);
            domainInstallations = List.copyOf(domainInstallations);
            installations = List.copyOf(installations);
        }
    }

    public static final class AssembledSolution implements AutoCloseable {
        private final String solutionId;
        private final List<OpenJiuwenDomainPackInstaller.Installation> domainInstallations;
        private final List<OpenJiuwenCapabilityInstaller.Installation> installations;
        private final SolutionLock lock;
        private final ObjectMapper json;
        private boolean closed;

        private AssembledSolution(
                String solutionId,
                List<OpenJiuwenDomainPackInstaller.Installation> domainInstallations,
                List<OpenJiuwenCapabilityInstaller.Installation> installations,
                SolutionLock lock,
                ObjectMapper json) {
            this.solutionId = solutionId;
            this.domainInstallations = List.copyOf(domainInstallations);
            this.installations = List.copyOf(installations);
            this.lock = lock;
            this.json = json;
        }

        public String solutionId() {
            return solutionId;
        }

        public SolutionLock lock() {
            return lock;
        }

        public synchronized OpenJiuwenCapabilityInstaller.Installation installationForCapability(String capabilityId) {
            if (closed) throw new IllegalStateException("Solution is closed");
            return installations.stream().filter(installation -> installation.receipt().capabilities().stream()
                    .anyMatch(capability -> capability.capabilityId().equals(capabilityId))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Capability is not installed: " + capabilityId));
        }

        public void writeLock(Path output) throws IOException {
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), lock);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closeCapabilityReverse(installations);
            closeDomainReverse(domainInstallations);
            closed = true;
        }
    }
}
