package io.github.bitlhk.finance.harness.providers.postloan;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;
import java.time.Instant;
import java.util.*;

/** Versioned synthetic reference source extracted from the employee-agent post-loan role pack. */
public final class PostLoanRiskReferenceProvider implements DescribedFinanceProvider {
    public static final String PROVIDER_ID="post-loan-risk-reference", PROVIDER_VERSION="0.1.0-SNAPSHOT";
    public static final String SNAPSHOT="postloan.risk-snapshot.query", ESCALATION="postloan.risk-escalation.evaluate";
    public static final String SUBJECT_TYPE="SYNTHETIC_ENTERPRISE_USCC", DATASET="risk-demo-fixture-v2";
    public static final Instant AS_OF=Instant.parse("2026-08-25T00:00:00Z");

    private static final Map<String,Map<String,Object>> ENTERPRISES=Map.of(
        "91370000726200000X", enterprise("鸿远建设集团有限公司","建筑业","关注",0,15,1.20,"BBB","A",false,3,3500),
        "91440100MA5C00000B", enterprise("德信商业地产开发有限公司","房地产业","次级",35,35,1.10,"BB","BBB",true,8,-5000));

    @Override public ProviderDescriptorV1 descriptor(){return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,
        PROVIDER_ID,PROVIDER_VERSION,List.of(new ProviderDescriptorV1.CapabilitySupport(SNAPSHOT,CapabilityRequestV1.Operation.QUERY),
        new ProviderDescriptorV1.CapabilitySupport(ESCALATION,CapabilityRequestV1.Operation.QUERY)));}

    public List<Map<String,String>> authorizedSubjects(Set<String> refs){return ENTERPRISES.entrySet().stream()
        .filter(e->refs.contains(e.getKey())).sorted(Map.Entry.comparingByKey())
        .map(e->Map.of("subjectRef",e.getKey(),"displayName",String.valueOf(e.getValue().get("enterpriseName")))).toList();}

    @Override public CapabilityResultV1 query(CapabilityRequestV1 request){
        if(request.operation()!=CapabilityRequestV1.Operation.QUERY||!Set.of(SNAPSHOT,ESCALATION).contains(request.capabilityId()))
            return fail(request,"UNSUPPORTED_CAPABILITY");
        if(!request.context().scopes().contains("postloan-risk:read"))return fail(request,"POSTLOAN_RISK_READ_REQUIRED");
        String type=String.valueOf(request.parameters().getOrDefault("subjectType",""));
        String ref=String.valueOf(request.parameters().getOrDefault("subjectRef",""));
        if(!SUBJECT_TYPE.equals(type))return fail(request,"POSTLOAN_SUBJECT_TYPE_UNSUPPORTED");
        if(!ref.equals(request.context().subjectRef()))return fail(request,"POSTLOAN_SUBJECT_BINDING_MISMATCH");
        Map<String,Object> enterprise=ENTERPRISES.get(ref);if(enterprise==null)return fail(request,"ENTERPRISE_NOT_FOUND_OR_NOT_AUTHORIZED");
        Map<String,Object> snapshot=snapshot(ref,enterprise);
        Map<String,Object> data=SNAPSHOT.equals(request.capabilityId())?snapshot:evaluate(snapshot);
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,request.context().requestId(),request.context().traceId(),
            CapabilityResultV1.Status.COMPLETED,SNAPSHOT.equals(request.capabilityId())?"已取得合成企业贷后风险快照。":"已形成确定性内部预警，等待人工复核。",
            data,List.of(),List.of("MANUAL_RISK_REVIEW"),PROVIDER_ID,AS_OF,null,null);
    }
    @Override public CapabilityResultV1 executeTask(CapabilityRequestV1 request){return fail(request,"READ_ONLY_PROVIDER");}

    private static Map<String,Object> snapshot(String ref,Map<String,Object> enterprise){
        var value=new LinkedHashMap<String,Object>();value.put("subjectType",SUBJECT_TYPE);value.put("subjectRef",ref);
        value.put("enterpriseName",enterprise.get("enterpriseName"));value.put("datasetVersion",DATASET);value.put("dataAsOf",AS_OF.toString());
        value.put("sourceMode","demo_fixture");value.put("sourceSystem",PROVIDER_ID);value.put("queryComplete",true);
        value.put("completeness",Map.of("criticalComplete",true,"missingCriticalDomains",List.of()));value.put("domains",enterprise.get("domains"));
        value.put("sourceRecordIds",Map.of("snapshot","demo://post-loan/"+ref+"/snapshot"));return Map.copyOf(value);
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> evaluate(Map<String,Object> snapshot){
        Map<String,Object>d=(Map<String,Object>)snapshot.get("domains");int overdue=((Number)d.get("overdueDays")).intValue();
        String classification=String.valueOf(d.get("fiveLevelClass"));double coverage=((Number)d.get("collateralCoverageRatio")).doubleValue();
        boolean frozen=Boolean.TRUE.equals(d.get("assetsFrozen"));List<String> triggers=new ArrayList<>();String level;
        if(overdue>=90||Set.of("可疑","损失").contains(classification)){level="RED";triggers.add("SEVERE_CREDIT_EVENT");}
        else if(overdue>=30||Set.of("次级","可疑","损失").contains(classification)||coverage<1||frozen){level="ORANGE";if(overdue>=30)triggers.add("OVERDUE_30_DAYS");if(Set.of("次级","可疑","损失").contains(classification))triggers.add("FIVE_LEVEL_ADVERSE");if(frozen)triggers.add("MAJOR_JUDICIAL_EVENT");}
        else if(overdue>0||"关注".equals(classification)){level="YELLOW";triggers.add(overdue>0?"MINOR_OVERDUE":"FIVE_LEVEL_ATTENTION");}
        else {level="GREEN";triggers.add("NO_MATERIAL_SIGNAL");}
        return Map.of("schemaVersion","postloan-risk-escalation.v1","subjectRef",snapshot.get("subjectRef"),"policyCode","POST_LOAN_RISK_ESCALATION",
            "ruleVersion","2.0","level",level,"triggers",List.copyOf(triggers),"requiresHumanEscalation",Set.of("ORANGE","RED").contains(level),
            "formalConclusionAllowed",false,"requiredActions",Set.of("ORANGE","RED").contains(level)?List.of("提交人工风险复核","不得由 Agent 自动调整授信或分类"):List.of("维持人工监测"));
    }
    private static Map<String,Object> enterprise(String name,String industry,String five,int overdue,int maxOverdue,double coverage,String rating,String previous,boolean frozen,int negative,long cashflow){
        Map<String,Object>d=new LinkedHashMap<>();d.put("industryName",industry);d.put("fiveLevelClass",five);d.put("overdueDays",overdue);d.put("maxOverdueDays",maxOverdue);
        d.put("loanBalanceTenThousands",five.equals("次级")?100000:15000);d.put("operatingCashflowTenThousands",cashflow);d.put("collateralCoverageRatio",coverage);
        d.put("internalRating",rating);d.put("previousRating",previous);d.put("assetsFrozen",frozen);d.put("negativeOpinionCount",negative);
        return Map.of("enterpriseName",name,"domains",Map.copyOf(d));
    }
    private static CapabilityResultV1 fail(CapabilityRequestV1 r,String code){String request=r==null||r.context()==null?"unknown":r.context().requestId(),trace=r==null||r.context()==null?"unknown":r.context().traceId();
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,request,trace,CapabilityResultV1.Status.FAILED,"贷后风险来源未返回可用结果。",Map.of(),List.of(),List.of(),PROVIDER_ID,Instant.now(),null,new CapabilityResultV1.Failure("INVALID_INPUT",code,false,"贷后风险来源未返回可用结果。",List.of()));}
}
