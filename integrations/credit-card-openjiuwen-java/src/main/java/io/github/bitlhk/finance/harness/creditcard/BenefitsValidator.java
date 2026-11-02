package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;

/** Eligibility and provenance come from the verified snapshot, never model-generated business facts. */
public final class BenefitsValidator {
    private BenefitsValidator() {}
    public static ObjectNode context(ObjectNode view,String goal,Set<String> excluded,int topK) {
        if(topK<1||topK>5)throw new IllegalArgumentException("INVALID_TOP_K");
        boolean visible=false; for(var source:view.path("case").path("visible_sources"))
            if("benefits".equals(source.asText()))visible=true;
        if(!visible)throw new SecurityException("BENEFITS_NOT_VISIBLE");
        Instant now=Instant.parse(view.path("case").path("observed_at").asText());
        String digest=CreditContracts.hash(view);
        ObjectNode result=CreditContracts.JSON.createObjectNode();
        result.put("customer_id",view.path("customer").path("customer_id").asText())
                .put("case_id",view.path("case").path("case_id").asText())
                .put("observed_at",view.path("case").path("observed_at").asText())
                .put("dataset_version",view.path("dataset_version").asText())
                .put("snapshot_digest",digest).put("tier",view.path("customer").path("tier").intValue())
                .put("goal",goal).put("top_k",topK);
        var candidates=result.putArray("candidates");var rejections=result.putArray("exclusions");
        var seen=new HashSet<String>();
        for(var benefit:view.path("benefits")) {
            String id=CreditContracts.id(benefit.path("benefit_id").asText());
            if(!seen.add(id))throw new IllegalArgumentException("DUPLICATE_BENEFIT_ID");
            String code=excluded.contains(id)?"manager-excluded":
                !Instant.parse(benefit.path("known_at").asText()).isAfter(now)
                &&!Instant.parse(benefit.path("valid_from").asText()).isAfter(now)
                &&Instant.parse(benefit.path("valid_until").asText()).isAfter(now)?"":"benefit-not-current";
            if(code.isEmpty() && view.path("customer").path("tier").intValue()<benefit.path("minimum_tier").intValue())code="benefit-ineligible";
            if(code.isEmpty() && benefit.path("remaining").longValue()<=0)code="benefit-exhausted";
            if(!code.isEmpty()){rejections.addObject().put("benefit_id",id).put("code",code);continue;}
            var candidate=candidates.addObject();candidate.set("benefit",benefit.deepCopy());
            candidate.putObject("reference").put("ref_id","benefits:"+id)
                    .put("customer_id",result.path("customer_id").asText()).put("source_id","benefits")
                    .put("record_id",id).put("version",result.path("dataset_version").asText())
                    .put("snapshot_digest",digest).put("observed_at",result.path("observed_at").asText());
        }
        var history=result.putArray("history");var facts=view.path("facts");
        for(int i=Math.max(0,facts.size()-100);i<facts.size();i++)
            if("ledger".equals(facts.get(i).path("source_id").asText()))history.add(facts.get(i).deepCopy());
        var fingerprint=CreditContracts.JSON.createObjectNode();fingerprint.set("input",result.deepCopy());
        fingerprint.put("eligibility","benefits-v1").put("protocol","rec-v1");
        result.put("comparison_fingerprint",CreditContracts.hash(fingerprint));return result;
    }
    public static ObjectNode ranking(ObjectNode context,JsonNode raw) {
        if(!raw.path("items").isArray()||raw.path("items").size()>100)
            throw new IllegalArgumentException("INVALID_RANKING");
        var pool=new HashMap<String,JsonNode>();
        for(var candidate:context.path("candidates"))pool.put(candidate.path("benefit").path("benefit_id").asText(),candidate);
        ObjectNode result=CreditContracts.JSON.createObjectNode();var items=result.putArray("items");
        var seen=new HashSet<String>();
        for(var choice:raw.path("items")) {
            String id=CreditContracts.required(choice,"benefit_id"),reason=CreditContracts.required(choice,"reason");
            if(!seen.add(id)||!pool.containsKey(id)||reason.length()>1500)
                throw new IllegalArgumentException("RANKING_OUTSIDE_CURRENT_POOL");
            if(items.size()>=context.path("top_k").intValue())continue;
            var item=items.addObject();item.set("benefit",pool.get(id).path("benefit").deepCopy());
            item.set("reference",pool.get(id).path("reference").deepCopy());
            item.put("matching_explanation",reason).put("explanation_kind","model-matching-not-business-fact");
        }
        result.put("status",items.isEmpty()?"no-candidates":"complete");
        result.put("comparison_fingerprint",context.path("comparison_fingerprint").asText());return result;
    }
}
