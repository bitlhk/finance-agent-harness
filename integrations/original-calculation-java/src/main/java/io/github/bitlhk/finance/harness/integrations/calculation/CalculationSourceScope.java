package io.github.bitlhk.finance.harness.integrations.calculation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.providers.calculation.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Host-owned current authority. Source identity/input is content-fixed separately. */
public final class CalculationSourceScope implements AutoCloseable {
    private static final Map<Path,Set<String>> LEASED=new HashMap<>();
    private static final ObjectMapper JSON=HarnessJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ManagedScenarioExtensionV01.Context context;
    private final Path file;
    private final Authority initial;
    private final Lease selected;
    private volatile boolean closed;
    public final OriginalCalculationFactoryV01 factory;
    public record Lease(String principalId,String subjectRef,String taskRef,String sessionRef,String credential,Instant expiresAt,Set<String> scopes){
        public Lease {
            for(String s:List.of(principalId,subjectRef,taskRef,sessionRef,credential))required(s);
            Objects.requireNonNull(expiresAt);scopes=Set.copyOf(scopes);
        }
        @Override public String toString(){return "CalculationLease[redacted]";}
    }
    public record Authority(String schemaVersion,String ownerRef,String endpoint,int timeoutMs,String bindingsFile,String bindingsDigest,List<Lease> leases){
        public Authority {
            if(!"original-calculation-host-authority.v1".equals(schemaVersion) || timeoutMs<10 || timeoutMs>60000 || leases==null || leases.isEmpty() || leases.size()>128)invalid();
            for(String s:List.of(ownerRef,endpoint,bindingsFile,bindingsDigest))required(s);
            leases=List.copyOf(leases);Set<String> ids=new HashSet<>();for(Lease l:leases)if(!ids.add(l.sessionRef()))invalid();
        }
        @Override public String toString(){return "CalculationAuthority[redacted]";}
    }
    public CalculationSourceScope(ManagedScenarioExtensionV01.Context context) throws Exception{
        this.context=context;
        Path artifact=context.artifactRoot().toRealPath(),state=context.privateStateRoot().toAbsolutePath().normalize();
        if(state.startsWith(artifact) || !state.toRealPath().equals(state) || Files.isSymbolicLink(state))throw new SecurityException("PRIVATE_STATE_REQUIRED");
        file=state.resolve("calculation-authority.json");initial=read();
        if(!initial.ownerRef().equals(context.ownerRef()))throw new SecurityException("CALCULATION_OWNER_MISMATCH");
        synchronized(LEASED){
            Set<String> used=LEASED.computeIfAbsent(file,key->new HashSet<>());
            selected=initial.leases().stream().filter(l->!used.contains(l.sessionRef()) && l.expiresAt().isAfter(Instant.now()) && l.scopes().contains(OriginalCalculationProvider.SCOPE))
                .findFirst().orElseThrow(()->new SecurityException("NO_CURRENT_CALCULATION_TASK"));
            used.add(selected.sessionRef());
        }
        try{
            Path binding=Path.of(initial.bindingsFile());
            if(!binding.isAbsolute() || binding.normalize().startsWith(artifact))throw new SecurityException("PRIVATE_BINDINGS_REQUIRED");
            factory=new OriginalCalculationFactoryV01(binding.toString(),initial.bindingsDigest());factory.configurationDigests();
            var b=factory.config().binding(selected.principalId(),selected.subjectRef(),selected.sessionRef());
            if(!b.taskRef().equals(selected.taskRef()))throw new SecurityException("CALCULATION_TASK_MISMATCH");
            current();
        }catch(Exception error){close();throw error;}
    }
    private Authority read(){
        try{
            if(Files.isSymbolicLink(file) || Files.size(file)>262144)invalid();
            return JSON.readValue(Files.readAllBytes(file),Authority.class);
        }catch(Exception error){throw new SecurityException("CALCULATION_AUTHORITY_UNAVAILABLE");}
    }
    public Lease current(){
        if(closed)throw new SecurityException("CALCULATION_SESSION_CLOSED");
        Authority now=read();
        if(!now.ownerRef().equals(context.ownerRef()) || !now.endpoint().equals(initial.endpoint()) || now.timeoutMs()!=initial.timeoutMs()
                || !now.bindingsFile().equals(initial.bindingsFile()) || !now.bindingsDigest().equals(initial.bindingsDigest()))throw new SecurityException("CALCULATION_AUTHORITY_CHANGED");
        Lease lease=now.leases().stream().filter(l->l.sessionRef().equals(selected.sessionRef())).findFirst().orElseThrow(()->new SecurityException("CALCULATION_AUTHORIZATION_REVOKED"));
        if(!lease.principalId().equals(selected.principalId()) || !lease.subjectRef().equals(selected.subjectRef()) || !lease.taskRef().equals(selected.taskRef())
                || !lease.expiresAt().isAfter(Instant.now()) || !lease.scopes().contains(OriginalCalculationProvider.SCOPE))throw new SecurityException("CALCULATION_AUTHORIZATION_REJECTED");
        return lease;
    }
    public OriginalCalculationFactoryV01.Binding binding(){var l=current();return factory.config().binding(l.principalId(),l.subjectRef(),l.sessionRef());}
    public Authority initial(){return initial;}
    public ManagedProviderFactoryV01.SourceSession session(){var l=current();return new ManagedProviderFactoryV01.SourceSession(l.credential(),l.subjectRef(),l.principalId(),l.sessionRef(),l.expiresAt());}
    public BoundFinanceProvider.Grant grant(){var l=current();return new BoundFinanceProvider.Grant(l.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,Set.of(l.subjectRef()),Set.of("CALCULATION"),l.scopes(),l.sessionRef(),"host-private-calculation",l.expiresAt());}
    public void requireStoredResult(String runId){
        current();
        var connection=new ManagedProviderFactoryV01.Connection(OriginalCalculationFactoryV01.KIND,java.net.URI.create(initial.endpoint()),
            Duration.ofMillis(initial.timeoutMs()),this::session,()->null,attempt->{});
        try(var provider=(OriginalCalculationProvider)factory.create(connection,Map.of())){provider.checkStoredStatus(runId);}
        current();
    }
    @Override public void close(){
        synchronized(LEASED){if(closed)return;closed=true;Set<String> used=LEASED.get(file);if(used!=null){used.remove(selected.sessionRef());if(used.isEmpty())LEASED.remove(file);}}
    }
    private static void required(String s){if(s==null || s.isBlank())invalid();}
    private static void invalid(){throw new IllegalArgumentException("CALCULATION_AUTHORITY_INVALID");}
}
