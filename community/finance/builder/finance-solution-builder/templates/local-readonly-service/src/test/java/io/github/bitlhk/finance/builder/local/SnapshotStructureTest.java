package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class SnapshotStructureTest {
    @Test public void dynamicToolSchemaFieldsAndRequiredRemainConsistent() throws Exception {
        var properties = new LinkedHashMap<String,Object>();
        properties.put("period", Map.of("type","string","pattern","^[0-9]{4}-[0-9]{2}$"));
        properties.put("accountId", Map.of("type","string","minLength",1));
        properties.put("selector", Map.of("type","object","properties",Map.of("accountId",Map.of("type","string")),
                "required",List.of("accountId"),"additionalProperties",false));
        properties.put("reference",Map.of("$ref","#/$defs/account"));
        var schema = Map.of("type","object","properties",properties,"required",List.of("period"),"additionalProperties",false,
                "$defs",Map.of("account",Map.of("type","object","properties",Map.of("accountId",Map.of("type","string")))));
        var body = capture(Map.of("tools",List.of(Map.of("type","function","function",Map.of("name","finance_transaction_summary","parameters",schema))),
                "messages",List.of(Map.of("role","user","content","synthetic")),"api_key","PRIVATE","reasoning_content","HIDDEN"));
        var filtered = HarnessJson.mapper().readTree(body.path("body").asText());
        assertEquals(HarnessJson.mapper().valueToTree(schema),filtered.path("tools").get(0).path("function").path("parameters"));
        assertFalse(body.toString().contains("PRIVATE"));assertFalse(body.toString().contains("HIDDEN"));
        assertTrue(body.path("changes").size()>0);
    }
    @Test public void collectionPropertyDepthAndByteLimitsAreReported() throws Exception {
        var messages = new ArrayList<Object>();
        for(int i=0;i<129;i++)messages.add(Map.of("role","user","content","synthetic"));
        var array=capture(Map.of("messages",messages));
        assertEquals("TRUNCATED",array.path("state").asText());
        assertTrue(array.path("changes").toString().contains("129"));
        assertTrue(array.path("changes").toString().contains("128"));
        var properties=new LinkedHashMap<String,Object>();for(int i=0;i<129;i++)properties.put("field"+i,Map.of("type","string"));
        var props=capture(tool(Map.of("type","object","properties",properties)));
        assertEquals("TRUNCATED",props.path("state").asText());
        assertTrue(props.path("changes").toString().contains("properties"));
        var values=new ArrayList<Integer>();for(int i=0;i<129;i++)values.add(i);
        assertEquals("TRUNCATED",capture(tool(Map.of("enum",values))).path("state").asText());
        Map<String,Object> nested=Map.of("type","string");
        for(int i=0;i<20;i++)nested=Map.of("type","array","items",nested);
        var deep=capture(tool(nested));assertEquals("TRUNCATED",deep.path("state").asText());
        assertTrue(deep.path("changes").toString().contains("DEPTH_LIMIT"));
        var bytes=capture(Map.of("messages",List.of(Map.of("role","user","content","x".repeat(60_000)))));
        assertEquals("TRUNCATED",bytes.path("state").asText());
        assertTrue(bytes.path("changes").toString().contains("BYTE_LIMIT"));
    }
    @Test public void redactionAndBoundedChangeDetailsAreExplicit() throws Exception {
        var messages=new ArrayList<Object>();
        for(int i=0;i<20;i++)messages.add(Map.of("role","user","content","password=synthetic-private"));
        var body=capture(Map.of("messages",messages));
        assertEquals("FILTERED",body.path("state").asText());
        assertEquals(16,body.path("changes").size());assertEquals(4,body.path("changesOmitted").asInt());
        assertEquals("REDACTED",body.path("changes").get(0).path("reason").asText());
        assertFalse(body.toString().contains("synthetic-private"));
        var unlisted=new LinkedHashMap<String,Object>();
        for(int i=0;i<129;i++)unlisted.put("unlisted"+i,"omitted");
        var filtered=capture(unlisted);
        assertEquals("FILTERED",filtered.path("state").asText());
        assertEquals("{}",filtered.path("body").asText());
        assertFalse(filtered.path("changes").toString().contains("PROPERTY_LIMIT"));
    }
    private Map<String,Object> tool(Map<String,Object> schema) {
        return Map.of("tools",List.of(Map.of("type","function","function",Map.of("name","synthetic","parameters",schema))));
    }
    private com.fasterxml.jackson.databind.JsonNode capture(Map<String,Object> value) throws Exception {
        Path root=Files.createTempDirectory("snapshot-structure");
        try {
            Path candidate=Files.createDirectory(root.resolve("candidate"));Files.writeString(candidate.resolve("service-lock.json"),"{}");
            var archive=new ExecutionArchive(candidate,"build","instance");var request=archive.begin(candidate,"session","subject",0);
            new ModelSnapshots(request,()->"span",List.of(),true,Runnable::run)
                    .capture("call","CLIENT_REQUEST_PREPARED","CAPTURED",value);
            return HarnessJson.mapper().readTree(request.snapshotDirectory().resolve(request.id+".call.request.json").toFile());
        } finally { try(var files=Files.walk(root)){for(Path f:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(f);} }
    }
}
