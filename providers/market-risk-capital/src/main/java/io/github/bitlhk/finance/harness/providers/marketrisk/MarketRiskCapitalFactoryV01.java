package io.github.bitlhk.finance.harness.providers.marketrisk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.marketrisk.MarketRiskCapitalMapper.Expected;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Finite installed factory. The binding file contains no source credentials. */
public final class MarketRiskCapitalFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND="market-risk-capital-http.v0.1";
    private final String path, digest;
    private Config frozen;

    public record Binding(String principalId, String subjectRef, String sessionRef, String taskRef,
            String snapshotRef, String snapshotDigest, String toolReleaseDigest, String upstreamRevision,
            String parametersDigest, String paramVersion, String engineVersion) {
        public Binding { new Expected("binding-check",principalId,subjectRef,taskRef,sessionRef,snapshotRef,
                snapshotDigest,toolReleaseDigest,upstreamRevision,parametersDigest,paramVersion,engineVersion); }
        public Expected expected(String requestId) { return new Expected(requestId,principalId,subjectRef,taskRef,
                sessionRef,snapshotRef,snapshotDigest,toolReleaseDigest,upstreamRevision,parametersDigest,paramVersion,engineVersion); }
    }
    public record Config(String schemaVersion, List<Binding> bindings) {
        public Config {
            if(!"market-risk-capital-bindings.v1".equals(schemaVersion) || bindings==null || bindings.isEmpty()
                    || bindings.size()>128) throw new IllegalArgumentException("MARKET_RISK_CONFIG_INVALID");
            bindings=List.copyOf(bindings);
            Set<List<String>> seen=new HashSet<>();
            for(Binding b:bindings) if(!seen.add(List.of(b.principalId(),b.subjectRef(),b.sessionRef())))
                throw new IllegalArgumentException("MARKET_RISK_CONFIG_INVALID");
        }
        public Binding binding(String principal,String subject,String session) {
            return bindings.stream().filter(b->b.principalId().equals(principal) && b.subjectRef().equals(subject)
                    && b.sessionRef().equals(session)).findFirst().orElseThrow(()->new SecurityException("ACCESS_DENIED"));
        }
    }
    public MarketRiskCapitalFactoryV01() {
        this(System.getenv("FH_MARKET_RISK_BINDINGS"),System.getenv("FH_MARKET_RISK_BINDINGS_SHA256"));
    }
    public MarketRiskCapitalFactoryV01(String path,String digest) {
        this.path=path;this.digest=digest!=null && digest.startsWith("sha256:")?digest.substring(7):digest;
    }
    private synchronized Config config() {
        if(frozen==null) {
            try {
                if(path==null || digest==null || !digest.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
                Path file=Path.of(path);
                if(!file.isAbsolute() || !file.toRealPath().equals(file.normalize()) || Files.size(file)>262144) throw new IllegalArgumentException();
                byte[] bytes=Files.readAllBytes(file);
                String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!digest.equals(actual)) throw new IllegalArgumentException();
                frozen=HarnessJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes,Config.class);
            } catch(Exception invalid) { throw new IllegalArgumentException("MARKET_RISK_CONFIG_INVALID"); }
        }
        return frozen;
    }
    @Override public String providerId() { return MarketRiskCapitalProvider.ID; }
    @Override public String connectionKind() { return KIND; }
    @Override public Map<String,Set<String>> requirements() {
        return Map.of(MarketRiskCapitalProvider.CAPABILITY,Set.of(MarketRiskCapitalProvider.SCOPE));
    }
    @Override public Map<String,String> configurationDigests() {
        config(); return Map.of("binding-file-sha256",digest);
    }
    @Override public DescribedFinanceProvider create(Connection connection,Map<String,BoundFinanceProvider> dependencies) {
        if(!KIND.equals(connection.kind()) || !dependencies.isEmpty()) throw new IllegalArgumentException("MARKET_RISK_CONNECTION_INVALID");
        return new MarketRiskCapitalProvider(connection,config(),Clock.systemUTC());
    }
}
