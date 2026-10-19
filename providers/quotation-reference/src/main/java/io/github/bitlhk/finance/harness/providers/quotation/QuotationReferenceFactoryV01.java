package io.github.bitlhk.finance.harness.providers.quotation;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** ServiceLoader entry for a preinstalled reviewed module, not an asset-selected class. */
public final class QuotationReferenceFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND = "quotation-reference.v0.1";
    private final String path, digest;
    private QuotationReferenceConfig frozen;
    public QuotationReferenceFactoryV01() {
        this(System.getenv("FH_QUOTATION_REFERENCE_CONFIG"), System.getenv("FH_QUOTATION_REFERENCE_CONFIG_SHA256"));
    }
    QuotationReferenceFactoryV01(String path, String digest) { this.path = path; this.digest = digest; }
    private synchronized QuotationReferenceConfig config() {
        if (frozen == null) {
            if (path == null || digest == null || !Path.of(path).isAbsolute())
                throw new IllegalArgumentException("QUOTATION_CONFIG_INVALID");
            frozen = QuotationReferenceConfig.load(Path.of(path), digest);
        }
        return frozen;
    }
    public String providerId() { return QuotationReferenceProvider.ID; }
    public String connectionKind() { return KIND; }
    public Map<String,Set<String>> requirements() {
        return Map.of(QuotationReferenceProvider.CAPABILITY, Set.of(QuotationReferenceProvider.SCOPE));
    }
    public Map<String,String> configurationDigests() { return Map.of("configurationFileSha256", config().sha256()); }
    public DescribedFinanceProvider create(Connection connection, Map<String,BoundFinanceProvider> dependencies) {
        if (!KIND.equals(connection.kind()) || !dependencies.isEmpty()
                || !"reference://quotation".equals(connection.endpoint().toString()))
            throw new IllegalArgumentException("QUOTATION_CONNECTION_INVALID");
        return new QuotationReferenceProvider(config(), Clock.systemUTC(), connection.currentSession());
    }
}
