package io.github.bitlhk.finance.harness.integrations.report;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.providers.report.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Host-owned current authority. Source identity/input is content-fixed separately. */
public final class ReportSourceScope implements AutoCloseable {
    private static final Map<Path,Set<String>> LEASED=new HashMap<>();
    private static final ObjectMapper JSON=HarnessJson.mapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ManagedScenarioExtensionV01.Context context;
    private final Path file;
    private final Authority initial;
    private final Lease selected;
    private volatile boolean closed;
    public final ProfessionalReportFactoryV01 factory;
    public record Lease(String principalId,String subjectRef,String taskRef,String sessionRef,String credential,Instant expiresAt,Set<String> scopes){
        public Lease {
            for(String s:List.of(principalId,subjectRef,taskRef,sessionRef,credential))required(s);
            Objects.requireNonNull(expiresAt);scopes=Set.copyOf(scopes);
        }
        @Override public String toString(){return "ReportLease[redacted]";}
    }
    public record Authority(String schemaVersion,String ownerRef,String endpoint,int timeoutMs,String bindingsFile,String bindingsDigest,List<Lease> leases){
        public Authority {
            if(!"professional-report-host-authority.v1".equals(schemaVersion) || timeoutMs<100 || timeoutMs>120000 || leases==null || leases.isEmpty() || leases.size()>128)invalid();
            for(String s:List.of(ownerRef,endpoint,bindingsFile,bindingsDigest))required(s);
            leases=List.copyOf(leases);Set<String> ids=new HashSet<>();for(Lease l:leases)if(!ids.add(l.sessionRef()))invalid();
        }
        @Override public String toString(){return "ReportAuthority[redacted]";}
    }
    public ReportSourceScope(ManagedScenarioExtensionV01.Context context) throws Exception{
        this.context=context;
        Path artifact=context.artifactRoot().toRealPath(),state=context.privateStateRoot().toAbsolutePath().normalize();
        if(state.startsWith(artifact) || !state.toRealPath().equals(state) || Files.isSymbolicLink(state))throw new SecurityException("PRIVATE_STATE_REQUIRED");
        file=state.resolve("report-authority.json");initial=read();
        if(!initial.ownerRef().equals(context.ownerRef()))throw new SecurityException("REPORT_OWNER_MISMATCH");
        synchronized(LEASED){
            Set<String> used=LEASED.computeIfAbsent(file,key->new HashSet<>());
            selected=initial.leases().stream().filter(l->!used.contains(l.sessionRef()) && l.expiresAt().isAfter(Instant.now()) && l.scopes().contains(ProfessionalReportProvider.SCOPE))
                .findFirst().orElseThrow(()->new SecurityException("NO_CURRENT_REPORT_TASK"));
            used.add(selected.sessionRef());
        }
        try{
            Path binding=Path.of(initial.bindingsFile());
            if(!binding.isAbsolute() || binding.normalize().startsWith(artifact))throw new SecurityException("PRIVATE_BINDINGS_REQUIRED");
            factory=new ProfessionalReportFactoryV01(binding.toString(),initial.bindingsDigest());factory.configurationDigests();
            var b=factory.config().binding(selected.principalId(),selected.subjectRef(),selected.sessionRef());
            if(!b.context().path("taskRef").asText().equals(selected.taskRef())||!selected.principalId().equals(context.ownerRef()))throw new SecurityException("REPORT_TASK_MISMATCH");
            current();
        }catch(Exception error){close();throw error;}
    }
    private Authority read(){
        try{
            if(Files.isSymbolicLink(file) || Files.size(file)>262144)invalid();
            return JSON.readValue(Files.readAllBytes(file),Authority.class);
        }catch(Exception error){throw new SecurityException("REPORT_AUTHORITY_UNAVAILABLE");}
    }
    public Lease current(){
        if(closed)throw new SecurityException("REPORT_SESSION_CLOSED");
        Authority now=read();
        if(!now.ownerRef().equals(context.ownerRef()) || !now.endpoint().equals(initial.endpoint()) || now.timeoutMs()!=initial.timeoutMs()
                || !now.bindingsFile().equals(initial.bindingsFile()) || !now.bindingsDigest().equals(initial.bindingsDigest()))throw new SecurityException("REPORT_AUTHORITY_CHANGED");
        Lease lease=now.leases().stream().filter(l->l.sessionRef().equals(selected.sessionRef())).findFirst().orElseThrow(()->new SecurityException("REPORT_AUTHORIZATION_REVOKED"));
        if(!lease.principalId().equals(selected.principalId()) || !lease.subjectRef().equals(selected.subjectRef()) || !lease.taskRef().equals(selected.taskRef())
                || !lease.expiresAt().isAfter(Instant.now()) || !lease.scopes().contains(ProfessionalReportProvider.SCOPE))throw new SecurityException("REPORT_AUTHORIZATION_REJECTED");
        return lease;
    }
    /** The artifact pins method/build; only the trusted Host supplies its startup-generated instance. */
    public ProfessionalReportFactoryV01.Config executionConfig(){
        current();
        var values=factory.config().bindings().stream().map(b->{
            var request=(com.fasterxml.jackson.databind.node.ObjectNode)b.request();
            var binding=(com.fasterxml.jackson.databind.node.ObjectNode)request.path("binding");
            String configured=binding.path("instanceId").asText();
            if(!configured.equals("HOST_INSTANCE")&&!configured.equals(context.instanceId()))throw new SecurityException("REPORT_INSTANCE_BINDING_MISMATCH");
            if(!binding.path("buildId").asText().equals(context.buildId()))throw new SecurityException("REPORT_BUILD_BINDING_MISMATCH");
            binding.put("instanceId",context.instanceId());
            return new ProfessionalReportFactoryV01.Binding(b.principalId(),b.subjectRef(),b.sessionRef(),b.context(),request);
        }).toList();
        return new ProfessionalReportFactoryV01.Config("professional-report-bindings.v1",values);
    }
    public ProfessionalReportFactoryV01.Binding binding(){var l=current();return executionConfig().binding(l.principalId(),l.subjectRef(),l.sessionRef());}
    public Authority initial(){return initial;}
    public ManagedProviderFactoryV01.SourceSession session(){var l=current();return new ManagedProviderFactoryV01.SourceSession(l.credential(),l.subjectRef(),l.principalId(),l.sessionRef(),l.expiresAt());}
    public BoundFinanceProvider.Grant grant(){var l=current();return new BoundFinanceProvider.Grant(l.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,Set.of(l.subjectRef()),Set.of("REPORT"),l.scopes(),l.sessionRef(),"host-private-report",l.expiresAt());}
    @Override public void close(){
        synchronized(LEASED){if(closed)return;closed=true;Set<String> used=LEASED.get(file);if(used!=null){used.remove(selected.sessionRef());if(used.isEmpty())LEASED.remove(file);}}
    }
    private static void required(String s){if(s==null || s.isBlank())invalid();}
    private static void invalid(){throw new IllegalArgumentException("REPORT_AUTHORITY_INVALID");}
}
