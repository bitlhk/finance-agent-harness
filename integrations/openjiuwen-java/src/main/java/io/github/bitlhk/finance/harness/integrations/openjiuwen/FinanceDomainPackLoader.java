package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import io.github.bitlhk.finance.harness.core.HarnessJson;
/** Compatibility facade retaining the original record and method signatures. */
public final class FinanceDomainPackLoader {
    private final io.github.bitlhk.finance.harness.assembly.FinanceDomainPackLoader delegate = new io.github.bitlhk.finance.harness.assembly.FinanceDomainPackLoader();
    public LoadedDomainPack load(Path file) throws IOException {
        var loaded = delegate.load(file);
        return new LoadedDomainPack(HarnessJson.mapper().convertValue(loaded.pack(), FinanceDomainPackV1.class),
                loaded.packFile(), loaded.skills().stream().map(s -> new LoadedSkill(s.directory(), s.entry(), s.instructions(), s.digest())).toList());
    }
    public record LoadedDomainPack(FinanceDomainPackV1 pack, Path packFile, List<LoadedSkill> skills) {
        public LoadedDomainPack { skills = List.copyOf(skills); }
    }
    public record LoadedSkill(String directory, String entry, String instructions, String digest) {}
}
