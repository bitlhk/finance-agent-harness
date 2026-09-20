package io.github.bitlhk.finance.harness.integrations.openjiuwen;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import io.github.bitlhk.finance.harness.core.HarnessJson;
/** Compatibility facade; parsing and schema validation live in the neutral assembly module. */
public final class FinanceCapabilityPackLoader {
    private final io.github.bitlhk.finance.harness.assembly.FinanceCapabilityPackLoader delegate = new io.github.bitlhk.finance.harness.assembly.FinanceCapabilityPackLoader();
    public FinanceCapabilityPackV1 load(Path path) throws IOException {
        return HarnessJson.mapper().convertValue(delegate.load(path), FinanceCapabilityPackV1.class);
    }
    public FinanceCapabilityPackV1 load(InputStream stream) throws IOException {
        return HarnessJson.mapper().convertValue(delegate.load(stream), FinanceCapabilityPackV1.class);
    }
}
