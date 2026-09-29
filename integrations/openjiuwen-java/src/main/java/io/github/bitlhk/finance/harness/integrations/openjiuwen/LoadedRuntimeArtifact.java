package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import java.io.IOException;
import java.nio.file.Path;
/** Compatibility facade; retains package-private runtime metadata API. */
record LoadedRuntimeArtifact(String reference, String version, Path source) {
    static LoadedRuntimeArtifact read(Class<?> anchor, String metadata, String group, String artifact) throws IOException {
        var value = io.github.bitlhk.finance.harness.assembly.LoadedRuntimeArtifact.read(anchor, metadata, group, artifact);
        return new LoadedRuntimeArtifact(value.reference(), value.version(), value.source());
    }
    void requireReference(String declared) {
        new io.github.bitlhk.finance.harness.assembly.LoadedRuntimeArtifact(reference, version, source).requireReference(declared);
    }
}
