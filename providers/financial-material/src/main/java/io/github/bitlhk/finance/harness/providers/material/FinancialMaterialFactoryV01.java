package io.github.bitlhk.finance.harness.providers.material;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.material.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Installed finite HTTP factory; private binding bytes are locked independently. */
public final class FinancialMaterialFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND="financial-material-http.v0.1";
    private final String path,digest;
    private Config frozen;
    public record Binding(String principalId,String subjectRef,String sessionRef,String taskRef,
            MaterialExtractionV1.Mode mode,String toolReleaseDigest) {
        public Binding {
            for(String value:List.of(principalId,subjectRef,sessionRef,taskRef))
                if(!value.matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("MATERIAL_BINDING_INVALID");
            if(mode==null||!toolReleaseDigest.matches("sha256:[a-f0-9]{64}"))throw new IllegalArgumentException("MATERIAL_BINDING_INVALID");
        }
    }
    public record Config(String schemaVersion,List<Binding> bindings) {
        public Config {
            if(!"financial-material-bindings.v1".equals(schemaVersion)||bindings==null||bindings.isEmpty()||bindings.size()>128)
                throw new IllegalArgumentException("MATERIAL_CONFIG_INVALID");
            bindings=List.copyOf(bindings);Set<List<String>> seen=new HashSet<>();
            for(Binding b:bindings)if(!seen.add(List.of(b.principalId(),b.subjectRef(),b.sessionRef())))
                throw new IllegalArgumentException("MATERIAL_CONFIG_INVALID");
        }
        public Binding binding(String principal,String subject,String session){return bindings.stream()
                .filter(b->b.principalId().equals(principal)&&b.subjectRef().equals(subject)&&b.sessionRef().equals(session))
                .findFirst().orElseThrow(()->new SecurityException("ACCESS_DENIED"));}
    }
    public FinancialMaterialFactoryV01(){this(System.getenv("FH_MATERIAL_BINDINGS"),System.getenv("FH_MATERIAL_BINDINGS_SHA256"));}
    public FinancialMaterialFactoryV01(String path,String digest){this.path=path;this.digest=digest==null?null:digest.replaceFirst("^sha256:","");}
    private synchronized Config config(){
        if(frozen==null)try{
            Path file=Path.of(Objects.requireNonNull(path));
            if(!file.isAbsolute()||!file.toRealPath().equals(file.normalize())||Files.size(file)>262144
                    ||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            byte[] bytes=Files.readAllBytes(file);
            if(!digest.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new IllegalArgumentException();
            frozen=HarnessJson.mapper().copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes,Config.class);
        }catch(Exception invalid){throw new IllegalArgumentException("MATERIAL_CONFIG_INVALID");}
        return frozen;
    }
    @Override public String providerId(){return FinancialMaterialProvider.ID;}
    @Override public String connectionKind(){return KIND;}
    @Override public Map<String,Set<String>> requirements(){return Map.of(FinancialMaterialProvider.EXTRACT,Set.of(FinancialMaterialProvider.SCOPE),FinancialMaterialProvider.LOCATE,Set.of(FinancialMaterialProvider.SCOPE));}
    @Override public Map<String,String> configurationDigests(){config();return Map.of("binding-file-sha256",digest);}
    @Override public DescribedFinanceProvider create(Connection connection,Map<String,BoundFinanceProvider> dependencies){
        if(!KIND.equals(connection.kind())||!dependencies.isEmpty())throw new IllegalArgumentException("MATERIAL_CONNECTION_INVALID");
        return new FinancialMaterialProvider(connection,config(),Clock.systemUTC());
    }
}
