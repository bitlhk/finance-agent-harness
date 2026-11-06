package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Immutable financial report projection. Execution success is not professional approval. */
public final class ProfessionalReportV1 {
    public static final String SCHEMA_VERSION="finance-professional-report.v1";
    private final JsonNode value;
    private final Set<String> verifiedSources;
    ProfessionalReportV1(JsonNode value,Set<String> verifiedSources) {
        this.value=value.deepCopy();this.verifiedSources=Set.copyOf(verifiedSources);
    }
    public JsonNode document(){return value.deepCopy();}
    public String reportId(){return value.path("reportId").asText();}
    public String runId(){return value.path("runId").asText();}
    public int revision(){return value.path("revision").asInt();}
    public String status(){return value.path("status").asText();}
    public JsonNode scope(){return value.path("scope").deepCopy();}
    public Set<String> verifiedSources(){return verifiedSources;}
}
