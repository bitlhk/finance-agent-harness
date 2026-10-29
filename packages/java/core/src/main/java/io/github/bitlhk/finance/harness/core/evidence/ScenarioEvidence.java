package io.github.bitlhk.finance.harness.core.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Necessary evidence in the existing private report store, not a grant or telemetry sink. */
public final class ScenarioEvidence {
    public static final class Unavailable extends RuntimeException {
        private final String stage;
        private final String providerDispatch;
        public Unavailable() { this("BINDING", "NOT_INVOKED"); }
        public Unavailable(String stage,String providerDispatch) {
            super("SCENARIO_EVIDENCE_UNAVAILABLE");this.stage=stage;this.providerDispatch=providerDispatch;
        }
        public String stage(){return stage;}
        public String providerDispatch(){return providerDispatch;}
    }
    private final Path records;
    private final Supplier<Map<String,String>> host;
    public ScenarioEvidence(Path records, Supplier<Map<String,String>> host) {
        this.records=records.toAbsolutePath().normalize(); this.host=Objects.requireNonNull(host);
    }
    public static Supplier<Map<String,String>> host(ManagedScenarioExtensionV01.Context context) {
        return () -> {
            var observed=context.currentObservation().get();
            try {
                return Map.of("hostRequestId",observed==null?"NOT_OBSERVED":observed.requestId(),"buildId",context.buildId(),
                        "instanceId",context.instanceId(),"ownerRef",context.ownerRef(),
                        "serviceLockDigest",hash(Files.readAllBytes(context.artifactRoot().resolve("service-lock.json"))));
            } catch(java.io.IOException failure) { throw new Unavailable(); }
        };
    }
    public Run begin(String requestId,String owner,String subject,String purpose,String capability,
                     Object parameters,Object authority,String authorityBasis) {
        var fields=HarnessJson.mapper().createObjectNode();
        fields.put("schemaVersion","scenario-execution.v1");fields.put("requestId",requestId);
        fields.put("ownerDigest",digest(owner));fields.put("subjectDigest",digest(subject));
        fields.put("purposeDigest",digest(purpose));fields.put("capabilityId",capability);
        fields.put("parametersDigest",digest(parameters));fields.put("authorityDigest",digest(authority));
        fields.put("authorizationBasis",authorityBasis);fields.put("authorityExpiry","NOT_OBSERVED");
        Map<String,String> trusted=Map.copyOf(host.get());
        if(!trusted.isEmpty()) {
            if(!owner.equals(trusted.get("ownerRef")))throw new SecurityException("SCENARIO_HOST_OWNER_MISMATCH");
            for(String key:List.of("hostRequestId","buildId","instanceId","serviceLockDigest")) {
                String value=trusted.get(key);
                if(value==null||value.isBlank())throw new SecurityException("SCENARIO_HOST_BINDING_REQUIRED");
                fields.put(key,value);
            }
            if("NOT_OBSERVED".equals(trusted.get("hostRequestId"))&&!"scenario.report.read".equals(capability))
                throw new SecurityException("SCENARIO_HOST_REQUEST_REQUIRED");
            fields.put("hostBindingStatus","TRUSTED_HOST");
            fields.put("requestCorrelationStatus","NOT_OBSERVED".equals(trusted.get("hostRequestId"))?"NOT_OBSERVED":"HOST_REQUEST");
        } else fields.put("hostBindingStatus","LEGACY_LOCAL_CALLER");
        fields.put("bindingDigest",digest(fields));
        return new Run(fields);
    }
    public void releaseSaved(String owner,String subject,String reportId,int revision,
                             Object authority,String basis,Runnable authorize,Object report) {
        Run read=begin(UUID.randomUUID().toString(),owner,subject,"SAVED_REPORT_ACCESS",
                "scenario.report.read",Map.of("reportId",reportId,"revision",revision),authority,basis);
        read.check("HISTORY_RELEASE",authorize,report);
    }
    public final class Run {
        private final ObjectNode binding;
        private final String id=UUID.randomUUID().toString();
        private int sequence;
        private String dispatch="NOT_INVOKED";
        private Run(ObjectNode binding){this.binding=binding.deepCopy();}
        public ObjectNode binding(){return binding.deepCopy();}
        public void invoked(){dispatch="INVOKED_SEND_UNOBSERVED";}
        public void returned(){dispatch="RETURNED";}
        public synchronized void check(String stage,Runnable authorize,Object value) {
            try { authorize.run(); }
            catch(SecurityException denied) { record(stage,"DENY",null);throw denied; }
            record(stage,"ALLOW",value);
        }
        private void record(String stage,String decision,Object value) {
            ObjectNode event=binding.deepCopy();
            event.put("stage",stage);event.put("decision",decision);event.put("providerDispatch",dispatch);
            event.put("recordedAt",Instant.now().toString());event.put("sequence",++sequence);
            if(value!=null)event.put("resultDigest",digest(value));
            event.put("releaseDecision",decision.equals("ALLOW")&&stage.endsWith("RELEASE")?"ALLOW":"NOT_ALLOWED");
            Path directory=records.resolve("decision-evidence");
            try {
                if(Files.isSymbolicLink(records)||Files.isSymbolicLink(directory))throw new Unavailable();
                Files.createDirectories(directory);
                byte[] bytes=HarnessJson.mapper().writeValueAsBytes(event);
                try(var channel=FileChannel.open(directory.resolve(id+"-"+sequence+".json"),
                        StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
                try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
            } catch(Exception failure){throw new Unavailable(stage,dispatch);}
        }
    }
    public static String digest(Object value) {
        try{return hash(HarnessJson.mapper().writeValueAsBytes(canonical(HarnessJson.mapper().valueToTree(value))));}
        catch(Exception failure){throw new IllegalArgumentException("SCENARIO_EVIDENCE_ENCODING_INVALID");}
    }
    private static JsonNode canonical(JsonNode value) {
        if(value.isObject()) {
            ObjectNode out=HarnessJson.mapper().createObjectNode();var keys=new TreeSet<String>();value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(k->out.set(k,canonical(value.get(k))));return out;
        }
        if(value.isArray()){var out=HarnessJson.mapper().createArrayNode();value.forEach(v->out.add(canonical(v)));return out;}
        if(value.isNumber())return com.fasterxml.jackson.databind.node.DecimalNode.valueOf(value.decimalValue().stripTrailingZeros());
        return value.deepCopy();
    }
    private static String hash(byte[] bytes) {
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception failure){throw new IllegalStateException("DIGEST_UNAVAILABLE");}
    }
}
