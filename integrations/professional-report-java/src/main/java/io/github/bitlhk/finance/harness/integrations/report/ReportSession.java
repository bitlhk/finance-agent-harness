package io.github.bitlhk.finance.harness.integrations.report;

import com.fasterxml.jackson.databind.*;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.providers.report.*;
import io.github.bitlhk.finance.harness.report.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Report lifecycle with generation-guarded publication, immutable history and current source access. */
public final class ReportSession implements ManagedScenarioExtensionV01.Session {
    private static final ObjectMapper JSON=ReportMapper.json();
    private final ManagedScenarioExtensionV01.Context context;
    private final Path root;
    private final JsonNode lock,plan;
    private final String id="report-session-"+UUID.randomUUID(),lockDigest;
    private final ReportSourceScope scope;
    private final ReportVersions reports;
    private final InstalledPreparationMethod method;
    private final Object state=new Object();
    private int generation;
    private boolean closed;
    private Thread active;
    private ProfessionalReportProvider activeProvider;
    private Map<String,Object> latest;
    public ReportSession(ManagedScenarioExtensionV01.Context c) throws Exception {
        context=c;root=c.artifactRoot().toRealPath();byte[] bytes=Files.readAllBytes(root.resolve("service-lock.json"));lock=ReportMapper.parse(bytes);lockDigest=ReportMapper.sha(bytes);plan=ReportMapper.parse(Files.readAllBytes(root.resolve("component-plan.json")));
        if(!ReportScenarioExtensionV01.ID.equals(lock.path("scenarioExtension").path("id").asText())||!Set.of("wealth-kyc-report-readonly","market-risk-report-readonly").contains(plan.path("profileRef").path("id").asText())
            ||!"maven://com.openjiuwen:agent-core-java:0.1.15".equals(plan.path("runtime").path("templateRef").asText())||plan.path("capabilityPackRefs").size()!=1||plan.path("domainPackRefs").size()!=1)throw new SecurityException("REPORT_COMPOSITION_INVALID");
        verifyFiles();scope=new ReportSourceScope(c);
        try {
            var actual=JSON.valueToTree(Map.of(ProfessionalReportProvider.ID,scope.factory.configurationDigests()));if(!actual.equals(lock.path("providerConfiguration")))throw new SecurityException("REPORT_CONFIGURATION_NOT_LOCKED");
            var b=scope.binding().request().path("binding");boolean wealth=scope.binding().request().path("assetId").asText().equals("wealth-kyc");if(wealth!=plan.path("profileRef").path("id").asText().equals("wealth-kyc-report-readonly"))throw new SecurityException("REPORT_PROFILE_SOURCE_MISMATCH");
            String mid=lock.path("method").path("assetId").asText(),version=lock.path("method").path("version").asText();
            if(!mid.matches("[a-z0-9-]{1,100}")||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))throw new SecurityException("REPORT_METHOD_NOT_LOCKED");
            String relative="assets/private-methods/"+mid+"/"+version+"/skills/"+mid+"/SKILL.md";String digest=lock.path("files").path(relative).asText();
            method=InstalledPreparationMethod.load(mid,version,root.resolve(relative),"sha256:"+digest,lockDigest);
            if(!method.id().equals(b.path("methodId").asText())||!method.version().equals(b.path("methodVersion").asText())||!method.digest().equals(b.path("methodDigest").asText())||!c.buildId().equals(b.path("buildId").asText())||!c.instanceId().equals(b.path("instanceId").asText()))throw new SecurityException("REPORT_METHOD_BINDING_MISMATCH");
            reports=new ReportVersions(c.privateStateRoot().resolve("reports").resolve(c.instanceId()),new ReportAccess(c.ownerRef(),s->{scope.current();return s.equals(scope.binding().context());}),Clock.systemUTC());
        }catch(Exception failure){scope.close();throw failure;}
    }
    private void verifyFiles() throws Exception {
        if(!ReportMapper.sha(Files.readAllBytes(root.resolve("service-lock.json"))).equals(lockDigest)||!lock.path("files").has("component-plan.json"))throw new SecurityException("REPORT_LOCK_CHANGED");
        var entries=lock.path("files").fields();while(entries.hasNext()){var e=entries.next();String relative=e.getKey();if(!relative.startsWith("assets/")&&!relative.equals("component-plan.json"))continue;
            Path p=root.resolve(relative).normalize();if(!p.startsWith(root)||!Files.isRegularFile(p)||!p.toRealPath().equals(p)||!ReportMapper.sha(Files.readAllBytes(p)).equals("sha256:"+e.getValue().asText()))throw new SecurityException("REPORT_ARTIFACT_CHANGED");}
    }
    private void current(int expected){scope.current();synchronized(state){if(closed||generation!=expected)throw new SecurityException("REPORT_DISCLOSURE_CLOSED");}}
    private ProfessionalReportProvider provider(){return new ProfessionalReportProvider(new ManagedProviderFactoryV01.Connection(ProfessionalReportFactoryV01.KIND,URI.create(scope.initial().endpoint()),Duration.ofMillis(scope.initial().timeoutMs()),scope::session,context.currentObservation(),a->{}),scope.executionConfig(),Clock.systemUTC());}
    @Override public String sessionId(){return id;}
    @Override public Map<String,Object> bootstrap(){scope.current();synchronized(state){if(closed)throw new SecurityException("REPORT_SESSION_CLOSED");}return Map.of("scenarioId",ReportScenarioExtensionV01.ID,"sessionId",id,"status","READY_NOT_EXECUTED","methodId",method.id(),"methodDigest",method.digest(),"formatInstructionDigest",ReportMapper.sha(ReportMethodAgent.FORMAT_INSTRUCTION.getBytes(java.nio.charset.StandardCharsets.UTF_8)),"runtime","openjiuwen-java@0.1.15","businessWrites",0);}
    @Override public Map<String,Object> status() throws Exception {
        scope.current();Map<String,Object> snapshot;int selected;synchronized(state){if(closed)throw new SecurityException("REPORT_SESSION_CLOSED");snapshot=latest;selected=generation;}
        if(snapshot==null)return bootstrap();if(snapshot.containsKey("reportId"))read(snapshot.get("reportId").toString(),((Number)snapshot.get("revision")).intValue());current(selected);return snapshot;
    }
    private CapabilityRequestV1 readRequest(ProfessionalReportV1 report){var l=scope.current();return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,scope.binding().capability(),CapabilityRequestV1.Operation.QUERY,
        new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,"report-read-"+UUID.randomUUID(),report.runId(),l.principalId(),FinanceContextV1.PrincipalType.EMPLOYEE,"managed-local-host","REPORT",l.subjectRef(),l.sessionRef(),FinanceContextV1.AuthenticationLevel.STRONG,l.scopes()),Map.of(),null,null,Map.of(),null);}
    private ProfessionalReportV1 read(String reportId,int revision) throws Exception {
        var report=reports.read(context.ownerRef(),reportId,revision);try(var p=provider()){for(String source:report.verifiedSources())p.checkStoredStatus(readRequest(report),source);}scope.current();return report;
    }
    @Override public Map<String,Object> call(String action,JsonNode body) throws Exception {
        ReportActions.validate(action,body);if(action.equals("cancel"))return cancel(false);if(action.equals("status"))return status();scope.current();int selected;synchronized(state){if(closed)throw new SecurityException("REPORT_SESSION_CLOSED");selected=generation;}
        if(action.equals("history")){var items=reports.history(context.ownerRef());for(var item:items)read(item.get("reportId").toString(),((Number)item.get("revision")).intValue());current(selected);return Map.of("items",items);}
        if(action.equals("versions")){var versions=reports.versions(context.ownerRef(),body.path("reportId").asText());for(int revision:versions)read(body.path("reportId").asText(),revision);current(selected);return Map.of("items",versions);}
        if(action.equals("result")&&!body.has("reportId"))return status();
        if(Set.of("result","note","artifact").contains(action)) {
            int revision=body.path("revision").asInt(1);var report=read(body.path("reportId").asText(),revision);current(selected);
            if(action.equals("note")){synchronized(state){current(selected);report=reports.note(context.ownerRef(),report.reportId(),revision,body.path("text").asText());}return Map.of("report",report.document());}
            if(action.equals("artifact")) {
                JsonNode artifact=null;for(JsonNode a:report.document().path("artifacts"))if(a.path("id").asText().equals(body.path("artifactId").asText()))artifact=a;
                if(artifact==null)throw new SecurityException("REPORT_ARTIFACT_NOT_FOUND");String prefix=artifact.path("sourceResultId").asText()+"_";var source=artifact.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)source).put("id",artifact.path("id").asText().substring(prefix.length()));
                try(var p=provider()){JsonNode value=p.artifact(readRequest(report),source.path("sourceResultId").asText(),source);current(selected);return Map.of("artifact",artifact,"contentBase64",value.path("contentBase64").asText());}
            }
            current(selected);return Map.of("report",report.document(),"lineage",reports.lineage(context.ownerRef(),report.reportId(),revision));
        }
        return generate(body);
    }
    private Map<String,Object> generate(JsonNode body) throws Exception {
        verifyFiles();scope.current();int selected;ReportVersions.Previous previous=null;
        if(body.has("previousReportId")){var old=read(body.path("previousReportId").asText(),body.path("previousRevision").asInt());previous=new ReportVersions.Previous(old.reportId(),old.revision(),ReportMapper.sha(ReportMapper.bytes(old.document())));}
        synchronized(state){if(closed||active!=null)throw new IllegalStateException("REPORT_ALREADY_RUNNING_OR_CLOSED");selected=++generation;active=Thread.currentThread();latest=Map.of("status","RUNNING","sessionId",id);}
        String execution="report-"+UUID.randomUUID();
        try(var p=provider()) {
            synchronized(state){current(selected);activeProvider=p;}
            var agent=new ReportMethodAgent(context,scope,p,method,()->current(selected),execution);var outcome=agent.run(body.path("query").asText());current(selected);
            var result=new LinkedHashMap<String,Object>();result.put("status",outcome.status());result.put("runId",execution);result.put("sessionId",id);result.put("response",outcome.response());result.put("metrics",outcome.metrics());result.put("businessWrites",0);
            synchronized(state){current(selected);if(outcome.report()!=null){var saved=reports.save(context.ownerRef(),outcome.report(),previous);result.put("reportId",saved.reportId());result.put("revision",saved.revision());result.put("report",saved.document());}latest=Collections.unmodifiableMap(result);return latest;}
        }catch(Exception failure){synchronized(state){if(generation!=selected&&latest!=null)return latest;latest=Map.of("status",Thread.currentThread().isInterrupted()?"CANCELLED":"FAILED","errorCode","REPORT_GENERATION_FAILED","runId",execution,"businessWrites",0);return latest;}}
        finally{synchronized(state){activeProvider=null;active=null;}Thread.interrupted();}
    }
    private Map<String,Object> cancel(boolean closing){ProfessionalReportProvider p;Thread t;synchronized(state){if(closed)return Map.of("status","CLOSED");generation++;p=activeProvider;t=active;if(closing)closed=true;latest=null;}
        boolean stopped=p==null||p.cancelAndClose();if(t!=null)t.interrupt();var result=Map.<String,Object>of("status",stopped?"CANCELLED":"FAILED","workerStopped",stopped,"disclosureClosed",true,"businessWrites",0);
        synchronized(state){latest=result;}return result;
    }
    @Override public void close(){cancel(true);scope.close();}
}
