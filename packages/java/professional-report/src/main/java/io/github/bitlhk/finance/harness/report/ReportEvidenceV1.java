package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;

/** A JSON pointer into retained original source bytes, not into a reserialized display. */
public record ReportEvidenceV1(String id,String runId,String sourceResultId,String pointer,
        String sourceDigest,String sourceVersion,String dataAsOf,JsonNode value) {
    public ReportEvidenceV1 {
        ReportMapper.id(id);ReportMapper.id(runId);ReportMapper.id(sourceResultId);ReportMapper.hash(sourceDigest);
        if(pointer==null || pointer.length()>2048 || (!pointer.isEmpty() && !pointer.startsWith("/"))
                || pointer.matches(".*~(?:[^01]|$).*"))ReportMapper.invalid();
        if(sourceVersion!=null && sourceVersion.length()>128 || dataAsOf!=null && dataAsOf.length()>128 || value==null)ReportMapper.invalid();
        value=value.deepCopy();
    }
    @Override public JsonNode value(){return value.deepCopy();}
}
