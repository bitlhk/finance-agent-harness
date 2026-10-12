package io.github.bitlhk.finance.harness.integrations.postloan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.examples.wealth.InstalledPreparationMethod;
import io.github.bitlhk.finance.harness.providers.postloan.PostLoanRiskReferenceProvider;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Owner-scoped, read-only verification with immutable reports. */
final class PostLoanRiskTask implements AutoCloseable {
    interface ExplanationGenerator {
        ObjectNode generate(InstalledPreparationMethod method, String purpose, List<String> factRefs, JsonNode facts) throws Exception;
    }
    interface Listener {
        Listener NONE=(stage,value)->{};
        void stage(String stage, JsonNode value) throws Exception;
    }
    private record Selection(long generation,String alias,String subjectRef,String purpose,String mappingRevision) {}

    private final String owner;
    private final FinanceProvider provider;
    private final Supplier<PostLoanSubjectMapping> mappings;
    private final ExplanationGenerator generator;
    private final InstalledPreparationMethod method;
    private final Path records;
    private final Map<String,String> assets;
    private final AtomicLong providerCalls=new AtomicLong(), modelCalls=new AtomicLong();
    private long generation;
    private Selection selected;
    private String activeRun;
    private boolean closed;

    PostLoanRiskTask(String owner,FinanceProvider provider,Supplier<PostLoanSubjectMapping> mappings,
            ExplanationGenerator generator,InstalledPreparationMethod method,Path records,Map<String,String> assets) throws Exception {
        this.owner=Objects.requireNonNull(owner);this.provider=Objects.requireNonNull(provider);
        this.mappings=Objects.requireNonNull(mappings);this.generator=Objects.requireNonNull(generator);
        this.method=Objects.requireNonNull(method);this.records=records.toAbsolutePath();this.assets=Map.copyOf(assets);
        Files.createDirectories(this.records);
        if(Files.isSymbolicLink(this.records))throw new SecurityException("UNSAFE_REPORT_DIRECTORY");
        Files.setPosixFilePermissions(this.records,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    private void owner(String caller){if(closed||!owner.equals(caller))throw new SecurityException("OWNER_OR_SESSION_REJECTED");}
    synchronized List<String> subjects(String caller){owner(caller);return mappings.get().subjects().keySet().stream().sorted().toList();}
    synchronized long select(String caller,String alias,String purpose){
        owner(caller);generation++;selected=null;activeRun=null;
        if(purpose==null||purpose.isBlank()||purpose.length()>500)throw new IllegalArgumentException("PURPOSE_REQUIRED");
        PostLoanSubjectMapping mapping=mappings.get();String ref=mapping.subjects().get(alias);
        if(ref==null||ref.isBlank())throw new SecurityException("POSTLOAN_SUBJECT_NOT_AUTHORIZED");
        selected=new Selection(generation,alias,ref,purpose,mapping.revision());return generation;
    }
    private void current(Selection value,String run){
        PostLoanSubjectMapping now=mappings.get();
        if(closed||selected!=value||!Objects.equals(activeRun,run)||!value.mappingRevision().equals(now.revision())
                ||!value.subjectRef().equals(now.subjects().get(value.alias())))throw new SecurityException("STALE_RUN_OR_AUTHORITY");
    }
    ObjectNode verify(String caller,long expected,Listener listener)throws Exception{
        final Selection selection;final String run=UUID.randomUUID().toString();final ObjectNode report=HarnessJson.mapper().createObjectNode();
        synchronized(this){owner(caller);selection=selected;if(selection==null||selection.generation()!=expected)throw new SecurityException("STALE_SELECTION");
            if(activeRun!=null)throw new IllegalStateException("ALREADY_VERIFIED_SELECT_AGAIN");activeRun=run;current(selection,run);
            report.put("schemaVersion","post-loan-risk-verification-report.v0.1");report.put("reportId",run);report.put("runId",run);
            report.put("ownerRef",owner);report.put("subjectAlias",selection.alias());report.put("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE);
            report.put("subjectRef",selection.subjectRef());report.put("purpose",selection.purpose());report.put("generation",selection.generation());
            report.put("mappingRevision",selection.mappingRevision());report.put("startedAt",Instant.now().toString());report.put("status","RUNNING");
            report.put("reviewStatus","PENDING");report.put("reportRevision",0);report.set("assets",HarnessJson.mapper().valueToTree(assets));
            report.set("method",HarnessJson.mapper().valueToTree(Map.of("id",method.id(),"version",method.version(),"digest",method.digest(),"lockDigest",method.lockDigest())));
            persist(report);
        }
        try{
            synchronized(this){current(selection,run);}providerCalls.incrementAndGet();
            var context=new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION,run,run,owner,
                    FinanceContextV1.PrincipalType.EMPLOYEE,"post-loan-risk-verification","READ_ONLY_INVESTIGATION",
                    selection.subjectRef(),run,FinanceContextV1.AuthenticationLevel.STRONG,Set.of("postloan-risk:read"));
            var request=new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,PostLoanRiskReferenceProvider.SNAPSHOT,
                    CapabilityRequestV1.Operation.QUERY,context,
                    Map.of("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE,"subjectRef",selection.subjectRef()),
                    null,null,Map.of(),null);
            CapabilityResultV1 result=provider.query(request);synchronized(this){current(selection,run);}
            report.set("providerResult",HarnessJson.mapper().valueToTree(result));report.put("source","post-loan-risk-reference");report.put("queriedAt",Instant.now().toString());
            if(result.status()!=CapabilityResultV1.Status.COMPLETED){
                report.put("verificationStatus","FAILED");report.put("queryComplete",false);report.put("status","PARTIAL");
                report.put("explanationStatus","NOT_STARTED");report.put("failureCode",result.error()==null?"PROVIDER_FAILED":result.error().sourceCode());
                report.putArray("missingEvidence").add("贷后风险事实查询未完成，不能形成完整核验结论");
            }else{
                ObjectNode facts=HarnessJson.mapper().valueToTree(result.data());
                if(!selection.subjectRef().equals(facts.path("subjectRef").asText())||!PostLoanRiskReferenceProvider.SUBJECT_TYPE.equals(facts.path("subjectType").asText()))
                    throw new SecurityException("RESULT_SUBJECT_MISMATCH");
                facts.put("purpose",selection.purpose());providerCalls.incrementAndGet();
                var warningRequest=new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION,PostLoanRiskReferenceProvider.ESCALATION,
                    CapabilityRequestV1.Operation.QUERY,context,Map.of("subjectType",PostLoanRiskReferenceProvider.SUBJECT_TYPE,"subjectRef",selection.subjectRef()),null,null,Map.of(),null);
                CapabilityResultV1 warning=provider.query(warningRequest);synchronized(this){current(selection,run);}
                if(warning.status()!=CapabilityResultV1.Status.COMPLETED)throw new IllegalStateException("DETERMINISTIC_POLICY_FAILED");
                facts.set("deterministicWarning",HarnessJson.mapper().valueToTree(warning.data()));
                report.set("facts",facts.deepCopy());report.put("verificationStatus",facts.path("queryComplete").asBoolean(false)?"SNAPSHOT_READY":"VERIFICATION_INCOMPLETE");
                report.put("queryComplete",facts.path("queryComplete").asBoolean(false));report.put("evidenceAsOf",result.asOf().toString());
                ObjectNode modelFacts=modelFacts(facts);List<String> refs=new ArrayList<>();collect(modelFacts,"",refs,300);
                report.set("factRefs",HarnessJson.mapper().valueToTree(refs));
                report.put("status","PARTIAL");report.put("explanationStatus","RUNNING");persist(report);listener.stage("FACTS_READY",report.deepCopy());
                try{
                    synchronized(this){current(selection,run);}modelCalls.incrementAndGet();
                    ObjectNode explanation=generator.generate(method,selection.purpose(),List.copyOf(refs),modelFacts.deepCopy());
                    validate(explanation,refs,modelFacts);synchronized(this){current(selection,run);}
                    report.set("modelExplanation",explanation);report.put("explanationStatus","READY");report.put("status","READY_FOR_REVIEW");
                }catch(Exception failure){
                    if(failure instanceof SecurityException)throw failure;
                    report.put("explanationStatus","FAILED");report.put("status","PARTIAL");
                    report.put("failureCode",failureCode(failure));report.put("failureStage",failureStage(failure));
                    if(failure instanceof ExplanationValidationException invalid)report.set("failureDetail",invalid.detail());
                    report.putArray("missingEvidence").add("核验解释未完整生成；确定性风险事实与查询完整性已保留");
                }
            }
            report.put("finishedAt",Instant.now().toString());synchronized(this){current(selection,run);persist(report);return report.deepCopy();}
        }catch(SecurityException stale){
            synchronized(this){ObjectNode discarded=report.deepCopy();discarded.remove(List.of("facts","providerResult","modelExplanation"));discarded.put("status","DISCARDED");persist(discarded);}throw stale;
        }
    }
    private static String failureCode(Exception failure){
        if(failure instanceof ExplanationValidationException invalid)return invalid.code();
        return switch(String.valueOf(failure.getMessage())){
            case "MODEL_REPORT_EMPTY","MODEL_REPORT_INVALID"->"REPORT_MODEL_PARSE_FAILED";
            case "INVALID_EXPLANATION","INVALID_EXPLANATION_ITEM"->"REPORT_MODEL_SCHEMA_REJECTED";
            case "UNKNOWN_FACT_REF"->"REPORT_MODEL_FACT_REF_REJECTED";
            case "UNSAFE_EXPLANATION"->"REPORT_MODEL_SAFETY_REJECTED";
            default->"REPORT_MODEL_FAILED";
        };
    }
    private static String failureStage(Exception failure){
        return switch(failureCode(failure)){
            case "REPORT_MODEL_PARSE_FAILED"->"MODEL_RESPONSE_PARSE";
            case "REPORT_MODEL_SCHEMA_REJECTED"->"MODEL_RESPONSE_SCHEMA";
            case "REPORT_MODEL_FACT_REF_REJECTED","REPORT_MODEL_SAFETY_REJECTED","REPORT_MODEL_RULE_EVIDENCE_REJECTED"->"MODEL_RESPONSE_VALIDATION";
            default->"MODEL_GENERATION";
        };
    }
    private static void validate(ObjectNode value,List<String> allowed,JsonNode facts){
        Set<String> fields=Set.of("summary","evidenceInterpretation","pendingItems","reviewChecklist");
        var actual=new HashSet<String>();value.fieldNames().forEachRemaining(actual::add);if(!actual.equals(fields))throw new IllegalArgumentException("INVALID_EXPLANATION");
        for(String field:List.of("summary","evidenceInterpretation")){int index=0;for(JsonNode item:value.path(field)){
            if(!item.isObject()||!item.path("text").isTextual()||!item.path("factRefs").isArray())throw new IllegalArgumentException("INVALID_EXPLANATION_ITEM");
            for(JsonNode ref:item.path("factRefs"))if(!allowed.contains(ref.asText())||facts.at(ref.asText()).isMissingNode())
                throw invalid("REPORT_MODEL_FACT_REF_REJECTED",field,index,"UNKNOWN_FACT_REF",item.path("text").asText());
            String text=item.path("text").asText();
            if(unsafeAuthorityClaim(text))throw invalid("REPORT_MODEL_SAFETY_REJECTED",field,index,"UNAUTHORIZED_AUTOMATION_CLAIM",text);
            if(unsupportedCollateralConclusion(text,item.path("factRefs"),facts))
                throw invalid("REPORT_MODEL_RULE_EVIDENCE_REJECTED",field,index,"COLLATERAL_RULE_EVIDENCE_REQUIRED",text);
            index++;
        }
        }
        for(String field:List.of("pendingItems","reviewChecklist")){
            if(!value.path(field).isArray())throw new IllegalArgumentException("INVALID_EXPLANATION_ITEM");
            int index=0;for(JsonNode item:value.path(field)){
                if(!item.isTextual())throw new IllegalArgumentException("INVALID_EXPLANATION_ITEM");
                String text=item.asText();
                if(unsafeAuthorityClaim(text))throw invalid("REPORT_MODEL_SAFETY_REJECTED",field,index,"UNAUTHORIZED_AUTOMATION_CLAIM",text);
                if(unsupportedCollateralConclusion(text,HarnessJson.mapper().createArrayNode(),facts))
                    throw invalid("REPORT_MODEL_RULE_EVIDENCE_REJECTED",field,index,"COLLATERAL_RULE_EVIDENCE_REQUIRED",text);
                index++;
            }
        }
    }
    private static ExplanationValidationException invalid(String code,String section,int index,String rule,String text){
        return new ExplanationValidationException(code,section,index,rule,text);
    }
    private static boolean unsupportedCollateralConclusion(String text,JsonNode refs,JsonNode facts){
        if(!(text.contains("担保覆盖率不足")||text.contains("担保不足")||text.contains("覆盖不足")
                ||text.contains("担保覆盖率充足")||text.contains("担保充足")||text.contains("覆盖充足")))return false;
        JsonNode rule=facts.at("/deterministicWarning/collateralCoverageRule");
        Set<String> actual=new HashSet<>();refs.forEach(ref->actual.add(ref.asText()));
        Set<String> required=Set.of("/domains/collateralCoverageRatio","/deterministicWarning/collateralCoverageRule/ruleVersion",
            "/deterministicWarning/collateralCoverageRule/applicableWhen","/deterministicWarning/collateralCoverageRule/threshold",
            "/deterministicWarning/collateralCoverageRule/matched");
        boolean insufficient=text.contains("不足");
        return !actual.containsAll(required)||rule.isMissingNode()||!rule.path("matched").isBoolean()
            ||insufficient!=rule.path("matched").asBoolean();
    }
    static boolean unsafeAuthorityClaim(String text){
        for(String phrase:List.of("自动调整授信","自动调高授信","自动调低授信","自动提高授信","自动降低授信","自动变更授信",
                "自动调整额度","自动调高额度","自动调低额度","自动提高额度","自动降低额度","自动变更额度",
                "自动调整分类","自动变更分类","自动认定分类","授信审批通过","自动审批授信","自动通过授信审批","监管五级分类结论")){
            int offset=0;
            while((offset=text.indexOf(phrase,offset))>=0){
                String before=text.substring(Math.max(0,offset-28),offset);
                String after=text.substring(offset+phrase.length(),Math.min(text.length(),offset+phrase.length()+36));
                if(doubleNegative(before)||(!negated(before)&&!rejectedAttribution(before,after)))return true;
                offset+=phrase.length();
            }
        }
        return false;
    }
    private static boolean negated(String before){
        return before.matches("(?s).*(禁止|严禁|不得|不应|不能|无需|不建议|不可|未建议|不允许|不采纳|不认可)(由.{0,12})?$");
    }
    private static boolean doubleNegative(String before){
        return before.matches("(?s).*(不得不|不能不|并非无需|并非不应|不是不得)(由.{0,12})?$");
    }
    private static boolean rejectedAttribution(String before,String after){
        boolean attributed=before.matches("(?s).*(声称|表示|(?:有人|客户经理|报告)?说|提出|提议|要求|认为|建议)[：:“”\"']?.{0,12}$");
        return attributed&&after.matches("(?s).{0,24}(不采纳|不认可|已否决|不得据此|仅为转述|不构成建议|不作为结论).*");
    }
    private static final class ExplanationValidationException extends IllegalArgumentException{
        private final String code,section,rule,text;private final int index;
        ExplanationValidationException(String code,String section,int index,String rule,String text){super(code);this.code=code;this.section=section;this.index=index;this.rule=rule;this.text=text;}
        String code(){return code;}
        ObjectNode detail(){ObjectNode value=HarnessJson.mapper().createObjectNode();value.put("code",code);value.put("section",section);
            value.put("itemIndex",index);value.put("rule",rule);value.put("sentence",text.length()>180?text.substring(0,180):text);return value;}
    }
    synchronized ObjectNode review(String caller,String id,int revision,String opinion)throws Exception{
        ObjectNode report=read(caller,id);if(revision!=report.path("reportRevision").asInt())throw new IllegalStateException("REPORT_VERSION_CONFLICT");
        if(opinion==null||opinion.isBlank()||opinion.length()>2000)throw new IllegalArgumentException("REVIEW_REQUIRED");
        report.put("reviewStatus","REVIEWED");report.put("reviewerRef",owner);report.put("reviewOpinion",opinion);report.put("reviewedAt",Instant.now().toString());persist(report);return report.deepCopy();
    }
    synchronized ObjectNode read(String caller,String id)throws Exception{owner(caller);ObjectNode value=readLatest(id);authorize(value);return value;}
    synchronized ObjectNode readVersion(String caller,String id,int revision)throws Exception{read(caller,id);Path file=directory(id).resolve(String.format("v%06d.json",revision));if(!Files.isRegularFile(file))throw new IllegalArgumentException("REPORT_VERSION_NOT_FOUND");ObjectNode value=readVerified(file);authorize(value);return value;}
    synchronized List<Integer> versions(String caller,String id)throws Exception{
        read(caller,id);
        try(var paths=Files.list(directory(id))){
            return paths.filter(Files::isRegularFile)
                    .filter(p->p.getFileName().toString().matches("v[0-9]{6}\\.json"))
                    .filter(p->{
                        try{
                            ObjectNode version=readVerified(p);
                            return !Set.of("RUNNING","DISCARDED").contains(version.path("status").asText())
                                    && !"RUNNING".equals(version.path("explanationStatus").asText());
                        }
                        catch(Exception invalid){return false;}
                    })
                    .map(p->Integer.parseInt(p.getFileName().toString().substring(1,7)))
                    .sorted().toList();
        }
    }
    synchronized Optional<ObjectNode> latest(String caller)throws Exception{owner(caller);if(!Files.isDirectory(records))return Optional.empty();try(var paths=Files.list(records)){for(Path p:paths.filter(Files::isDirectory).sorted((a,b)->Long.compare(modified(b),modified(a))).limit(1000).toList())try{ObjectNode value=read(caller,p.getFileName().toString());if(!Set.of("RUNNING","DISCARDED").contains(value.path("status").asText())&&!"RUNNING".equals(value.path("explanationStatus").asText()))return Optional.of(value);}catch(Exception ignored){}return Optional.empty();}}
    synchronized ObjectNode replay(String caller,String id,Integer revision)throws Exception{
        long p=providerCalls.get(),m=modelCalls.get();ObjectNode report=revision==null?read(caller,id):readVersion(caller,id,revision);ObjectNode result=HarnessJson.mapper().createObjectNode();result.set("report",report);
        ObjectNode evidence=result.putObject("replayEvidence");evidence.put("businessProviderCalls",providerCalls.get()-p);evidence.put("modelGenerationCalls",modelCalls.get()-m);evidence.put("authorizationRevalidated",true);evidence.put("recordedAt",Instant.now().toString());return result;
    }
    private void authorize(JsonNode report){owner(report.path("ownerRef").asText());PostLoanSubjectMapping map=mappings.get();if(!report.path("mappingRevision").asText().equals(map.revision())||!report.path("subjectRef").asText().equals(map.subjects().get(report.path("subjectAlias").asText())))throw new SecurityException("REPORT_AUTHORITY_CHANGED");}
    private ObjectNode readLatest(String id)throws Exception{Path dir=directory(id);JsonNode pointer=HarnessJson.mapper().readTree(Files.readAllBytes(dir.resolve("latest.json")));Path version=dir.resolve(String.format("v%06d.json",pointer.path("revision").asInt()));byte[] bytes=Files.readAllBytes(version);if(!hash(bytes).equals(pointer.path("digest").asText()))throw new SecurityException("REPORT_VERSION_DIGEST_MISMATCH");ObjectNode value=readVerified(version);if(value.path("reportRevision").asInt()!=pointer.path("revision").asInt())throw new SecurityException("REPORT_VERSION_POINTER_MISMATCH");return value;}
    private Path directory(String id)throws Exception{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("INVALID_REPORT_ID");Path p=records.resolve(id);if(Files.isSymbolicLink(p))throw new SecurityException("UNSAFE_REPORT_DIRECTORY");Files.createDirectories(p);return p;}
    private void persist(ObjectNode value)throws Exception{
        int next=value.path("reportRevision").asInt()+1;value.put("reportRevision",next);
        byte[] bytes=HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(value);String digest=hash(bytes);
        Path dir=directory(value.path("reportId").asText()),target=dir.resolve(String.format("v%06d.json",next));
        Path digestTarget=target.resolveSibling(target.getFileName()+".sha256"),pointer=dir.resolve("latest.json");
        if(Files.exists(target)||Files.exists(digestTarget))throw new IllegalStateException("REPORT_VERSION_EXISTS");
        atomicCreate(target,bytes);atomicCreate(digestTarget,digest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        atomicReplace(pointer,HarnessJson.mapper().writeValueAsBytes(Map.of("revision",next,"digest",digest)));
    }
    private ObjectNode readVerified(Path file)throws Exception{
        byte[] bytes=Files.readAllBytes(file);Path digestFile=file.resolveSibling(file.getFileName()+".sha256");
        if(!Files.isRegularFile(digestFile)||!hash(bytes).equals(Files.readString(digestFile).trim()))throw new SecurityException("REPORT_VERSION_DIGEST_MISMATCH");
        return (ObjectNode)HarnessJson.mapper().readTree(bytes);
    }
    private static void atomicCreate(Path target,byte[] bytes)throws Exception{
        Path temporary=Files.createTempFile(target.getParent(),".report-",".tmp");
        try{Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}
    }
    private static void atomicReplace(Path target,byte[] bytes)throws Exception{
        Path temporary=Files.createTempFile(target.getParent(),".pointer-",".tmp");
        try{Files.write(temporary,bytes,StandardOpenOption.TRUNCATE_EXISTING);Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    private static String hash(byte[] bytes)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static long modified(Path p){try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return 0;}}
    static ObjectNode modelFacts(ObjectNode source){ObjectNode copy=source.deepCopy();pruneEmptyContainers(copy);return copy;}
    private static void pruneEmptyContainers(JsonNode node){
        if(node==null||!node.isContainerNode())return;
        if(node.isArray())node.forEach(PostLoanRiskTask::pruneEmptyContainers);
        else{
            List<String> remove=new ArrayList<>();
            node.fields().forEachRemaining(entry->{
                JsonNode child=entry.getValue();pruneEmptyContainers(child);
                if(child.isContainerNode()&&child.isEmpty())remove.add(entry.getKey());
            });
            remove.forEach(((ObjectNode)node)::remove);
        }
    }
    private static void collect(JsonNode node,String path,List<String> refs,int limit){if(refs.size()>=limit||node==null||node.isNull())return;if(node.isValueNode()){if(!path.isBlank())refs.add(path);return;}if(node.isArray()){for(int i=0;i<node.size();i++)collect(node.path(i),path+"/"+i,refs,limit);}else node.fields().forEachRemaining(e->collect(e.getValue(),path+"/"+e.getKey().replace("~","~0").replace("/","~1"),refs,limit));}
    @Override public synchronized void close(){closed=true;selected=null;activeRun=null;}
}
