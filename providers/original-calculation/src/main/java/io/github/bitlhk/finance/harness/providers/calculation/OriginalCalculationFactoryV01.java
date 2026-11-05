package io.github.bitlhk.finance.harness.providers.calculation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;
import static io.github.bitlhk.finance.harness.calculation.CalculationResultV1.*;

/** Private, content-fixed source/input bindings. Metadata planning contacts no source. */
public final class OriginalCalculationFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND="original-calculation-http.v0.1";
    private final String path,digest;
    private Config frozen;
    public record Binding(String principalId,String subjectRef,String sessionRef,String taskRef,
            String assetId,String operationId,String sourceCommit,String sourceEntity,String filesDigest,
            String adapterDigest,String lockDigest,JsonNode request) {
        public Binding {
            for(String s:List.of(principalId,subjectRef,sessionRef,taskRef,assetId,operationId,sourceEntity))text(s);
            if(!OriginalCalculationProvider.FAMILIES.contains(operationId) || !sourceCommit.matches("[a-f0-9]{40}"))invalid();
            for(String s:List.of(filesDigest,adapterDigest,lockDigest))hash(s);
            if(request==null || !request.isObject() || !request.path("assetId").asText().equals(assetId)
                    || !request.path("operationId").asText().equals(operationId) || !request.path("input").isObject() || !request.path("parameters").isObject())invalid();
            for(String s:List.of(principalId,subjectRef,taskRef))if(!s.matches("[A-Za-z0-9_-]{1,100}"))invalid();
            request=request.deepCopy();
        }
        @Override public JsonNode request(){return request.deepCopy();}
        public Map<String,String> sourceContext(){return Map.of("subjectRef",principalId,"customerRef",subjectRef,"taskRef",taskRef);}
        public String capability(){return OriginalCalculationProvider.capability(operationId);}
    }
    public record Config(String schemaVersion,List<Binding> bindings){
        public Config {
            if(!"original-calculation-bindings.v1".equals(schemaVersion) || bindings==null || bindings.isEmpty() || bindings.size()>128)invalid();
            bindings=List.copyOf(bindings);Set<List<String>> seen=new HashSet<>();
            for(Binding b:bindings)if(!seen.add(List.of(b.principalId(),b.subjectRef(),b.sessionRef())))invalid();
        }
        public Binding binding(String principal,String subject,String session){
            return bindings.stream().filter(b->b.principalId().equals(principal) && b.subjectRef().equals(subject) && b.sessionRef().equals(session))
                .findFirst().orElseThrow(()->new SecurityException("CALCULATION_NOT_BOUND"));
        }
    }
    public OriginalCalculationFactoryV01(){this(System.getenv("FH_CALCULATION_BINDINGS"),System.getenv("FH_CALCULATION_BINDINGS_SHA256"));}
    public OriginalCalculationFactoryV01(String path,String digest){this.path=path;this.digest=digest!=null&&digest.startsWith("sha256:")?digest.substring(7):digest;}
    public synchronized Config config(){
        if(frozen==null){
            try{
                if(path==null || digest==null || !digest.matches("[a-f0-9]{64}"))invalid();
                Path file=Path.of(path);
                if(!file.isAbsolute() || !file.toRealPath().equals(file.normalize()) || Files.size(file)>262144)invalid();
                byte[] bytes=Files.readAllBytes(file);
                if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(digest))invalid();
                frozen=HarnessJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(bytes,Config.class);
            }catch(Exception error){throw new IllegalArgumentException("CALCULATION_CONFIG_INVALID");}
        }
        return frozen;
    }
    @Override public String providerId(){return OriginalCalculationProvider.ID;}
    @Override public String connectionKind(){return KIND;}
    @Override public Map<String,Set<String>> requirements(){
        Map<String,Set<String>> out=new LinkedHashMap<>();
        OriginalCalculationProvider.FAMILIES.forEach(f->out.put(OriginalCalculationProvider.capability(f),Set.of(OriginalCalculationProvider.SCOPE)));
        return Collections.unmodifiableMap(out);
    }
    @Override public Map<String,String> configurationDigests(){config();return Map.of("binding-file-sha256",digest);}
    @Override public DescribedFinanceProvider create(Connection connection,Map<String,BoundFinanceProvider> dependencies){
        if(!dependencies.isEmpty())throw new IllegalArgumentException("CALCULATION_DEPENDENCIES_INVALID");
        return new OriginalCalculationProvider(connection,config(),Clock.systemUTC());
    }
}
