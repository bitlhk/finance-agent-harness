package io.github.bitlhk.finance.harness.assembly;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

/** Read metadata from the anchor's own code source, never an unrelated classpath resource. */
public record LoadedRuntimeArtifact(String reference, String version, Path source) {
    public static LoadedRuntimeArtifact read(Class<?> anchor, String metadata, String group, String artifact) throws IOException {
        final Path source;
        try {
            source = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception unavailable) {
            throw new IOException("RUNTIME_ARTIFACT_UNVERIFIED: " + anchor.getName(), unavailable);
        }
        Properties properties = new Properties();
        if (Files.isDirectory(source)) {
            try (var input = Files.newInputStream(source.resolve(metadata))) { properties.load(input); }
        } else {
            try (var jar = new JarFile(source.toFile())) {
                var entry = jar.getJarEntry(metadata);
                if (entry == null) throw new IOException("RUNTIME_METADATA_MISSING: " + metadata);
                try (var input = jar.getInputStream(entry)) { properties.load(input); }
            }
        }
        String version = properties.getProperty("version", "");
        if (!group.equals(properties.getProperty("groupId")) || !artifact.equals(properties.getProperty("artifactId"))
                || version.isBlank() || version.contains("${")) {
            throw new IOException("RUNTIME_METADATA_INVALID: " + metadata);
        }
        return new LoadedRuntimeArtifact("maven://" + group + ":" + artifact + ":" + version, version, source);
    }

    public void requireReference(String declared) {
        if (!reference.equals(declared)) {
            throw new IllegalArgumentException("RUNTIME_REFERENCE_MISMATCH: declared=" + declared + ", observed=" + reference);
        }
    }
}
