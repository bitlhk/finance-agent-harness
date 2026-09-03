package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.openjiuwen.core.singleagent.BaseAgent;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
        Objects.requireNonNull(repositoryRoot, "repositoryRoot");
        Objects.requireNonNull(solutionFile, "solutionFile");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(providers, "providers");
        SolutionSpec spec = yaml.readValue(solutionFile.toFile(), SolutionSpec.class);
        validate(spec);

        List<FinanceDomainPackLoader.LoadedDomainPack> loadedDomainPacks = new ArrayList<>();
        List<FinanceCapabilityPackV1> loadedCapabilityPacks = new ArrayList<>();
        List<LockedAsset> lockedAssets = new ArrayList<>();
        for (AssetRef ref : spec.domainPackRefs()) {
            Path packFile = resolvePackFile(repositoryRoot, ref.source(), "Domain Pack");
            FinanceDomainPackLoader.LoadedDomainPack loaded = domainPackLoader.load(packFile);
            if (!ref.id().equals(loaded.pack().packId()) || !ref.version().equals(loaded.pack().version())) {
                throw new IllegalArgumentException(
                        "Domain Pack reference does not match pack content: " + ref.id());
            }
            loadedDomainPacks.add(loaded);
            lockedAssets.add(new LockedAsset(
                    "DOMAIN_PACK", ref.id(), ref.version(), ref.source(), sha256Tree(packFile.getParent())));
        }
        for (AssetRef ref : spec.capabilityPackRefs()) {
            Path packFile = resolvePackFile(repositoryRoot, ref.source(), "Capability Pack");
            FinanceCapabilityPackV1 pack = packLoader.load(packFile);
            if (!ref.id().equals(pack.packId()) || !ref.version().equals(pack.version())) {
                throw new IllegalArgumentException(
                        "Capability Pack reference does not match pack content: " + ref.id());
            }
            loadedCapabilityPacks.add(pack);
            lockedAssets.add(new LockedAsset(
                    "CAPABILITY_PACK", ref.id(), ref.version(), ref.source(), sha256(packFile)));
        }
        validateCapabilityCoverage(loadedDomainPacks, loadedCapabilityPacks);
        ProviderRef providerRef = providerFor(spec);
        FinanceProvider provider = providers.get(providerRef.id());
        if (provider == null) {
            throw new IllegalArgumentException("Provider binding is missing: " + providerRef.id());
        }
        validateProviderBinding(providerRef, provider, loadedCapabilityPacks);

        List<OpenJiuwenDomainPackInstaller.Installation> domainInstallations = new ArrayList<>();
        List<OpenJiuwenCapabilityInstaller.Installation> installations = new ArrayList<>();
        try {
            for (FinanceDomainPackLoader.LoadedDomainPack loaded : loadedDomainPacks) {
                domainInstallations.add(domainPackInstaller.install(agent, loaded));
            }
            for (FinanceCapabilityPackV1 pack : loadedCapabilityPacks) {
                installations.add(installer.install(
                        agent, provider, providerRef.id(), defaults, pack, simulated));
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
                        spec.runtime().integrationRef()),
                List.copyOf(lockedAssets),
                installedDomainPacks,
                installedPacks);
        return new AssembledSolution(spec.solutionId(), domainInstallations, installations, lock, json);
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

    private static ProviderRef providerFor(SolutionSpec spec) {
        if (spec.providerRefs().size() != 1) {
            throw new IllegalArgumentException(
                    "V1 assembler requires exactly one Provider binding per solution");
        }
        return spec.providerRefs().getFirst();
    }

    private static void validateProviderBinding(
            ProviderRef providerRef,
            FinanceProvider provider,
            List<FinanceCapabilityPackV1> capabilityPacks) {
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
        Set<String> unsupported = new LinkedHashSet<>();
        for (FinanceCapabilityPackV1 pack : capabilityPacks) {
            for (FinanceCapabilityPackV1.Capability capability : pack.capabilities()) {
                CapabilityRequestV1.Operation operation = CapabilityRequestV1.Operation.valueOf(
                        capability.operation().name());
                if (!descriptor.supports(capability.capabilityId(), operation)) {
                    unsupported.add(capability.capabilityId() + ":" + operation);
                }
            }
        }
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException(
                    "Provider does not implement installed capabilities: " + unsupported);
        }
    }

    private static void validate(SolutionSpec spec) {
        if (!SCHEMA_VERSION.equals(spec.schemaVersion())) {
            throw new IllegalArgumentException("Unsupported solution schemaVersion: " + spec.schemaVersion());
        }
        if (!"openjiuwen".equals(spec.runtime().family()) || !"java".equals(spec.runtime().language())) {
            throw new IllegalArgumentException("This assembler only supports OpenJiuwen Java solutions");
        }
    }

    private static Path resolvePackFile(Path repositoryRoot, String source, String kind) {
        if (source == null || !source.startsWith("repo://")) {
            throw new IllegalArgumentException("V1 assembler only resolves repo:// " + kind + "s");
        }
        Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(source.substring("repo://".length())).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("Capability Pack source escapes repository root");
        }
        if (Files.isDirectory(resolved)) resolved = resolved.resolve("pack.yaml");
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException(kind + " was not found: " + source);
        }
        return resolved;
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile)
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
            String modelRef,
            AssetRef evaluationPackRef) {
        private SolutionSpec {
            domainPackRefs = domainPackRefs == null ? List.of() : List.copyOf(domainPackRefs);
            capabilityPackRefs = capabilityPackRefs == null ? List.of() : List.copyOf(capabilityPackRefs);
            providerRefs = providerRefs == null ? List.of() : List.copyOf(providerRefs);
        }
    }

    private record RuntimeRef(String family, String language, String templateRef, String integrationRef) {
    }

    private record AssetRef(String id, String version, String source, String digest) {
    }

    private record ProviderRef(String id, String version, String source, String digest) {
    }

    public record LockedRuntime(
            String family,
            String language,
            String templateRef,
            String integrationRef) {
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
            List<InstalledPack> installations) {
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
