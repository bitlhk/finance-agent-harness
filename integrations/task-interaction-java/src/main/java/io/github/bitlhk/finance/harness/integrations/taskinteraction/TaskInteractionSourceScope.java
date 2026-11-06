package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.providers.taskinteraction.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Current deployment authority stays outside the artifact, model and browser. */
public final class TaskInteractionSourceScope implements AutoCloseable {
    public record Lease(String principalId,String subjectRef,String taskRef,String sessionRef,String credential,Instant expiresAt,Set<String> scopes){
        public Lease {
            for(var id:List.of(principalId,subjectRef,taskRef,sessionRef))TaskInteractionMapper.id(id);
            if(credential==null||credential.length()<24||credential.length()>512||expiresAt==null)throw new IllegalArgumentException("TASK_AUTHORITY_INVALID");
            scopes=Set.copyOf(scopes);
        }
        @Override public String toString(){return "TaskLease[redacted]";}
    }
    public record Authority(String schemaVersion,String ownerRef,String endpoint,int timeoutMs,String bindingsFile,String bindingsDigest,List<Lease> leases){
        public Authority {
            TaskInteractionMapper.id(ownerRef);
            if(!"original-task-host-authority.v1".equals(schemaVersion)||timeoutMs<100||timeoutMs>60000||endpoint==null||bindingsFile==null||bindingsDigest==null||leases==null||leases.isEmpty()||leases.size()>128)throw new IllegalArgumentException("TASK_AUTHORITY_INVALID");
            leases=List.copyOf(leases);
            if(leases.stream().map(Lease::sessionRef).distinct().count()!=leases.size())throw new IllegalArgumentException("TASK_AUTHORITY_INVALID");
        }
        @Override public String toString(){return "TaskAuthority[redacted]";}
    }
    private final ManagedScenarioExtensionV01.Context context;
    private final Path file;
    private final Authority initial;
    private final Lease selected;
    public final ManagedOriginalTaskFactoryV01 factory;
    private volatile boolean closed;
    public TaskInteractionSourceScope(ManagedScenarioExtensionV01.Context context) throws Exception {
        this.context=context;
        Path root=context.artifactRoot().toRealPath(),state=context.privateStateRoot().toAbsolutePath().normalize();
        if(state.startsWith(root)||!state.toRealPath().equals(state)||Files.isSymbolicLink(state))throw new SecurityException("PRIVATE_STATE_REQUIRED");
        file=state.resolve("task-authority.json");initial=read();
        if(!context.ownerRef().equals(initial.ownerRef()))throw new SecurityException("TASK_OWNER_MISMATCH");
        var choices=initial.leases().stream().filter(l->l.principalId().equals(context.ownerRef())).toList();
        if(choices.size()!=1)throw new SecurityException("TASK_SINGLE_SCOPE_REQUIRED");
        selected=choices.getFirst();factory=new ManagedOriginalTaskFactoryV01(initial.bindingsFile(),initial.bindingsDigest());
        var b=binding();if(!b.taskRef().equals(selected.taskRef()))throw new SecurityException("TASK_BINDING_MISMATCH");
    }
    private Authority read(){
        try{
            if(Files.isSymbolicLink(file)||!file.toRealPath().equals(file.toAbsolutePath().normalize())||Files.size(file)>262144)throw new SecurityException("TASK_AUTHORITY_INVALID");
            return TaskInteractionMapper.json().readValue(Files.readAllBytes(file),Authority.class);
        }catch(Exception failure){throw new SecurityException("TASK_AUTHORITY_UNAVAILABLE");}
    }
    public Lease current(){
        if(closed)throw new SecurityException("TASK_SESSION_CLOSED");
        Authority now=read();
        if(!now.ownerRef().equals(initial.ownerRef())||!now.endpoint().equals(initial.endpoint())||now.timeoutMs()!=initial.timeoutMs()
                ||!now.bindingsFile().equals(initial.bindingsFile())||!now.bindingsDigest().equals(initial.bindingsDigest()))throw new SecurityException("TASK_AUTHORITY_CHANGED");
        Lease lease=now.leases().stream().filter(l->l.sessionRef().equals(selected.sessionRef())).findFirst().orElseThrow(()->new SecurityException("TASK_AUTHORITY_REVOKED"));
        if(!lease.principalId().equals(selected.principalId())||!lease.subjectRef().equals(selected.subjectRef())||!lease.taskRef().equals(selected.taskRef())||!lease.expiresAt().isAfter(Instant.now())||!lease.scopes().contains(ManagedOriginalTaskProvider.READ))throw new SecurityException("TASK_AUTHORITY_REJECTED");
        return lease;
    }
    public Authority initial(){return initial;}
    public ManagedOriginalTaskFactoryV01.Binding binding(){var l=current();return factory.config().binding(l.principalId(),l.subjectRef(),l.sessionRef());}
    public ManagedProviderFactoryV01.SourceSession session(){var l=current();return new ManagedProviderFactoryV01.SourceSession(l.credential(),l.subjectRef(),l.principalId(),l.sessionRef(),l.expiresAt());}
    public BoundFinanceProvider.Grant grant(){var l=current();return new BoundFinanceProvider.Grant(l.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,Set.of(l.subjectRef()),Set.of("CUSTODY"),l.scopes(),l.sessionRef(),"host-private-task",l.expiresAt());}
    @Override public void close(){closed=true;}
}
