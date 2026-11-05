package io.github.bitlhk.finance.harness.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import static io.github.bitlhk.finance.harness.calculation.CalculationResultV1.*;

/** Validate host-owned identities and preserve typed original values, without rounding. */
public final class CalculationResultMapper {
    private CalculationResultMapper(){}
    public record Expected(String runId, String assetId, String operationId, String sourceCommit,
            String sourceEntity, String filesDigest, String adapterDigest, String lockDigest,
            String inputDigest, String parameterDigest) {
        public Expected {
            for(String s:List.of(runId,assetId,operationId,sourceEntity))text(s);
            if(sourceCommit==null || !sourceCommit.matches("[a-f0-9]{40}"))invalid();
            for(String s:List.of(filesDigest,adapterDigest,lockDigest,inputDigest,parameterDigest))hash(s);
        }
    }
    public static CalculationResultV1 map(JsonNode node,Expected expected){
        try{
            keys(node,Set.of("schemaVersion","runId","generation","status","assetId","operationId","inputDigest","parameterDigest","source","tool","calculatedAt","dataAsOf","rawValue","rawDigest","projection","timings"));
            equal(str(node,"schemaVersion"),SCHEMA_VERSION);equal(str(node,"status"),"SUCCEEDED");
            equal(str(node,"runId"),expected.runId());equal(str(node,"assetId"),expected.assetId());equal(str(node,"operationId"),expected.operationId());
            equal(str(node,"inputDigest"),expected.inputDigest());equal(str(node,"parameterDigest"),expected.parameterDigest());
            JsonNode source=node.get("source"),tool=node.get("tool");
            keys(source,Set.of("repository","commit","entity","filesDigest"));
            equal(str(source,"repository"),"https://gitcode.com/openJiuwen/agent-store");equal(str(source,"commit"),expected.sourceCommit());
            equal(str(source,"entity"),expected.sourceEntity());equal(str(source,"filesDigest"),expected.filesDigest());
            keys(tool,Set.of("version","adapterDigest","lockDigest"));
            equal(str(tool,"version"),"0.1.0");equal(str(tool,"adapterDigest"),expected.adapterDigest());equal(str(tool,"lockDigest"),expected.lockDigest());
            JsonNode raw=node.get("rawValue");validateRaw(raw,0);
            equal(str(node,"rawDigest"),digest(raw));
            JsonNode projection=node.get("projection");keys(projection,Set.of("metrics","tables","series","warnings"));
            for(String field:List.of("metrics","tables","series","warnings"))if(!projection.get(field).isArray())invalid();
            JsonNode timings=node.get("timings");keys(timings,Set.of("elapsedMs","calls"));
            if(!timings.get("elapsedMs").isNumber() || !Double.isFinite(timings.get("elapsedMs").doubleValue()) || timings.get("elapsedMs").doubleValue()<0
                    || !timings.get("calls").isIntegralNumber() || timings.get("calls").intValue()<1)invalid();
            if(!node.get("generation").isIntegralNumber() || node.get("generation").intValue()!=1)invalid();
            JsonNode date=node.get("dataAsOf");if(!date.isNull() && !date.isTextual())invalid();
            return new CalculationResultV1(SCHEMA_VERSION,expected.runId(),1,expected.assetId(),expected.operationId(),expected.inputDigest(),expected.parameterDigest(),
                source,tool,OffsetDateTime.parse(str(node,"calculatedAt")).toInstant(),date.isNull()?null:date.textValue(),raw,str(node,"rawDigest"),projection,timings);
        }catch(RuntimeException invalid){throw new IllegalArgumentException("ORIGINAL_CALCULATION_RESULT_INVALID");}
    }
    public static String digest(JsonNode value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(HarnessJson.mapper().writeValueAsBytes(sorted(value))));}
        catch(Exception error){throw new IllegalArgumentException("CALCULATION_DIGEST_FAILED");}
    }
    private static JsonNode sorted(JsonNode n){
        if(n.isObject()){
            ObjectNode out=JsonNodeFactory.instance.objectNode();List<String> names=new ArrayList<>();n.fieldNames().forEachRemaining(names::add);Collections.sort(names);
            names.forEach(k->out.set(k,sorted(n.get(k))));return out;
        }
        if(n.isArray()){ArrayNode out=JsonNodeFactory.instance.arrayNode();n.forEach(v->out.add(sorted(v)));return out;}
        return n;
    }
    public static void validateRaw(JsonNode n,int depth){
        if(depth>40 || n==null || !n.isObject())invalid();
        String kind=str(n,"kind");
        switch(kind){
            case "float" -> {
                keys(n,Set.of("kind","value","hex"));double v=Double.parseDouble(str(n,"value")),hex=Double.parseDouble(str(n,"hex"));
                if(!Double.isFinite(v) || Double.doubleToRawLongBits(v)!=Double.doubleToRawLongBits(hex))invalid();
            }
            case "integer" -> {keys(n,Set.of("kind","value"));if(!str(n,"value").matches("-?[0-9]+"))invalid();}
            case "boolean" -> {keys(n,Set.of("kind","value"));if(!n.get("value").isBoolean())invalid();}
            case "string" -> {keys(n,Set.of("kind","value"));if(!n.get("value").isTextual() || n.get("value").textValue().length()>1000000)invalid();}
            case "datetime" -> {keys(n,Set.of("kind","value","pythonType"));str(n,"value");str(n,"pythonType");}
            case "missing" -> {keys(n,Set.of("kind","marker"));if(!Set.of("None","NA","NaT","nan","inf","-inf").contains(str(n,"marker")))invalid();}
            case "list","tuple" -> {keys(n,Set.of("kind","items"));array(n.get("items"));for(JsonNode x:n.get("items"))validateRaw(x,depth+1);}
            case "mapping" -> {
                keys(n,Set.of("kind","entries"));array(n.get("entries"));
                for(JsonNode e:n.get("entries")){keys(e,Set.of("key","value"));validateRaw(e.get("key"),depth+1);validateRaw(e.get("value"),depth+1);}
            }
            case "record" -> {
                keys(n,Set.of("kind","pythonType","fields"));str(n,"pythonType");array(n.get("fields"));Set<String> names=new HashSet<>();
                for(JsonNode e:n.get("fields")){keys(e,Set.of("name","value"));if(!names.add(str(e,"name")))invalid();validateRaw(e.get("value"),depth+1);}
            }
            case "dataframe" -> {
                keys(n,Set.of("kind","columns","index","dtypes","data"));
                for(String k:List.of("columns","index","dtypes","data"))array(n.get(k));
                if(n.get("columns").size()!=n.get("dtypes").size() || n.get("index").size()!=n.get("data").size())invalid();
                for(JsonNode x:n.get("columns"))validateRaw(x,depth+1);for(JsonNode x:n.get("index"))validateRaw(x,depth+1);
                for(JsonNode x:n.get("dtypes"))if(!x.isTextual())invalid();
                for(JsonNode row:n.get("data")){array(row);if(row.size()!=n.get("columns").size())invalid();for(JsonNode x:row)validateRaw(x,depth+1);}
            }
            case "series" -> {
                keys(n,Set.of("kind","name","index","dtype","data"));array(n.get("index"));array(n.get("data"));str(n,"dtype");validateRaw(n.get("name"),depth+1);
                if(n.get("index").size()!=n.get("data").size())invalid();
                for(JsonNode x:n.get("index"))validateRaw(x,depth+1);for(JsonNode x:n.get("data"))validateRaw(x,depth+1);
            }
            default -> invalid();
        }
    }
    private static void array(JsonNode n){if(n==null || !n.isArray() || n.size()>10000)invalid();}
    private static void keys(JsonNode n,Set<String> expected){
        if(n==null || !n.isObject())invalid();Set<String> found=new HashSet<>();n.fieldNames().forEachRemaining(found::add);if(!found.equals(expected))invalid();
    }
    private static String str(JsonNode n,String k){JsonNode v=n.get(k);if(v==null || !v.isTextual())invalid();String s=v.textValue();text(s);return s;}
    private static void equal(String actual,String expected){if(!Objects.equals(actual,expected))invalid();}
}
