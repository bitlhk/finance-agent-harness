package io.github.bitlhk.finance.harness.examples.wealth;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentPreparationBroker;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Explicit synthetic read-only acceptance. No product calls, CRM writes, or browser identity inputs. */
public final class ServicePreparationBrokerAcceptance {
    public static void main(String[] args) {
        try { run(args); }
        catch(Exception failure) { System.err.println("PREPARATION_ACCEPTANCE_FAILED:"+failure.getClass().getSimpleName());System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        if(args.length!=4 || !args[2].matches("CUST-[0-9]{6}")) throw new IllegalArgumentException("SYNTHETIC_INPUT_REQUIRED");
        if(System.getenv("INTERNAL_RUNTIME_TOKEN_SECRET")!=null || System.getenv("DATABASE_URL")!=null)
            throw new SecurityException("EA_PRIVATE_ENVIRONMENT_MUST_NOT_BE_INHERITED");
        String owner=args[1];
        var broker=new EmployeeAgentPreparationBroker(URI.create(args[0]),owner,"probe-"+java.util.UUID.randomUUID(),
            ()->System.getenv("HARNESS_PREPARATION_PROBE_TOKEN"),Duration.ofSeconds(20));
        try(var task=new BrokeredServicePreparation(owner,broker,(goal,ids,context)->
                ids.stream().filter(id->!id.endsWith("customerId")).limit(3).toList(),Path.of(args[3]),
                Map.of("runtime","openjiuwen-java@0.1.15","mode","LIVE_PROVIDER_CONTROLLED_EXPLANATION"))) {
            String alias=task.customers(owner).stream().filter(v->v.endsWith("("+args[2]+")")).findFirst().orElseThrow();
            long generation=task.select(owner,alias,"了解合成客户情况，准备回访，不查询产品");
            var report=task.prepare(owner,generation);
            if(!report.has("facts") || !report.path("customerRef").asText().equals(args[2]))
                throw new IllegalStateException("VALID_CUSTOMER_FACTS_MISSING");
            String id=report.path("reportId").asText();
            var reviewed=task.review(owner,id,report.path("reportRevision").asInt(),"工程联调复核：保留事实与缺项，不代表业务Owner验收。");
            var saved=task.read(owner,id);
            if(!saved.equals(reviewed) || !"REVIEWED".equals(saved.path("reviewStatus").asText()))
                throw new IllegalStateException("REPORT_READBACK_MISMATCH");
            boolean isolated=false;
            try { task.read("other-owner",id); } catch(SecurityException expected) { isolated=true; }
            if(!isolated) throw new IllegalStateException("OWNER_ISOLATION_FAILED");
            System.out.println(HarnessJson.mapper().writeValueAsString(Map.of(
                "schema","service-preparation-broker-acceptance.v1","reportId",id,"runId",saved.path("runId").asText(),
                "status",saved.path("status").asText(),"reviewStatus",saved.path("reviewStatus").asText(),
                "factsPreserved",saved.has("facts"),"sourceReceiptPresent",saved.path("sourceReceipt").hasNonNull("receiptId"),
                "missingEvidenceCount",saved.path("missingEvidence").size(),"wrongOwnerRejected",isolated,
                "explanationMode","CONTROLLED_NOT_LIVE_MODEL")));
        }
    }
}
