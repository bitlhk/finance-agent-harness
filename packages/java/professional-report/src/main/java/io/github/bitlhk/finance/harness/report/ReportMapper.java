package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Strict opt-in domain mapping; Core, Execution and their JSON contracts remain unchanged. */
public final class ReportMapper {
    private static final ObjectMapper JSON=HarnessJson.mapper()
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Set<String> ROOT=Set.of("schemaVersion","reportId","runId","revision","scope","binding","status","contentState","reviewStatus","qualification","sections","evidence","artifacts","notes","errors","metrics");
    public static final Set<String> SCOPE=Set.of("ownerRef","customerRef","taskRef","authorityRef");
    public static final Set<String> BINDING=Set.of("methodId","methodVersion","methodDigest","sourceReleaseDigest","inputDigest","buildId","instanceId","methodKind","executionId");
    public static final Set<String> MEDIA=Set.of("text/markdown","text/csv","application/json","image/png","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static {
        // A whole-valued decimal such as 1.0 must remain a floating JSON value.
        // Stripping its scale writes "1" on a Map roundtrip, which the strict
        // original-value check correctly distinguishes from the source's 1.0.
        JSON.configure(com.fasterxml.jackson.databind.cfg.JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES,false);
        JSON.getFactory().setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1024*1024).build());
    }
    private ReportMapper(){}

    public record Expected(String reportId,String executionId,JsonNode scope,JsonNode binding) {
        public Expected {id(reportId);id(executionId);fields(scope,SCOPE);fields(binding,BINDING);scope=scope.deepCopy();binding=binding.deepCopy();}
        @Override public JsonNode scope(){return scope.deepCopy();}
        @Override public JsonNode binding(){return binding.deepCopy();}
    }
    public static ObjectMapper json(){return JSON.copy();}
    public static JsonNode parse(byte[] bytes) {
        if(bytes==null || bytes.length==0 || bytes.length>4*1024*1024)invalid();
        try{return JSON.readTree(bytes);}catch(Exception failure){throw new IllegalArgumentException("REPORT_JSON_INVALID");}
    }
    public static byte[] bytes(JsonNode value) {
        try{return JSON.writeValueAsBytes(value);}catch(Exception failure){throw new IllegalArgumentException("REPORT_JSON_INVALID");}
    }
    public static String sha(byte[] bytes) {
        try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception failure){throw new IllegalArgumentException("REPORT_DIGEST_INVALID");}
    }

    /** Validate exact original document bytes. Never rehash a Python float after Java serialization. */
    public static ProfessionalReportV1 mapSource(byte[] reportBytes,Expected expected,byte[] originalDocument) {
        JsonNode value=parse(reportBytes);validate(value);
        if(!value.path("reportId").asText().equals(expected.reportId()) || !value.path("runId").asText().equals(expected.executionId())
                || !value.path("scope").equals(expected.scope()) || !value.path("binding").equals(expected.binding()))invalid();
        String sourceHash=sha(originalDocument);JsonNode original=parse(originalDocument);
        Set<String> sources=new HashSet<>();
        boolean fileProof=false;
        for(JsonNode artifact:value.path("artifacts"))if(artifact.path("sha256").asText().equals(sourceHash)
                && artifact.path("mediaType").asText().equals("application/json"))fileProof=true;
        if(!fileProof)invalid();
        for(JsonNode e:value.path("evidence")) {
            if(!e.path("sourceResultId").asText().equals(expected.reportId()) || !e.path("sourceDigest").asText().equals(sourceHash))invalid();
            JsonNode target=original.at(e.path("pointer").asText());
            if(target.isMissingNode() || !target.equals(e.path("value")))invalid();
            sources.add(e.path("sourceResultId").asText());
        }
        return new ProfessionalReportV1(value,sources);
    }

    /** Assemble only already verified source groups; narrative quality is a separate qualification. */
    public static ProfessionalReportV1 compose(JsonNode document,List<ProfessionalReportV1> originals) {
        validate(document);Set<String> verified=new HashSet<>();Map<String,JsonNode> evidence=new HashMap<>();
        for(var original:originals) {
            if(!original.scope().equals(document.path("scope")) || !original.runId().equals(document.path("runId").asText()))invalid();
            verified.addAll(original.verifiedSources());
            for(JsonNode e:original.document().path("evidence"))evidence.put(e.path("sourceResultId").asText()+"\n"+e.path("pointer").asText(),e);
        }
        for(JsonNode e:document.path("evidence")) {
            var source=evidence.get(e.path("sourceResultId").asText()+"\n"+e.path("pointer").asText());
            if(source==null || !verified.contains(e.path("sourceResultId").asText()))invalid();
            for(String k:List.of("runId","sourceResultId","pointer","sourceDigest","sourceVersion","dataAsOf","value"))
                if(!source.path(k).equals(e.path(k)))invalid();
        }
        return new ProfessionalReportV1(document,verified);
    }

    /** Restore the Host's own digest-checked retained projection, not new external evidence. */
    static ProfessionalReportV1 retained(JsonNode document,Set<String> sourceProofs) {
        validate(document);
        for(JsonNode e:document.path("evidence"))if(!sourceProofs.contains(e.path("sourceResultId").asText()))invalid();
        return new ProfessionalReportV1(document,sourceProofs);
    }

    public static void validate(JsonNode n) {
        fields(n,ROOT);if(!ProfessionalReportV1.SCHEMA_VERSION.equals(n.path("schemaVersion").asText()))invalid();
        id(text(n,"reportId"));id(text(n,"runId"));integer(n.get("revision"),1,Integer.MAX_VALUE);fields(n.get("scope"),SCOPE);
        for(JsonNode v:n.path("scope"))id(v.asText());
        fields(n.get("binding"),BINDING);var b=n.path("binding");
        for(String k:List.of("methodId","buildId","instanceId","executionId"))id(text(b,k));
        if(!text(b,"methodVersion").matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?") || text(b,"methodVersion").length()>128)invalid();
        for(String k:List.of("methodDigest","sourceReleaseDigest","inputDigest"))hash(text(b,k));
        member(text(b,"methodKind"),Set.of("ORIGINAL_SKILL","DERIVED_PROCEDURE"));
        if(!n.path("runId").asText().equals(b.path("executionId").asText()))invalid();
        member(text(n,"status"),Set.of("RUNNING","SUCCEEDED","PARTIAL","FAILED","CANCELLED","TIMED_OUT"));
        member(text(n,"contentState"),Set.of("COMPLETE","PARTIAL","UNAVAILABLE"));member(text(n,"reviewStatus"),Set.of("UNREVIEWED","REVIEWED"));
        fields(n.get("qualification"),Set.of("engineering","originalEngine","professional","production"));
        for(JsonNode v:n.path("qualification"))member(v.asText(),Set.of("PASS","FAIL","NOT_RUN","BLOCKED"));
        array(n.get("sections"),128);array(n.get("evidence"),2048);array(n.get("artifacts"),64);array(n.get("notes"),128);array(n.get("errors"),64);
        String status=n.path("status").asText();
        if(Set.of("FAILED","CANCELLED","TIMED_OUT").contains(status) && (!n.path("contentState").asText().equals("UNAVAILABLE") || !n.path("sections").isEmpty() || !n.path("artifacts").isEmpty()))invalid();
        if(status.equals("PARTIAL") && !n.path("contentState").asText().equals("PARTIAL"))invalid();
        if(status.equals("SUCCEEDED") && (!n.path("contentState").asText().equals("COMPLETE") || n.path("sections").isEmpty() || n.path("evidence").isEmpty()))invalid();
        Map<String,JsonNode> evidence=new HashMap<>();Map<String,JsonNode> roots=new HashMap<>();
        for(JsonNode e:n.path("evidence")) {
            fields(e,Set.of("id","runId","sourceResultId","pointer","sourceDigest","sourceVersion","dataAsOf","value"));
            new ReportEvidenceV1(text(e,"id"),text(e,"runId"),text(e,"sourceResultId"),text(e,"pointer"),text(e,"sourceDigest"),nullable(e.get("sourceVersion")),nullable(e.get("dataAsOf")),e.get("value"));
            if(!e.path("runId").equals(n.path("runId")) || evidence.put(e.path("id").asText(),e)!=null)invalid();
            if(e.path("pointer").asText().isEmpty() && roots.put(e.path("sourceResultId").asText(),e)!=null)invalid();
        }
        for(JsonNode e:evidence.values()) {
            JsonNode root=roots.get(e.path("sourceResultId").asText());
            if(root==null || !root.path("sourceDigest").equals(e.path("sourceDigest")) || !root.path("value").at(e.path("pointer").asText()).equals(e.path("value")))invalid();
        }
        Set<String> artifacts=new HashSet<>();
        for(JsonNode a:n.path("artifacts")) {
            fields(a,Set.of("id","runId","sourceResultId","name","mediaType","bytes","sha256"));id(text(a,"id"));id(text(a,"sourceResultId"));hash(text(a,"sha256"));integer(a.get("bytes"),1,16*1024*1024);
            if(!a.path("runId").equals(n.path("runId")) || !roots.containsKey(text(a,"sourceResultId")) || !text(a,"name").matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}") || !artifacts.add(text(a,"id")))invalid();
            member(text(a,"mediaType"),MEDIA);
        }
        Set<String> sections=new HashSet<>();
        for(JsonNode s:n.path("sections")) {
            String kind=text(s,"kind");Set<String> allowed=switch(kind) {
                case "TEXT"->Set.of("id","title","kind","origin","text","evidenceRefs");
                case "TABLE"->Set.of("id","title","kind","origin","columns","rows","truncated","totalRows","evidenceRefs");
                case "IMAGE"->Set.of("id","title","kind","origin","artifactRef","evidenceRefs");
                default->throw new IllegalArgumentException("REPORT_SECTION_TYPE_REJECTED");};
            fields(s,allowed);id(text(s,"id"));if(!sections.add(text(s,"id")) || text(s,"title").length()>256)invalid();
            member(text(s,"origin"),kind.equals("TEXT")?Set.of("SOURCE","MODEL_EXPLANATION"):Set.of("SOURCE"));array(s.get("evidenceRefs"),128);
            Set<String> refs=new HashSet<>();for(JsonNode ref:s.path("evidenceRefs"))if(!ref.isTextual() || !evidence.containsKey(ref.asText()) || !refs.add(ref.asText()))invalid();
            if(status.equals("SUCCEEDED") && refs.isEmpty())invalid();
            if(kind.equals("TEXT") && text(s,"text").length()>1024*1024)invalid();
            if(kind.equals("IMAGE") && !artifacts.contains(text(s,"artifactRef")))invalid();
            if(kind.equals("TABLE")) {
                array(s.get("columns"),128);array(s.get("rows"),2000);for(JsonNode c:s.path("columns"))if(!c.isTextual() || c.asText().length()>256)invalid();
                for(JsonNode row:s.path("rows")){if(!row.isArray() || row.size()!=s.path("columns").size())invalid();for(JsonNode v:row)if(!v.isValueNode())invalid();}
                integer(s.get("totalRows"),s.path("rows").size(),Integer.MAX_VALUE);
                if(!s.path("truncated").isBoolean() || s.path("truncated").asBoolean()!=(s.path("totalRows").asInt()>s.path("rows").size()))invalid();
            }
        }
        for(JsonNode note:n.path("notes")) {fields(note,Set.of("id","text","actorRef","createdAt"));id(text(note,"id"));id(text(note,"actorRef"));if(text(note,"text").isBlank() || text(note,"text").length()>2000)invalid();try{java.time.Instant.parse(text(note,"createdAt"));}catch(Exception e){invalid();}}
        for(JsonNode error:n.path("errors")) {fields(error,Set.of("stage","code"));id(text(error,"stage"));id(text(error,"code"));}
        fields(n.get("metrics"),Set.of("elapsedMillis","toolCalls","modelCalls","inputTokens","outputTokens","cachedInputTokens"));
        for(String k:List.of("elapsedMillis","toolCalls","modelCalls"))integer(n.path("metrics").get(k),0,Long.MAX_VALUE);
        for(String k:List.of("inputTokens","outputTokens","cachedInputTokens"))if(!n.path("metrics").get(k).isNull())integer(n.path("metrics").get(k),0,Long.MAX_VALUE);
    }
    public static void fields(JsonNode n,Set<String> expected) {if(n==null || !n.isObject())invalid();Set<String> actual=new HashSet<>();n.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))invalid();}
    public static String text(JsonNode n,String key) {JsonNode v=n.get(key);if(v==null || !v.isTextual())invalid();return v.asText();}
    static String nullable(JsonNode n){if(n==null || !n.isNull()&&!n.isTextual())invalid();return n.isNull()?null:n.asText();}
    static void integer(JsonNode n,long minimum,long maximum){if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<minimum || n.asLong()>maximum)invalid();}
    static void array(JsonNode n,int maximum){if(n==null || !n.isArray() || n.size()>maximum)invalid();}
    static void member(String value,Set<String> admitted){if(!admitted.contains(value))invalid();}
    public static void id(String value){if(value==null || !value.matches("[A-Za-z0-9_-]{1,128}"))invalid();}
    public static void hash(String value){if(value==null || !value.matches("sha256:[a-f0-9]{64}"))invalid();}
    public static void invalid(){throw new IllegalArgumentException("REPORT_RESULT_INVALID");}
}
