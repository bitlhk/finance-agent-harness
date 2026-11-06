package io.github.bitlhk.finance.harness.providers.taskinteraction;

import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Fixed configuration is deployment data. It never carries a credential or grants a role. */
public final class ManagedOriginalTaskFactoryV01 implements ManagedProviderFactoryV01 {
    public static final String KIND="original-task-http.v0.1";
    public record Binding(String principalId,String subjectRef,String sessionRef,String taskRef,String sourceObjectRef,String sourceRelease) {
        public Binding {
            for(var v:List.of(principalId,subjectRef,sessionRef,taskRef,sourceObjectRef))TaskInteractionMapper.id(v);
            TaskInteractionMapper.hash(sourceRelease);
        }
        public TaskInteractionMapper.Expected expected(){return new TaskInteractionMapper.Expected(taskRef,sourceObjectRef,sourceRelease);}
    }
    public record Config(String schemaVersion,List<Binding> bindings) {
        public Config {
            if(!"original-task-bindings.v1".equals(schemaVersion)||bindings==null||bindings.isEmpty()||bindings.size()>128)throw new IllegalArgumentException("TASK_CONFIG_INVALID");
            bindings=List.copyOf(bindings);Set<List<String>> keys=new HashSet<>();
            for(var b:bindings)if(!keys.add(List.of(b.principalId(),b.subjectRef(),b.sessionRef())))throw new IllegalArgumentException("TASK_CONFIG_DUPLICATE");
        }
        public Binding binding(String principal,String subject,String session){
            return bindings.stream().filter(b->b.principalId().equals(principal)&&b.subjectRef().equals(subject)&&b.sessionRef().equals(session)).findFirst()
                .orElseThrow(()->new SecurityException("TASK_SOURCE_NOT_BOUND"));
        }
    }
    private final String path;
    private final String digest;
    private Config cached;
    public ManagedOriginalTaskFactoryV01(){this(System.getenv("FH_TASK_BINDINGS"),System.getenv("FH_TASK_BINDINGS_SHA256"));}
    public ManagedOriginalTaskFactoryV01(String path,String digest){this.path=path;this.digest=digest==null?null:digest.replaceFirst("^sha256:","");}
    public synchronized Config config(){
        if(cached==null)try{
            if(path==null||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();
            Path p=Path.of(path);if(!p.isAbsolute()||!p.toRealPath().equals(p.normalize())||Files.size(p)>262144)throw new IllegalArgumentException();
            byte[] raw=Files.readAllBytes(p);
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)).equals(digest))throw new IllegalArgumentException();
            cached=TaskInteractionMapper.json().readValue(raw,Config.class);
        }catch(Exception failure){throw new IllegalArgumentException("TASK_CONFIG_INVALID");}
        return cached;
    }
    @Override public String providerId(){return ManagedOriginalTaskProvider.ID;}
    @Override public String connectionKind(){return KIND;}
    @Override public Map<String,Set<String>> requirements(){return Map.of(ManagedOriginalTaskProvider.QUERY,Set.of(ManagedOriginalTaskProvider.READ),ManagedOriginalTaskProvider.ACTION,Set.of(ManagedOriginalTaskProvider.WRITE));}
    @Override public Map<String,String> configurationDigests(){config();return Map.of("binding-file-sha256",digest);}
    @Override public DescribedFinanceProvider create(Connection connection,Map<String,BoundFinanceProvider> dependencies){
        if(!dependencies.isEmpty())throw new IllegalArgumentException("TASK_DEPENDENCIES_INVALID");
        return new ManagedOriginalTaskProvider(connection,config(),Clock.systemUTC());
    }
}
