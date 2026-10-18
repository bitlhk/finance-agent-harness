package io.github.bitlhk.finance.harness.integrations.fraud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.observation.ObservedFinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.providers.fraud.FraudMiningProvider;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Thin managed-host adapter. Identity, mapping and credentials are server-owned. */
public final class FraudVerificationScenarioExtension implements ManagedScenarioExtensionV01 {
    public static final String ID="fraud-risk-verification-assistance.v1";
    private static final Set<String>ACTIONS=Set.of("select","verify","latest","read","review","versions","read-version","replay");
    @Override public String id(){return ID;}@Override public Set<String>actions(){return ACTIONS;}
    @Override public Session open(Context context)throws Exception{
        Path mapping=outside(Path.of(env("FH_FRAUD_SUBJECT_MAPPING")).toAbsolutePath().normalize(),context.artifactRoot());
        Path records=outside(context.privateStateRoot().resolve("fraud-risk-verification-reports"),context.artifactRoot());
        String owner=required(context.ownerRef(),"OWNER_REQUIRED");
        var mappings=(java.util.function.Supplier<FraudSubjectMapping>)()->{try{return FraudSubjectMapping.load(mapping,owner);}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}};
        FraudMiningProvider source=new FraudMiningProvider(new FraudMiningProvider.Config(URI.create(env("FH_FRAUD_URL")),
                Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("FH_FRAUD_TIMEOUT_MILLIS","8000"))),
                Integer.parseInt(System.getenv().getOrDefault("FH_FRAUD_PAGE_SIZE","100")),
                Integer.parseInt(System.getenv().getOrDefault("FH_FRAUD_MAX_PAGES","10")),
                Integer.parseInt(System.getenv().getOrDefault("FH_FRAUD_MAX_RECORDS","1000"))));
        var provider=new ObservedFinanceProvider(source,FraudMiningProvider.PROVIDER_ID,context.currentObservation());
        InstalledPreparationMethod method=installed(context.artifactRoot());var model=Objects.requireNonNull(context.model());
        var task=new FraudVerificationTask(owner,provider,mappings,new OpenJiuwenFraudReportGenerator(model.clientProvider(),model.apiKey(),model.apiBase(),model.modelName()),method,records,
                Map.of("capability","fraud-risk-evidence-query@0.1.0","provider","fraud-mining-reference@0.1.0-SNAPSHOT","runtime","openjiuwen-java@0.1.15"),
                io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence.host(context));
        return new Managed(owner,UUID.randomUUID().toString(),task,method);
    }
    private static final class Managed implements Session{
        final String owner,id;final FraudVerificationTask task;final InstalledPreparationMethod method;boolean closed;
        Managed(String owner,String id,FraudVerificationTask task,InstalledPreparationMethod method){this.owner=owner;this.id=id;this.task=task;this.method=method;}
        @Override public String sessionId(){return id;}
        @Override public Map<String,Object> bootstrap(){current();return Map.of("scenarioId",ID,"subjects",task.subjects(owner),"steps",List.of("SUBJECT_AND_PURPOSE","RISK_FACTS_AND_EVIDENCE","VERIFICATION_REPORT","HUMAN_REVIEW"),"method",Map.of("id",method.id(),"version",method.version(),"digest",method.digest(),"lockDigest",method.lockDigest()));}
        @Override public synchronized Map<String,Object> call(String action,JsonNode body)throws Exception{
            current();if(!ACTIONS.contains(action)||body==null||!body.isObject())throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            return switch(action){
                case "select"->{exact(body,"subjectAlias","purpose");yield Map.of("status","SELECTED","generation",task.select(owner,text(body,"subjectAlias"),text(body,"purpose")));}
                case "verify"->{exact(body,"generation");yield value(task.verify(owner,integer(body,"generation"),FraudVerificationTask.Listener.NONE));}
                case "latest"->{exact(body);var latest=task.latest(owner);var result=new LinkedHashMap<String,Object>();result.put("found",latest.isPresent());latest.ifPresent(v->result.put("report",value(v)));yield Map.copyOf(result);}
                case "read"->{exact(body,"reportId");yield value(task.read(owner,text(body,"reportId")));}
                case "review"->{exact(body,"reportId","revision","opinion");yield value(task.review(owner,text(body,"reportId"),integer(body,"revision"),text(body,"opinion")));}
                case "versions"->{exact(body,"reportId");yield Map.of("reportId",text(body,"reportId"),"versions",task.versions(owner,text(body,"reportId")));}
                case "read-version"->{exact(body,"reportId","revision");yield value(task.readVersion(owner,text(body,"reportId"),integer(body,"revision")));}
                case "replay"->{exactOne(body,Set.of("reportId"),Set.of("reportId","revision"));yield value(task.replay(owner,text(body,"reportId"),body.has("revision")?integer(body,"revision"):null));}
                default->throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");};
        }
        @Override public Map<String,Object> status(){var out=new LinkedHashMap<String,Object>(bootstrap());out.put("status","ACTIVE");return Map.copyOf(out);}
        @Override public void close(){if(!closed){closed=true;task.close();}}private void current(){if(closed)throw new SecurityException("SCENARIO_SESSION_CLOSED");}
    }
    private static InstalledPreparationMethod installed(Path root)throws Exception{
        JsonNode lock=HarnessJson.mapper().readTree(Files.readAllBytes(root.resolve("service-lock.json")));String id="fraud-risk-verification",version="0.1.0";
        String relative="assets/domain-packs/risk/fraud-verification-assistance/skills/fraud-risk-verification/SKILL.md";
        if(lock.has("method")){id=lock.path("method").path("assetId").asText();version=lock.path("method").path("version").asText();relative="assets/private-methods/"+id+"/"+version+"/skills/"+id+"/SKILL.md";}
        String digest=lock.path("files").path(relative).asText();if(digest.isBlank())throw new SecurityException("INSTALLED_METHOD_NOT_LOCKED");
        return InstalledPreparationMethod.load(id,version,root.resolve(relative),"sha256:"+digest,"sha256:"+env("FH_EXPECTED_LOCK"));
    }
    private static Map<String,Object> value(JsonNode node){return HarnessJson.mapper().convertValue(node,new TypeReference<>(){});}private static Path outside(Path p,Path root){if(p.startsWith(root.toAbsolutePath().normalize()))throw new SecurityException("PRIVATE_STATE_OUTSIDE_ARTIFACT_REQUIRED");return p;}
    private static String env(String n){return required(System.getenv(n),n+"_REQUIRED");}private static String required(String v,String c){if(v==null||v.isBlank())throw new IllegalArgumentException(c);return v.trim();}
    private static String text(JsonNode b,String n){return required(b.path(n).asText(null),"SCENARIO_FIELD_REQUIRED");}private static int integer(JsonNode b,String n){if(!b.path(n).canConvertToInt())throw new IllegalArgumentException("SCENARIO_FIELD_INVALID");return b.path(n).asInt();}
    private static void exact(JsonNode b,String...n){Set<String>a=new HashSet<>();b.fieldNames().forEachRemaining(a::add);if(!a.equals(Set.of(n)))throw new IllegalArgumentException("SCENARIO_BODY_INVALID");}
    @SafeVarargs private static void exactOne(JsonNode b,Set<String>... choices){Set<String>a=new HashSet<>();b.fieldNames().forEachRemaining(a::add);for(Set<String>c:choices)if(a.equals(c))return;throw new IllegalArgumentException("SCENARIO_BODY_INVALID");}
}
