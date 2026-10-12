package io.github.bitlhk.finance.builder.local;

import java.util.*;
import java.util.function.UnaryOperator;

/** Structural diagnostic projection, not a replacement tool schema or a general DLP engine. */
final class SnapshotContentFilter {
    private enum Kind { REQUEST, MESSAGE, TOOL, FUNCTION, CALL, SCHEMA, PROPERTIES, LITERAL }
    private static final Map<Kind,Set<String>> FIELDS = Map.of(
        Kind.REQUEST, Set.of("model","messages","tools","temperature","top_p","max_tokens","max_completion_tokens",
                "stream","stop","frequency_penalty","presence_penalty","seed","tool_choice","parallel_tool_calls"),
        Kind.MESSAGE, Set.of("role","content","tool_calls","tool_call_id","name","reason"),
        Kind.TOOL, Set.of("type","function"),
        Kind.FUNCTION, Set.of("name","description","parameters","strict","arguments"),
        Kind.CALL, Set.of("id","index","type","function"),
        Kind.SCHEMA, Set.of("$schema","$id","$ref","$anchor","$defs","definitions","title","description","type",
                "properties","patternProperties","required","additionalProperties","items","prefixItems","enum","const",
                "format","pattern","minLength","maxLength","minimum","maximum","exclusiveMinimum","exclusiveMaximum",
                "multipleOf","minItems","maxItems","uniqueItems","minProperties","maxProperties","allOf","anyOf","oneOf",
                "not","if","then","else","contains","minContains","maxContains","propertyNames","dependentSchemas",
                "readOnly","writeOnly","deprecated","default"));
    private final UnaryOperator<String> clean;
    private final List<Map<String,Object>> changes = new ArrayList<>();
    private int changeCount, nodes;
    private boolean truncated;
    SnapshotContentFilter(UnaryOperator<String> clean) { this.clean=clean; }
    Object filter(Map<String,Object> value, boolean request) {
        return visit(value, request?Kind.REQUEST:Kind.MESSAGE,"",0);
    }
    List<Map<String,Object>> changes() { return List.copyOf(changes); }
    int omittedChanges() { return changeCount-changes.size(); }
    boolean truncated() { return truncated; }
    void note(String reason,String path,int original,int retained) {
        changeCount++;
        if(reason.endsWith("LIMIT"))truncated=true;
        if(changes.size()>=16)return;
        var detail=new LinkedHashMap<String,Object>();detail.put("reason",reason);
        String safe=clean.apply(path);
        detail.put("path",safe.length()>256?safe.substring(0,256)+"...":safe);
        if(safe.length()>256)detail.put("pathAbbreviated",true);
        if(original>=0){detail.put("originalCount",original);detail.put("retainedCount",retained);}
        changes.add(detail);
    }
    private String child(String path,String key) {
        String safe=clean.apply(key).replaceAll("[\\p{Cntrl}]","?");
        return path+"/"+safe.replace("~","~0").replace("/","~1");
    }
    private Object visit(Object value,Kind kind,String path,int depth) {
        if(depth>16){note("DEPTH_LIMIT",path,-1,0);return "[DEPTH_LIMIT]";}
        if(++nodes>4096){note("NODE_LIMIT",path,-1,0);return "[NODE_LIMIT]";}
        if(value instanceof String text){
            String safe=clean.apply(text);if(!safe.equals(text))note("REDACTED",path,-1,0);return safe;
        }
        if(value instanceof Collection<?> list){
            if(list.size()>128)note("COLLECTION_LIMIT",path,list.size(),128);
            var result=new ArrayList<Object>();int i=0;
            for(Object item:list){if(i==128)break;result.add(visit(item,kind,path+"/"+i,depth+1));i++;}
            return result;
        }
        if(value instanceof Map<?,?> map){
            // Arbitrary payload maps are not permitted. Dynamic keys are allowed only
            // in schema property/definition maps, whose values are recursively schemas.
            if(kind==Kind.LITERAL){note("UNSUPPORTED_OBJECT",path,-1,0);return "[NOT_CAPTURED]";}
            var result=new LinkedHashMap<String,Object>();int retained=0;
            for(var entry:map.entrySet()){
                if(retained==128)break;
                String key=String.valueOf(entry.getKey());String target=child(path,key);
                if(kind==Kind.PROPERTIES){
                    if(key.length()>128 || !clean.apply(key).equals(key) || key.chars().anyMatch(Character::isISOControl)){
                        note("PROPERTY_NAME_FILTERED",path,-1,0);continue;
                    }
                    result.put(key,visit(entry.getValue(),Kind.SCHEMA,target,depth+1));retained++;continue;
                }
                if(!FIELDS.getOrDefault(kind,Set.of()).contains(key)){
                    note("FIELD_NOT_ALLOWLISTED",target,-1,0);continue;
                }
                Kind nested=Kind.LITERAL;
                if(kind==Kind.REQUEST){
                    if(key.equals("messages"))nested=Kind.MESSAGE;
                    else if(key.equals("tools") || key.equals("tool_choice"))nested=Kind.TOOL;
                } else if(kind==Kind.MESSAGE && key.equals("tool_calls"))nested=Kind.CALL;
                else if((kind==Kind.TOOL || kind==Kind.CALL) && key.equals("function"))nested=Kind.FUNCTION;
                else if(kind==Kind.FUNCTION && key.equals("parameters"))nested=Kind.SCHEMA;
                else if(kind==Kind.SCHEMA){
                    if(Set.of("properties","patternProperties","$defs","definitions","dependentSchemas").contains(key))nested=Kind.PROPERTIES;
                    else if(Set.of("additionalProperties","items","prefixItems","allOf","anyOf","oneOf","not","if","then","else","contains","propertyNames").contains(key))nested=Kind.SCHEMA;
                }
                result.put(key,visit(entry.getValue(),nested,target,depth+1));retained++;
            }
            if(retained==128 && map.size()>retained)note("PROPERTY_LIMIT",path,map.size(),retained);
            return result;
        }
        if(value==null || value instanceof Number || value instanceof Boolean)return value;
        note("UNSUPPORTED_VALUE",path,-1,0);return "[NOT_CAPTURED]";
    }
}
