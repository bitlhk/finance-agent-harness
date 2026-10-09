package io.github.bitlhk.finance.harness.providers.curatedmcp;

import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.Path;
import java.util.*;

/** Discovery is not admission. Both source file and its hash must be supplied by the Host. */
public final class CuratedMcpFactoryV01 implements ManagedProviderFactoryV01 {
    private final Map<String, String> environment;
    public CuratedMcpFactoryV01() { this(System.getenv()); }
    CuratedMcpFactoryV01(Map<String, String> environment) {
        var selected = new HashMap<String, String>();
        for (String name : List.of("FH_CURATED_MCP_CONFIG", "FH_CURATED_MCP_CONFIG_SHA256"))
            if (environment.containsKey(name)) selected.put(name, environment.get(name));
        this.environment = Map.copyOf(selected);
    }
    public String providerId() { return CuratedMcpProvider.ID; }
    public String connectionKind() { return "synthetic-bank-http.v1"; }
    public Map<String, Set<String>> requirements() {
        var result = new LinkedHashMap<String, Set<String>>();
        CuratedTools.ALL.forEach(t -> result.put(t.capabilityId(), Set.of(t.scope())));
        return Map.copyOf(result);
    }
    public DescribedFinanceProvider create(Connection authority, Map<String, BoundFinanceProvider> dependencies) {
        if (!dependencies.isEmpty()) throw new IllegalArgumentException("CURATED_DEPENDENCIES_UNSUPPORTED");
        return new CuratedMcpProvider(authority, source());
    }
    public Map<String, String> configurationDigests() {
        source();
        return Map.of("source", environment.get("FH_CURATED_MCP_CONFIG_SHA256"));
    }
    private CuratedSourceConfig source() {
        String path = environment.get("FH_CURATED_MCP_CONFIG"), pin = environment.get("FH_CURATED_MCP_CONFIG_SHA256");
        if (path == null || pin == null) throw new IllegalArgumentException("CURATED_CONFIG_REQUIRED");
        var source = CuratedSourceConfig.load(Path.of(path), pin);
        if (source.runtimePolicy() == null) throw new IllegalArgumentException("CURATED_BOUNDED_SOURCE_REQUIRED");
        return source;
    }
}
