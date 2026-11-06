package io.github.bitlhk.finance.harness.integrations.report;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Finite UI actions. No customer, authority, file path or tool endpoint is accepted. */
public final class ReportActions {
    public static final Set<String> ACTIONS=Set.of("chat","generate","status","cancel","history","result","versions","note","artifact");
    private ReportActions(){}
    public static void validate(String action,JsonNode body) {
        if(!ACTIONS.contains(action)||body==null||!body.isObject())throw new IllegalArgumentException("REPORT_ACTION_INVALID");
        Set<String> allowed=switch(action){case "chat","generate"->Set.of("query","previousReportId","previousRevision");case "result","versions"->Set.of("reportId","revision");case "note"->Set.of("reportId","revision","text");case "artifact"->Set.of("reportId","revision","artifactId");default->Set.of();};
        body.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("REPORT_ACTION_FIELDS_REJECTED");});
        for(String key:List.of("reportId","artifactId","previousReportId"))if(body.has(key)&&(!body.path(key).isTextual()||!body.path(key).asText().matches("[A-Za-z0-9_-]{1,128}")))throw new IllegalArgumentException("REPORT_ACTION_ID_INVALID");
        for(String key:List.of("revision","previousRevision"))if(body.has(key)&&(!body.path(key).isIntegralNumber()||!body.path(key).canConvertToInt()||body.path(key).asInt()<1||body.path(key).asInt()>32))throw new IllegalArgumentException("REPORT_REVISION_INVALID");
        if(Set.of("chat","generate").contains(action)&&(!body.path("query").isTextual()||body.path("query").asText().isBlank()||body.path("query").asText().length()>8000||body.has("previousReportId")!=body.has("previousRevision")))throw new IllegalArgumentException("REPORT_QUERY_INVALID");
        if(Set.of("versions","note","artifact").contains(action)&&!body.has("reportId"))throw new IllegalArgumentException("REPORT_ID_REQUIRED");
        if(Set.of("note","artifact").contains(action)&&!body.has("revision"))throw new IllegalArgumentException("REPORT_REVISION_REQUIRED");
        if(action.equals("note")&&(!body.path("text").isTextual()||body.path("text").asText().isBlank()||body.path("text").asText().length()>2000))throw new IllegalArgumentException("REPORT_NOTE_INVALID");
        if(action.equals("artifact")&&!body.has("artifactId"))throw new IllegalArgumentException("REPORT_ARTIFACT_REQUIRED");
    }
}
