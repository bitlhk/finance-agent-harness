package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.github.bitlhk.finance.harness.assembly.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Identity is read from the loaded artifacts, never from caller-provided SDK labels. */
public final class RuntimeInventory {
    public static final String FAMILY = "agentscope";
    public static final String SDK_VERSION = "2.0.3";
    public static final String INTEGRATION_VERSION = "0.1.0-SNAPSHOT";
    private RuntimeInventory() {}
    public record Artifact(String role, String coordinate, String version, String digest, String source) {}

    public static void requireProviderArtifact(DescribedFinanceProvider provider, String reference) throws IOException {
        if (reference == null || !reference.matches("maven://[^: /]+:[^: /]+:[^ /]+")) throw new IllegalArgumentException("PROVIDER_ARTIFACT_REFERENCE_REQUIRED");
        String[] coordinate = reference.substring(8).split(":", 3);
        for (Class<?> type : provider.artifactTypes()) {
            Path source;
            try { source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()); }
            catch (Exception invalid) { throw new IOException("PROVIDER_CODESOURCE_UNAVAILABLE", invalid); }
            if (!Files.isDirectory(source)) {
                try {
                    var loaded = LoadedRuntimeArtifact.read(type, "META-INF/maven/" + coordinate[0] + "/" + coordinate[1] + "/pom.properties", coordinate[0], coordinate[1]);
                    loaded.requireReference(reference); return;
                } catch (IOException absentMetadata) { continue; }
            }
            // Reactor test phase has no packaged pom.properties yet. Final qualification checks
            // these tested class bytes against the JAR and then verifies its own Maven metadata.
            if (!Set.of("classes", "test-classes").contains(source.getFileName().toString()) || !"target".equals(source.getParent().getFileName().toString())) continue;
            Path pom = source.getParent().getParent().resolve("pom.xml");
            try {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
                var parent = child(project, "parent");
                String group = childText(project, "groupId"); if (group.isEmpty()) group = childText(parent, "groupId");
                String version = childText(project, "version"); if (version.isEmpty()) version = childText(parent, "version");
                if (reference.equals("maven://" + group + ":" + childText(project, "artifactId") + ":" + version)) return;
            } catch (Exception invalid) { throw new IOException("PROVIDER_REACTOR_METADATA_INVALID", invalid); }
        }
        throw new IllegalArgumentException("PROVIDER_LOADED_ARTIFACT_MISMATCH");
    }
    private static org.w3c.dom.Element child(org.w3c.dom.Element element, String name) {
        if (element == null) return null;
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof org.w3c.dom.Element e && name.equals(e.getTagName())) return e;
        return null;
    }
    private static String childText(org.w3c.dom.Element element, String name) {
        var child = child(element, name); return child == null ? "" : child.getTextContent().trim();
    }

    public static List<Artifact> inspect(Collection<? extends DescribedFinanceProvider> providers) throws IOException {
        List<Artifact> result = new ArrayList<>();
        sdk(result, ReActAgent.class, "agentscope-core", "7f1ec0f9c5afd1388554275efc78f58cb05692b634453695cf404eb79f7cfda4");
        sdk(result, OpenAIChatModel.class, "agentscope-extensions-model-openai", "ecfb8f439eeb1f82dedada75817fd470212a195448e0186eedc6c6b98f5e5c4f");
        var integration = LoadedRuntimeArtifact.read(RuntimeInventory.class, "META-INF/finance-harness/integration.properties",
                "io.github.bitlhk.finance", "finance-harness-agentscope-java");
        integration.requireReference("maven://io.github.bitlhk.finance:finance-harness-agentscope-java:" + INTEGRATION_VERSION);
        var assembly = LoadedRuntimeArtifact.read(AssetDigests.class, "META-INF/finance-harness/assembly.properties",
                "io.github.bitlhk.finance", "finance-harness-assembly");
        assembly.requireReference("maven://io.github.bitlhk.finance:finance-harness-assembly:" + INTEGRATION_VERSION);
        result.add(artifact("RUNTIME_INTEGRATION", RuntimeInventory.class, integration.reference().substring(8)));
        result.add(artifact("ASSEMBLY", AssetDigests.class, "io.github.bitlhk.finance:finance-harness-assembly:" + INTEGRATION_VERSION));
        result.add(artifact("CORE", FinanceContextV1.class, "io.github.bitlhk.finance:finance-harness-core:" + INTEGRATION_VERSION));
        result.add(artifact("COLLECTOR", AgentScopeObservationCollector.class, AgentScopeObservationCollector.ID + ":" + AgentScopeObservationCollector.VERSION));
        result.add(artifact("PROVIDER_OBSERVER", ObservedFinanceProvider.class, "finance-harness-core:" + INTEGRATION_VERSION));
        result.add(artifact("AUTHORIZATION_BINDING", io.github.bitlhk.finance.harness.core.spi.BoundFinanceProvider.class, "finance-harness-core:" + INTEGRATION_VERSION));
        for (var provider : providers) for (Class<?> type : provider.artifactTypes())
            result.add(artifact("PROVIDER:" + provider.descriptor().providerId() + ":" + type.getName(), type,
                    provider.descriptor().providerId() + ":" + provider.descriptor().version()));
        return result.stream().distinct().sorted(Comparator.comparing(Artifact::role)).toList();
    }

    private static void sdk(List<Artifact> out, Class<?> type, String name, String expected) throws IOException {
        var loaded = LoadedRuntimeArtifact.read(type, "META-INF/maven/io.agentscope/" + name + "/pom.properties", "io.agentscope", name);
        if (!SDK_VERSION.equals(loaded.version())) throw new IllegalArgumentException("SDK_VERSION_MISMATCH");
        Artifact artifact = artifact(name.equals("agentscope-core") ? "RUNTIME_TEMPLATE" : "RUNTIME_MODEL_ADAPTER", type, "io.agentscope:" + name + ":" + SDK_VERSION);
        if (!artifact.digest().equals("sha256:" + expected)) throw new IllegalArgumentException("SDK_DIGEST_MISMATCH");
        out.add(artifact);
    }

    public static Artifact artifact(String role, Class<?> anchor, String coordinate) throws IOException {
        try {
            Path source = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
            String digest = Files.isDirectory(source) ? AssetDigests.sha256Tree(source) : AssetDigests.sha256(source);
            return new Artifact(role, coordinate, coordinate.substring(coordinate.lastIndexOf(':') + 1), digest, source.toUri().toString());
        } catch (java.net.URISyntaxException | NullPointerException unavailable) {
            throw new IOException("RUNTIME_ARTIFACT_UNVERIFIED", unavailable);
        }
    }
}
