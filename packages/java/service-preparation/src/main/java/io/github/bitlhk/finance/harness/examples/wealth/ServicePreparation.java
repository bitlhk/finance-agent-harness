package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.evidence.ScenarioEvidence;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Single-session example Host. Authority and mappings are injected by the server, never by the model. */
public final class ServicePreparation implements AutoCloseable {
    public static final String CAPABILITY = "wealth.previsit-context.query";
    public record Mapping(String revision, Map<String,String> customers) {
        public Mapping { Objects.requireNonNull(revision); customers = Map.copyOf(customers); }
    }
    @FunctionalInterface public interface Explanation {
        // Only select existing fact references. The Host renders values; no free-form business assertions.
        List<String> selectFocus(String goal, List<String> factIds, JsonNode context) throws Exception;
    }
    @FunctionalInterface public interface ReportGenerator {
        ObjectNode generate(InstalledPreparationMethod method, String goal,
                            List<String> factIds, JsonNode context) throws Exception;
        default ObjectNode generateStreaming(InstalledPreparationMethod method, String goal,
                List<String> factIds, JsonNode context, java.util.function.Consumer<ReportDelta> sink) throws Exception {
            ObjectNode value = generate(method, goal, factIds, context);
            for (String section : List.of("communicationFocus", "preparationAdvice"))
                value.path(section).forEach(item -> sink.accept(new ReportDelta(section,
                        item.path("text").asText(), jsonStrings(item.path("factRefs")))));
            for (String section : List.of("pendingItems", "reviewChecklist"))
                value.path(section).forEach(item -> sink.accept(new ReportDelta(section, item.asText(), List.of())));
            return value;
        }
    }
    public record ReportDelta(String section, String text, List<String> factRefs) {
        public ReportDelta { factRefs = List.copyOf(factRefs); }
    }
    public interface PreparationListener {
        PreparationListener NONE = new PreparationListener() {};
        default void stage(String stage, JsonNode snapshot) throws Exception {}
        default void reportDelta(ReportDelta delta) throws Exception {}
    }
    private static final class Completion { boolean cancelled; boolean finished; }
    private record Selection(long generation, String alias, String customer, String goal,
                             JsonNode taskContext, Mapping mapping, PreparationAccess.Snapshot authority,
                             Completion completion) {}
    private final String owner;
    private final PreparationAccess access;
    private final Supplier<Mapping> mappings;
    private final ReportGenerator generator;
    private final InstalledPreparationMethod method;
    private final Path records;
    private final Map<String,String> versions;
    private final ScenarioEvidence evidence;
    private final AtomicLong businessProviderCalls = new AtomicLong();
    private final AtomicLong modelGenerationCalls = new AtomicLong();
    private long generation;
    private Selection selected;
    private String activeRun;
    private boolean closed;

    public ServicePreparation(String owner, PreparationAccess access, Supplier<Mapping> mappings,
            Explanation explanation, Path records, Map<String,String> versions) throws Exception {
        this(owner, access, mappings, legacy(explanation), records, versions,
                new InstalledPreparationMethod("legacy-focus-selector", "0", "sha256:legacy",
                        "sha256:legacy-lock", "Legacy compatibility adapter; not a managed method asset."));
    }
    public ServicePreparation(String owner, PreparationAccess access, Supplier<Mapping> mappings,
            ReportGenerator generator, Path records, Map<String,String> versions,
            InstalledPreparationMethod method) throws Exception {
        this(owner,access,mappings,generator,records,versions,method,Map::of);
    }
    public ServicePreparation(String owner, PreparationAccess access, Supplier<Mapping> mappings,
            ReportGenerator generator, Path records, Map<String,String> versions,
            InstalledPreparationMethod method, Supplier<Map<String,String>> trustedHost) throws Exception {
        this.owner = Objects.requireNonNull(owner); this.access = Objects.requireNonNull(access);
        this.mappings = Objects.requireNonNull(mappings); this.generator = Objects.requireNonNull(generator);
        this.method = Objects.requireNonNull(method);
        this.records = records.toAbsolutePath(); this.versions = Map.copyOf(versions);
        this.evidence = new ScenarioEvidence(this.records,trustedHost);
        Files.createDirectories(this.records);
        if (Files.isSymbolicLink(this.records)) throw new SecurityException("UNSAFE_RECORD_DIRECTORY");
        Files.setPosixFilePermissions(this.records, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    private void owner(String caller) {
        if (closed || !owner.equals(caller)) throw new SecurityException("OWNER_OR_SESSION_REJECTED");
    }
    public synchronized List<String> customers(String caller) {
        owner(caller); Mapping m = mappings.get();
        if (m == null) throw new SecurityException("CUSTOMER_MAPPING_MISSING");
        return m.customers().entrySet().stream().filter(e -> {
            try { access.authorize(e.getValue()); return true; }
            catch (SecurityException denied) {
                if ("CRM_EMPLOYEE_SCOPE_EXPIRED".equals(denied.getMessage())) throw denied;
                return false;
            }
        }).map(Map.Entry::getKey).sorted().toList();
    }
    public synchronized long select(String caller, String alias, String goal) {
        return select(caller, alias, goal, HarnessJson.mapper().createObjectNode());
    }
    public synchronized long select(String caller, String alias, String goal, JsonNode taskContext) {
        owner(caller);
        // Even a rejected selection invalidates the old customer's active report generation.
        generation++; selected = null; activeRun = null;
        if (goal == null || goal.isBlank() || goal.length() > 500) throw new IllegalArgumentException("GOAL_REQUIRED");
        Mapping m = mappings.get();
        if (m == null || !m.customers().containsKey(alias)) throw new SecurityException("CUSTOMER_MAPPING_MISSING");
        String customer = m.customers().get(alias);
        var authority = access.authorize(customer);
        if (taskContext == null || !taskContext.isObject()) throw new IllegalArgumentException("TASK_CONTEXT_INVALID");
        selected = new Selection(generation, alias, customer, goal, taskContext.deepCopy(), m, authority, new Completion());
        return generation;
    }
    public synchronized String cancel(String caller, long expectedGeneration) throws Exception {
        owner(caller);
        if (selected == null || selected.generation() != expectedGeneration) return "NOT_ACTIVE";
        if (selected.completion().cancelled) return "CANCELLED";
        if (selected.completion().finished) return "NOT_ACTIVE";
        if (activeRun != null) {
            // Only the last durable snapshot is retained; a late model result cannot rewrite it.
            ObjectNode saved = (ObjectNode) HarnessJson.mapper().readTree(Files.readAllBytes(latestPath(activeRun)));
            saved.remove(List.of("modelSuggestion", "fallbackSuggestion"));
            saved.put("status", "CANCELLED"); saved.put("explanationStatus", "CANCELLED");
            saved.put("finishedAt", Instant.now().toString());
            if (saved.path("modelDiagnostics").isObject()) {
                ObjectNode diagnostics=(ObjectNode)saved.get("modelDiagnostics");
                diagnostics.put("status","CANCELLED");diagnostics.put("stage","RESULT_RELEASE_CANCELLED");
                diagnostics.put("terminationSource","USER_REQUEST");
                diagnostics.put("upstreamTerminationObserved",false);
            }
            saved.withArray("events").add("USER_CANCELLED_RESULT_RELEASE");
            persist(saved);
        }
        selected.completion().cancelled = true;
        return "CANCELLED";
    }
    private void current(Selection s, String run) {
        if (s.completion().cancelled) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        if (closed || selected != s || !Objects.equals(run, activeRun)
                || !s.mapping().equals(mappings.get())
                || !s.authority().equals(access.authorize(s.customer()))) {
            throw new SecurityException("STALE_RUN_OR_AUTHORITY");
        }
    }
    public ObjectNode prepare(String caller, long expectedGeneration) throws Exception {
        return prepare(caller, expectedGeneration, PreparationListener.NONE);
    }
    public ObjectNode prepare(String caller, long expectedGeneration, PreparationListener listener) throws Exception {
        return prepareInternal(caller, expectedGeneration, null, listener);
    }
    public ObjectNode prepareProduct(String caller, long expectedGeneration, Map<String,Object> arguments,
            PreparationListener listener) throws Exception {
        if(arguments==null||!arguments.keySet().equals(Set.of("amount","horizonMonths","channel")))
            throw new IllegalArgumentException("PRODUCT_REVIEW_ARGUMENTS_REQUIRED");
        return prepareInternal(caller, expectedGeneration, Map.copyOf(arguments), listener);
    }
    private ObjectNode prepareInternal(String caller, long expectedGeneration, Map<String,Object> productArguments,
            PreparationListener listener) throws Exception {
        Objects.requireNonNull(listener);
        final Selection s; final String run = UUID.randomUUID().toString();
        final ObjectNode report = HarnessJson.mapper().createObjectNode();
        synchronized (this) {
            owner(caller); s = selected;
            if (s == null || expectedGeneration != s.generation()) throw new SecurityException("STALE_SELECTION");
            if (activeRun != null) throw new IllegalStateException("ALREADY_PREPARED_SELECT_AGAIN");
            activeRun = run; current(s,run);
            report.put("schemaVersion","service-preparation-report.v0.1");
            report.put("reportId",run); report.put("runId",run); report.put("ownerRef",owner);
            report.put("customerRef",s.customer()); report.put("customerAlias",s.alias());
            report.put("goal",s.goal()); report.put("generation",s.generation());
            report.set("taskContext",s.taskContext().deepCopy());
            report.put("mappingRevision",s.mapping().revision());
            report.put("authorityRef",s.authority().authorityRef());
            report.put("startedAt",Instant.now().toString()); report.put("status","RUNNING");
            report.put("reviewStatus","PENDING"); report.put("reportRevision",0);
            report.set("assets",HarnessJson.mapper().valueToTree(versions));
            report.set("method", HarnessJson.mapper().valueToTree(Map.of("id", method.id(), "version", method.version(),
                    "digest", method.digest(), "lockDigest", method.lockDigest())));
            report.putArray("events").add("RUN_STARTED"); persist(report);
        }
        var proof=evidence.begin(run,owner,s.customer(),s.goal(),CAPABILITY,
                Map.of("customerRef",s.customer(),"taskContext",s.taskContext()),s.authority(),"CURRENT_CRM_EMPLOYEE_AUTHORITY");
        report.set("executionBinding",proof.binding());
        Runnable authorize=()->{synchronized(this){current(s,run);}};
        try {
            proof.check("ENTRY",authorize,null);proof.invoked();
            businessProviderCalls.incrementAndGet();
            CapabilityResultV1 result = querySource(run,run,s.customer(),CAPABILITY,Map.of(),proof);
            proof.returned();proof.check("RETURN",authorize,result);
            JsonNode data = HarnessJson.mapper().valueToTree(result.data());
            JsonNode context = data.path("previsitContext");
            report.set("execution",HarnessJson.mapper().valueToTree(result));
            var missing = report.putArray("missingEvidence");
            if (result.status() != CapabilityResultV1.Status.COMPLETED || !context.isObject()) {
                report.put("status","FAILED"); missing.add("访前事实查询未完成");
                if(result.error()!=null)report.put("failureCode",result.error().sourceCode());
            } else {
                String customer = context.path("customer").path("customerId").asText();
                if (!s.customer().equals(customer)) throw new SecurityException("RESULT_CUSTOMER_MISMATCH");
                report.set("facts",context.deepCopy());
                report.set("sourceReceipt",data.path("hostEvidence").deepCopy());
                String asOf = context.path("dataAsOf").asText("");
                report.put("dataAsOf",asOf); // Never substitute receipt time for business data time.
                if (asOf.isBlank()) missing.add("客户事实缺少业务数据日期");
                if (data.path("hostEvidence").path("receiptId").asText().isBlank()) missing.add("缺少来源回执");
                var ids = new ArrayList<String>();
                collectFactRefs(context, "", ids, 200);
                report.set("factRefs",HarnessJson.mapper().valueToTree(ids));
                ObjectNode generationContext=(ObjectNode)context.deepCopy();
                generationContext.set("taskContext",s.taskContext().deepCopy());
                collectFactRefs(s.taskContext(),"/taskContext",ids,ids.size()+100);
                if(productArguments!=null) {
                    var productProof=evidence.begin(run+"-products",owner,s.customer(),s.goal(),"wealth.allocation-context.query",
                            Map.of("customerRef",s.customer(),"arguments",productArguments),s.authority(),"CURRENT_CRM_EMPLOYEE_AUTHORITY");
                    report.set("productExecutionBinding",productProof.binding());
                    productProof.check("ENTRY",authorize,null);productProof.invoked();
                    businessProviderCalls.incrementAndGet();
                    CapabilityResultV1 allocation=querySource(run+"-products",run,s.customer(),"wealth.allocation-context.query",productArguments,productProof);
                    productProof.returned();productProof.check("RETURN",authorize,allocation);
                    if(allocation.status()!=CapabilityResultV1.Status.COMPLETED) {
                        missing.add("产品适当性筛选未完成");
                        if(allocation.error()!=null)report.put("failureCode",allocation.error().sourceCode());
                    } else {
                        JsonNode productReview=HarnessJson.mapper().valueToTree(allocation.data());
                        report.set("productReview",productReview.deepCopy());generationContext.set("productReview",productReview.deepCopy());
                        for(String field:List.of("amount","horizonMonths","channel"))
                            if(productReview.path("request").has(field))
                                ids.add("/productReview/request/"+field);
                        for(int i=0;i<productReview.path("eligibleProducts").size();i++) {
                            final int productIndex=i;
                            JsonNode product=productReview.path("eligibleProducts").path(i);
                            product.fieldNames().forEachRemaining(k->ids.add("/productReview/eligibleProducts/"+productIndex+"/"+k.replace("~","~0").replace("/","~1")));
                        }
                        report.withArray("events").add("PRODUCT_POLICY_SCREENED");
                    }
                }
                ObjectNode factAssessment=assessFacts(s.goal(),context,report.path("productReview"));
                report.set("factAssessment",factAssessment.deepCopy());
                generationContext.set("factAssessment",factAssessment.deepCopy());
                ObjectNode factState=factState(context,s.taskContext(),data.path("hostEvidence"),Instant.now());
                report.set("factState",factState.deepCopy());generationContext.set("factState",factState.deepCopy());
                collectFactRefs(factState,"/factState",ids,ids.size()+160);
                report.set("clarificationQuestions",clarificationQuestions(factAssessment,s.taskContext()));
                ObjectNode factPresentation=presentFacts(context,Instant.now());
                report.set("factPresentation",factPresentation.deepCopy());
                ObjectNode temporalAssessment=temporalAssessment(factPresentation);
                generationContext.set("temporalAssessment",temporalAssessment.deepCopy());
                collectFactRefs(temporalAssessment,"/temporalAssessment",ids,ids.size()+40);
                report.set("factRefs",HarnessJson.mapper().valueToTree(ids));
                if(productArguments==null) {
                    if (!"READY".equals(context.path("readiness").path("status").asText())) missing.add("来源未声明准备条件全部就绪");
                    data.path("missingInputs").forEach(missing::add);
                    context.path("readiness").path("reasons").forEach(missing::add);
                }
                factAssessment.path("missing").forEach(item->missing.add(item.path("label").asText()));
                report.put("status",missing.isEmpty()&&"READY".equals(factAssessment.path("status").asText())
                        ?"READY_FOR_REVIEW":"PARTIAL");
                String factsStatus = report.path("status").asText();
                report.put("status","PARTIAL"); report.put("explanationStatus","RUNNING");
                ObjectNode modelDiagnostics=report.putObject("modelDiagnostics");
                modelDiagnostics.put("status","RUNNING");modelDiagnostics.put("stage","MODEL_DISPATCH");
                modelDiagnostics.put("startedAt",Instant.now().toString());modelDiagnostics.put("provisionalDeltaCount",0);
                report.withArray("events").add("FACTS_PRESERVED");
                synchronized (this) { current(s,run); persist(report); }
                proof.check("FACTS_RELEASE",authorize,report);listener.stage("FACTS_READY", report.deepCopy());
                var provisionalDeltas=new AtomicLong();
                var firstDeltaAt=new java.util.concurrent.atomic.AtomicReference<String>();
                var modelStage=new java.util.concurrent.atomic.AtomicReference<>("MODEL_DISPATCH");
                try {
                    proof.check("MODEL_RELEASE",authorize,generationContext);
                    listener.stage("REPORT_GENERATION_STARTED", report.deepCopy());
                    modelGenerationCalls.incrementAndGet();
                    modelStage.set("MODEL_STREAM");
                    ObjectNode generated = generator.generateStreaming(method, s.goal(), List.copyOf(ids), generationContext.deepCopy(), delta -> {
                        modelStage.set("STREAM_DELTA_VALIDATION");
                        ReportDelta normalized=normalizeDeltaFactRefs(delta,ids);
                        validateDelta(normalized, ids, generationContext, s.goal());
                        firstDeltaAt.compareAndSet(null,Instant.now().toString());provisionalDeltas.incrementAndGet();
                        modelStage.set("MODEL_STREAM");
                        try { proof.check("DELTA_RELEASE",authorize,normalized);listener.reportDelta(normalized); }
                        catch (RuntimeException failure) { throw failure; }
                        catch (Exception failure) { throw new ListenerFailure(failure); }
                    });
                    modelStage.set("FINAL_OUTPUT_VALIDATION");
                    normalizeGeneratedFactRefs(generated,ids);
                    validateGenerated(generated, ids, generationContext, s.goal());
                    report.set("modelSuggestion", generated.deepCopy());
                    report.put("explanationStatus","READY");
                    report.put("status",factsStatus);
                    modelDiagnostics.put("status","READY");modelDiagnostics.put("stage","COMPLETED");
                    modelDiagnostics.put("completedAt",Instant.now().toString());
                    modelDiagnostics.put("provisionalDeltaCount",provisionalDeltas.get());
                    if(firstDeltaAt.get()!=null)modelDiagnostics.put("firstDeltaAt",firstDeltaAt.get());
                    report.withArray("events").add("MODEL_SUGGESTION_COMMITTED");
                } catch (Exception failure) {
                    if(failure instanceof SecurityException || failure instanceof ScenarioEvidence.Unavailable
                            || failure instanceof java.util.concurrent.CancellationException)throw failure;
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    report.put("explanationStatus","FAILED"); report.put("status","PARTIAL");
                    boolean timedOut = causedByTimeout(failure);
                    report.put("failureCode", timedOut ? "REPORT_MODEL_TIMEOUT"
                            : causedBy(failure,"MODEL_REPORT_EMPTY") ? "MODEL_OUTPUT_EMPTY" : "REPORT_MODEL_FAILED");
                    String reason=modelFailureReason(failure),stage=modelFailureStage(failure,modelStage.get());
                    modelDiagnostics.put("status","FAILED");modelDiagnostics.put("stage",stage);
                    modelDiagnostics.put("failureReasonCode",reason);modelDiagnostics.put("completedAt",Instant.now().toString());
                    String validationDetail=modelValidationDetail(failure,reason);
                    if(validationDetail!=null)modelDiagnostics.put("validationDetail",validationDetail);
                    modelDiagnostics.set("transportObservation",modelTransportObservation(failure));
                    modelDiagnostics.put("provisionalDeltaCount",provisionalDeltas.get());
                    modelDiagnostics.put("discardedProvisionalDeltaCount",provisionalDeltas.get());
                    if(firstDeltaAt.get()!=null)modelDiagnostics.put("firstDeltaAt",firstDeltaAt.get());
                    report.withArray("events").add(provisionalDeltas.get()>0?"MODEL_DRAFT_DISCARDED":"MODEL_GENERATION_FAILED");
                    report.set("fallbackSuggestion", deterministicFallback(generationContext));
                    missing.add(timedOut
                            ? "服务准备建议生成超时，已查询的客户事实仍完整保留"
                            : "沟通重点生成失败，事实已保留，准备报告尚未完整生成");
                }
            }
            report.put("finishedAt",Instant.now().toString());
            report.withArray("events").add("FACT_QUERY_FINISHED").add("REPORT_GENERATED");
            synchronized (this) {
                current(s,run); persist(report); proof.check("PAGE_RELEASE",authorize,report);
                s.completion().finished = true;
                return report.deepCopy();
            }
        } catch (SourceCallFailure failure) {
            // A local exception does not prove whether an external HTTP request was sent.
            report.put("status",report.has("facts")?"PARTIAL":"FAILED");
            report.put("explanationStatus","NOT_RUN");
            report.put("failureCode","PREPARATION_SOURCE_CALL_FAILED");
            report.put("failureStage","PROVIDER_CALL");
            report.put("failedCapability",failure.capability);
            report.put("providerDispatch","INVOKED_SEND_UNOBSERVED");
            report.put("finishedAt",Instant.now().toString());
            report.withArray("events").add("PROVIDER_CALL_FAILED_NO_AUTOMATIC_RETRY");
            try {
                synchronized(this) {
                    current(s,run);
                    failure.proof.check("PROVIDER_FAILURE",authorize,report);
                    persist(report);
                    proof.check("PAGE_RELEASE",authorize,report);
                    return report.deepCopy();
                }
            } catch(ScenarioEvidence.Unavailable unavailable) {
                retainEvidenceFailure(report,s,unavailable);
                throw unavailable;
            } catch(SecurityException stale) {
                retainDiscarded(report,s);
                throw stale;
            }
        } catch (ScenarioEvidence.Unavailable unavailable) {
            retainEvidenceFailure(report,s,unavailable);
            throw unavailable;
        } catch (SecurityException stale) {
            retainDiscarded(report,s);
            throw stale;
        } finally {
            synchronized (this) { s.completion().finished = true; }
        }
    }
    private synchronized void retainDiscarded(ObjectNode report,Selection selection) throws Exception {
        if(selection.completion().cancelled) throw new java.util.concurrent.CancellationException("RUN_CANCELLED");
        ObjectNode discarded=report.deepCopy();
        discarded.remove(List.of("facts","execution","sourceReceipt","focusFactRefs","modelSuggestion"));
        discarded.put("status","DISCARDED");discarded.withArray("events").add("LATE_RESULT_DISCARDED");
        persist(discarded);
    }
    private static final class SourceCallFailure extends RuntimeException {
        final String capability;
        final ScenarioEvidence.Run proof;
        SourceCallFailure(String capability,ScenarioEvidence.Run proof) {
            super("PREPARATION_SOURCE_CALL_FAILED"); this.capability=capability; this.proof=proof;
        }
    }
    private CapabilityResultV1 querySource(String request,String trace,String customer,String capability,
            Map<String,Object> arguments,ScenarioEvidence.Run proof) {
        try { return access.query(request,trace,customer,capability,arguments); }
        catch(SecurityException|java.util.concurrent.CancellationException|ScenarioEvidence.Unavailable protectedFailure) {
            throw protectedFailure;
        }
        catch(RuntimeException sourceFailure) { throw new SourceCallFailure(capability,proof); }
    }
    private void retainEvidenceFailure(ObjectNode report,Selection selection,ScenarioEvidence.Unavailable unavailable) {
        report.put("status","PARTIAL");report.put("explanationStatus","FAILED");
        report.put("failureCode","SCENARIO_EVIDENCE_UNAVAILABLE");report.put("failureStage",unavailable.stage());
        report.put("providerDispatch",unavailable.providerDispatch());report.put("releaseStatus","WITHHELD");
        synchronized(this) {
            if(!selection.completion().cancelled) {
                try { persist(report); } catch(Exception storageFailure) { /* A failed store cannot promise a retained receipt. */ }
            }
        }
    }
    public synchronized ObjectNode read(String caller, String id) throws Exception {
        owner(caller); ObjectNode r = (ObjectNode)HarnessJson.mapper().readTree(Files.readAllBytes(latestPath(id)));
        releaseSaved(r);
        return r;
    }
    private void authorizeReport(ObjectNode r) {
        if (!owner.equals(r.path("ownerRef").asText())) throw new SecurityException("REPORT_OWNER_REJECTED");
        var a = access.authorize(r.path("customerRef").asText());
        Mapping m = mappings.get();
        if (m == null || !m.revision().equals(r.path("mappingRevision").asText())
                || !r.path("customerRef").asText().equals(m.customers().get(r.path("customerAlias").asText()))
                || !a.authorityRef().equals(r.path("authorityRef").asText()))
            throw new SecurityException("REPORT_AUTHORITY_CHANGED");
    }
    private void releaseSaved(ObjectNode r) {
        authorizeReport(r);
        evidence.releaseSaved(owner,r.path("customerRef").asText(),r.path("reportId").asText(),r.path("reportRevision").asInt(),
                access.authorize(r.path("customerRef").asText()),"CURRENT_CRM_EMPLOYEE_AUTHORITY",()->authorizeReport(r),r);
    }
    public synchronized ObjectNode review(String caller, String id, int revision, String opinion) throws Exception {
        ObjectNode r = read(caller,id);
        if (selected != null && (selected.generation()!=r.path("generation").asLong()
                || !id.equals(activeRun))) throw new SecurityException("STALE_REPORT");
        if (revision!=r.path("reportRevision").asInt()) throw new IllegalStateException("REPORT_VERSION_CONFLICT");
        if ("RUNNING".equals(r.path("explanationStatus").asText())) throw new IllegalStateException("REPORT_STILL_GENERATING");
        if (!Set.of("READY_FOR_REVIEW","PARTIAL","FAILED").contains(r.path("status").asText())) throw new IllegalStateException("REPORT_NOT_REVIEWABLE");
        if (opinion==null || opinion.isBlank() || opinion.length()>2000) throw new IllegalArgumentException("REVIEW_REQUIRED");
        r.put("reviewStatus","REVIEWED"); r.put("reviewerRef",access.authorize(r.path("customerRef").asText()).principalRef());
        r.put("reviewOpinion",opinion); r.put("reviewedAt",Instant.now().toString());
        var entry = r.withArray("reviewHistory").addObject();
        entry.put("revision",revision+1); entry.put("reviewerRef",r.path("reviewerRef").asText());
        entry.put("opinion",opinion); entry.put("reviewedAt",r.path("reviewedAt").asText());
        r.withArray("events").add("MANAGER_REVIEW_SAVED");
        persist(r);releaseSaved(r);return r.deepCopy(); // Review never upgrades execution or explanation status.
    }
    public synchronized ObjectNode readVersion(String caller, String id, int revision) throws Exception {
        read(caller,id); // Recheck current owner, mapping and CRM authority before historical access.
        if (revision < 1) throw new IllegalArgumentException("INVALID_REPORT_REVISION");
        Path file = reportDirectory(id).resolve(String.format("v%06d.json", revision));
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IllegalArgumentException("REPORT_VERSION_NOT_FOUND");
        ObjectNode value = (ObjectNode)HarnessJson.mapper().readTree(Files.readAllBytes(file));
        releaseSaved(value);
        return value;
    }
    public synchronized List<Integer> versions(String caller, String id) throws Exception {
        read(caller,id);
        Path directory=records.resolve(id);
        if(!Files.isDirectory(directory))return List.of(1);
        try(var files=Files.list(directory)) {
            return files.filter(Files::isRegularFile).map(p->p.getFileName().toString())
                .filter(name->name.matches("v[0-9]{6}\\.json"))
                .map(name->Integer.parseInt(name.substring(1,7))).sorted().toList();
        }
    }
    public synchronized List<Map<String,Object>> versionSummaries(String caller, String id) throws Exception {
        read(caller,id);
        var result=new ArrayList<Map<String,Object>>();
        for(int revision:versions(caller,id)) {
            ObjectNode value=readVersion(caller,id,revision);
            var item=new LinkedHashMap<String,Object>();
            item.put("revision",revision);item.put("status",value.path("status").asText());
            item.put("reviewStatus",value.path("reviewStatus").asText());
            item.put("customer",value.path("customerAlias").asText());item.put("goal",value.path("goal").asText());
            item.put("savedAt",value.path("reviewedAt").asText(value.path("finishedAt").asText(value.path("startedAt").asText())));
            item.put("reviewerRef",value.path("reviewerRef").asText());
            item.put("reviewOpinion",value.path("reviewOpinion").asText());
            result.add(Map.copyOf(item));
        }
        return List.copyOf(result);
    }
    public synchronized Optional<ObjectNode> latest(String caller) throws Exception {
        owner(caller);
        if(!Files.isDirectory(records))return Optional.empty();
        try(var paths=Files.list(records)) {
            List<Path> candidates=paths.filter(Files::isDirectory).filter(path->!Files.isSymbolicLink(path))
                    .filter(path->path.getFileName().toString().matches("[0-9a-f-]{36}"))
                    .sorted((left,right)->Long.compare(lastModified(right),lastModified(left))).limit(1000).toList();
            for(Path candidate:candidates) {
                String id=candidate.getFileName().toString();
                try {
                    ObjectNode value=read(caller,id);
                    if(owner.equals(value.path("ownerRef").asText()))return Optional.of(value);
                } catch(SecurityException|IllegalArgumentException ignored) {
                    // Reports outside the current authority are intentionally undiscoverable.
                }
            }
        }
        return Optional.empty();
    }
    public synchronized ObjectNode replay(String caller,String id,Integer revision) throws Exception {
        long providerBefore=businessProviderCalls.get(),modelBefore=modelGenerationCalls.get();
        ObjectNode report=revision==null?read(caller,id):readVersion(caller,id,revision);
        ObjectNode result=HarnessJson.mapper().createObjectNode();result.set("report",report.deepCopy());
        ObjectNode evidence=result.putObject("replayEvidence");
        evidence.put("reportId",id);evidence.put("revision",report.path("reportRevision").asInt());
        evidence.put("businessFactProviderCalls",businessProviderCalls.get()-providerBefore);
        evidence.put("modelGenerationCalls",modelGenerationCalls.get()-modelBefore);
        evidence.put("authorizationRevalidated",true);
        evidence.put("coverage","SERVICE_PREPARATION_BUSINESS_QUERY_AND_MODEL_BOUNDARIES");
        evidence.put("recordedAt",Instant.now().toString());
        return result;
    }
    public synchronized ObjectNode note(String caller,String id,Integer revision) throws Exception {
        long providerBefore=businessProviderCalls.get(),modelBefore=modelGenerationCalls.get();
        ObjectNode report=revision==null?read(caller,id):readVersion(caller,id,revision);
        String markdown=renderNote(report);
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("schemaVersion","manager-note.v0.1");
        result.put("reportId",id);result.put("reportRevision",report.path("reportRevision").asInt());
        result.put("fileName","customer-preparation-"+id.substring(0,8)+"-v"+report.path("reportRevision").asInt()+".md");
        result.put("mediaType","text/markdown; charset=utf-8");result.put("content",markdown);
        byte[] bytes=markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        result.put("byteLength",bytes.length);result.put("sha256",hash(bytes));
        result.put("generatedAt",Instant.now().toString());result.put("displayTimeZone","Asia/Shanghai");
        result.put("businessWriteCalls",0);
        result.put("businessFactProviderCalls",businessProviderCalls.get()-providerBefore);
        result.put("modelGenerationCalls",modelGenerationCalls.get()-modelBefore);
        return result;
    }
    private Path path(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("INVALID_REPORT_ID");
        Path p = records.resolve(id+".json");
        if (Files.isSymbolicLink(p)) throw new SecurityException("UNSAFE_REPORT_FILE");
        return p;
    }
    private Path reportDirectory(String id) throws Exception {
        path(id); Path directory = records.resolve(id);
        if (Files.isSymbolicLink(directory)) throw new SecurityException("UNSAFE_REPORT_DIRECTORY");
        Files.createDirectories(directory);
        return directory;
    }
    private Path latestPath(String id) throws Exception {
        Path directory = records.resolve(id);
        if (Files.isDirectory(directory) && !Files.isSymbolicLink(directory)) {
            Path pointer = directory.resolve("latest.json");
            JsonNode latest = HarnessJson.mapper().readTree(Files.readAllBytes(pointer));
            int revision = latest.path("revision").asInt();
            Path version = directory.resolve(String.format("v%06d.json", revision));
            byte[] bytes = Files.readAllBytes(version);
            if (!latest.path("digest").asText().equals(hash(bytes))) throw new SecurityException("REPORT_VERSION_DIGEST_MISMATCH");
            return version;
        }
        Path legacy=path(id);
        if(!Files.isRegularFile(legacy))throw new IllegalArgumentException("REPORT_NOT_FOUND");
        return legacy; // Historical flat-file reports remain readable.
    }
    private void persist(ObjectNode r) throws Exception {
        int next = r.path("reportRevision").asInt() + 1;
        r.put("reportRevision", next);
        byte[] bytes = HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(r);
        if (bytes.length>1024*1024) throw new IllegalStateException("REPORT_TOO_LARGE");
        Path directory=reportDirectory(r.path("reportId").asText());
        Path target=directory.resolve(String.format("v%06d.json", next));
        if (Files.exists(target)) throw new IllegalStateException("REPORT_VERSION_EXISTS");
        Path tmp=Files.createTempFile(records,"report-",".tmp");
        try {
            Files.write(tmp,bytes);Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);
            byte[] pointer=HarnessJson.mapper().writeValueAsBytes(Map.of("revision",next,"digest",hash(bytes)));
            Path pointerTmp=Files.createTempFile(records,"latest-",".tmp");
            try { Files.write(pointerTmp,pointer);Files.move(pointerTmp,directory.resolve("latest.json"),
                    StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(pointerTmp); }
        }
        finally { Files.deleteIfExists(tmp); }
    }
    private static String hash(byte[] bytes) throws Exception {
        return "sha256:"+HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static long lastModified(Path value) {
        try{return Files.getLastModifiedTime(value).toMillis();}catch(Exception ignored){return Long.MIN_VALUE;}
    }
    static ObjectNode assessFacts(String goal,JsonNode facts,JsonNode productReview) {
        ObjectNode result=HarnessJson.mapper().createObjectNode();var available=result.putArray("available");
        var missing=result.putArray("missing");
        if(!facts.path("customer").path("displayName").asText().isBlank()
                &&!facts.path("customer").path("customerStatus").asText().isBlank())
            addAvailable(available,"CUSTOMER_IDENTITY","客户身份与状态",List.of("/customer/displayName","/customer/customerStatus"));
        else addMissing(missing,"CUSTOMER_IDENTITY_NOT_PROVIDED","未取得完整客户身份与状态","不能形成可复核的客户服务准备记录");
        if(!facts.path("customer").path("maskedPhone").asText().isBlank())
            addAvailable(available,"CONTACT_CHANNEL","脱敏联系方式",List.of("/customer/maskedPhone"));
        JsonNode wealth=findProfile(facts,"wealth"),insurance=findProfile(facts,"life_insurance");
        JsonNode detail=wealth.path("detail");
        boolean assets=hasAny(detail,List.of("totalAssets","portfolio","holdings","wealthAggregate"));
        boolean risk=hasPath(detail,"riskAssessment/riskLevel");
        boolean recent=hasAny(detail,List.of("recentEvents","recentMatters","recentContactSummary","maturities"));
        if(assets)addAvailable(available,"WEALTH_POSITION","财富资产或持仓资料",List.of(profilePath(facts,"wealth")+"/detail"));
        else addMissing(missing,"WEALTH_POSITION_NOT_PROVIDED","未取得财富资产或持仓明细","无法据此判断具体资产配置或到期情况");
        if(risk)addAvailable(available,"RISK_ASSESSMENT","风险测评资料",List.of(profilePath(facts,"wealth")+"/detail/riskAssessment"));
        else addMissing(missing,"RISK_ASSESSMENT_NOT_PROVIDED","未取得风险测评资料","不能据此形成产品适当性结论");
        if(recent)addAvailable(available,"RECENT_EVENTS","近期联系、事项或到期事件",List.of(profilePath(facts,"wealth")+"/detail"));
        else addMissing(missing,"RECENT_EVENTS_NOT_PROVIDED","未取得近期联系、事项或到期事件","回访触发原因仍需经理确认");
        if(!insurance.isMissingNode()&&!insurance.isNull())
            addAvailable(available,"INSURANCE_PROFILE","存在保险领域档案",List.of(profilePath(facts,"life_insurance")+"/profileType"));
        if(productReview!=null&&productReview.isObject()&&!productReview.isEmpty()) {
            addAvailable(available,"PRODUCT_POLICY_RESULT","产品目录与适当性规则筛选结果",List.of("/productReview/policySource/ruleVersion","/productReview/policySource/catalogVersion"));
            for(String code:List.of("WEALTH_POSITION_NOT_PROVIDED","RISK_ASSESSMENT_NOT_PROVIDED","RECENT_EVENTS_NOT_PROVIDED"))removeMissing(missing,code);
        }
        result.put("goal",goal);result.put("status",missing.isEmpty()?"READY":"PARTIAL");
        return result;
    }
    static ObjectNode factState(JsonNode facts,JsonNode taskContext,JsonNode receipt,Instant evaluatedAt) {
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("schemaVersion","service-preparation-fact-state.v0.1");
        var items=result.putArray("items");
        boolean receiptVerified=!receipt.path("receiptId").asText().isBlank();
        addFactState(items,"customer.identity",facts.path("customer"),"SYSTEM_RECORD",
                receipt.path("providerId").asText("customer-crm-readonly"),receiptVerified?"VERIFIED":"UNVERIFIED",
                facts.path("customer").isObject()?"PRESENT":"UNKNOWN","CURRENT",facts.path("dataAsOf").asText(""));
        JsonNode wealth=findProfile(facts,"wealth"),detail=wealth.path("detail");
        addFactState(items,"wealth.portfolio",detail.path("portfolioSummary"),"SYSTEM_RECORD",
                "customer-crm-readonly",receiptVerified?"VERIFIED":"UNVERIFIED",
                detail.path("portfolioSummary").isObject()?"PRESENT":"UNKNOWN","CURRENT",facts.path("dataAsOf").asText(""));
        JsonNode risk=detail.path("riskAssessment");String validity="UNKNOWN";
        String validUntil=risk.path("validUntil").asText("");
        try { if(!validUntil.isBlank())validity=LocalDate.parse(validUntil.substring(0,Math.min(10,validUntil.length())))
                .isBefore(evaluatedAt.atZone(ZoneOffset.UTC).toLocalDate())?"EXPIRED":"CURRENT"; }
        catch(java.time.format.DateTimeParseException ignored){validity="UNKNOWN";}
        addFactState(items,"wealth.riskAssessment",risk,"SYSTEM_RECORD","customer-crm-readonly",
                receiptVerified?"VERIFIED":"UNVERIFIED",risk.isObject()?"PRESENT":"UNKNOWN",validity,validUntil);
        taskContext.path("facts").forEach(value->items.add(value.deepCopy()));
        return result;
    }
    private static void addFactState(com.fasterxml.jackson.databind.node.ArrayNode items,String key,JsonNode value,
            String sourceKind,String sourceRef,String verification,String completeness,String validity,String recordedAt) {
        ObjectNode item=items.addObject();item.put("key",key);item.set("value",value.deepCopy());
        ObjectNode source=item.putObject("source");source.put("kind",sourceKind);source.put("ref",sourceRef);
        if(recordedAt!=null&&!recordedAt.isBlank())source.put("recordedAt",recordedAt);
        item.putObject("verification").put("status",verification);
        item.putObject("completeness").put("status",completeness);
        item.putObject("validity").put("status",validity);
    }
    private static com.fasterxml.jackson.databind.node.ArrayNode clarificationQuestions(JsonNode assessment,JsonNode taskContext) {
        var result=HarnessJson.mapper().createArrayNode();
        for(JsonNode item:taskContext.path("questions"))if(result.size()<3)result.add(item.asText());
        for(JsonNode item:assessment.path("missing"))if(result.size()<3)result.add(item.path("label").asText()+"："+item.path("impact").asText());
        return result;
    }
    private static String renderNote(JsonNode report) {
        StringBuilder out=new StringBuilder("# 客户回访准备笔记\n\n");
        out.append("- 客户：").append(display(report.path("facts").path("customer").path("displayName").asText(report.path("customerAlias").asText()))).append("\n");
        out.append("- 目标：").append(report.path("goal").asText()).append("\n");
        out.append("- 报告版本：v").append(report.path("reportRevision").asInt()).append("\n");
        out.append("- 业务数据截至：").append(report.path("dataAsOf").asText("待核实")).append("\n");
        out.append("- 生成记录：").append(report.path("finishedAt").asText(report.path("startedAt").asText("待核实"))).append("（UTC，页面按北京时间展示）\n");
        out.append("- 状态：").append(report.path("reviewStatus").asText("PENDING")).append("\n\n");
        out.append("## 事实与状态\n\n");
        for(JsonNode item:report.path("factState").path("items"))out.append("- ").append(item.path("key").asText()).append("：")
                .append(item.path("value").isValueNode()?item.path("value").asText():item.path("value").toString())
                .append("；来源 ").append(item.path("source").path("kind").asText("UNKNOWN"))
                .append("；核验 ").append(item.path("verification").path("status").asText("UNVERIFIED"))
                .append("；完整性 ").append(item.path("completeness").path("status").asText("UNKNOWN"))
                .append("；有效性 ").append(item.path("validity").path("status").asText("UNKNOWN")).append("\n");
        JsonNode suggestion=report.has("modelSuggestion")?report.path("modelSuggestion"):report.path("fallbackSuggestion");
        appendNoteSection(out,"沟通重点",suggestion.path("communicationFocus"),true);
        appendNoteSection(out,"准备建议",suggestion.path("preparationAdvice"),true);
        appendNoteSection(out,"关键追问",report.path("clarificationQuestions"),false);
        appendNoteSection(out,"待办草稿（尚未写入 CRM）",suggestion.path("pendingItems"),false);
        out.append("\n## 依据\n\n- Provider：").append(report.path("sourceReceipt").path("providerId").asText("待核实"));
        out.append("\n- 方法：").append(report.path("method").path("id").asText("待核实")).append(" ")
                .append(report.path("method").path("version").asText()).append("\n- 方法摘要：")
                .append(report.path("method").path("digest").asText("待核实")).append("\n");
        return out.toString();
    }
    private static void appendNoteSection(StringBuilder out,String title,JsonNode values,boolean objects) {
        out.append("\n## ").append(title).append("\n\n");
        if(!values.isArray()||values.isEmpty()){out.append("- 暂无\n");return;}
        for(JsonNode value:values){JsonNode text=objects?value.path("text"):value;out.append("- ").append(text.asText()).append("\n");
            if(objects&&value.path("factRefs").isArray())out.append("  - 事实引用：").append(jsonStrings(value.path("factRefs"))).append("\n");}
    }
    private static String display(String value){return value.startsWith("合成客户")?value.substring(4):value;}
    static ObjectNode presentFacts(JsonNode facts,Instant evaluatedAt) {
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("evaluatedAt",evaluatedAt.toString());
        String asOf=facts.path("dataAsOf").asText("");result.put("dataAsOf",asOf);
        if(!asOf.isBlank())try {
            long age=Math.max(0,ChronoUnit.DAYS.between(Instant.parse(asOf),evaluatedAt));
            result.put("ageDays",age);result.put("freshnessStatus",age==0?"CURRENT_DAY":"HISTORICAL_SNAPSHOT");
        } catch(java.time.format.DateTimeParseException invalid) { result.put("freshnessStatus","DATE_UNPARSEABLE"); }
        else result.put("freshnessStatus","DATE_MISSING");
        JsonNode wealth=findProfile(facts,"wealth"),detail=wealth.path("detail");
        JsonNode portfolio=detail.path("portfolioSummary");
        if(portfolio.isObject()||detail.has("totalAssets")||detail.has("availableCash")||detail.has("investedAssets")) {
            ObjectNode summary=result.putObject("portfolio");
            for(String field:List.of("totalAssets","availableCash","investedAssets","dataAsOf"))
                if(portfolio.has(field))summary.set(field,portfolio.path(field).deepCopy());
                else if(detail.has(field))summary.set(field,detail.path(field).deepCopy());
        }
        JsonNode risk=detail.path("riskAssessment");
        if(risk.isObject()) {
            ObjectNode summary=result.putObject("riskAssessment");
            for(String field:List.of("riskLevel","riskPreference","validUntil"))
                if(risk.has(field))summary.set(field,risk.path(field).deepCopy());
        }
        var holdings=result.putArray("holdings");
        detail.path("holdings").forEach(value->copyDisplayFact(holdings.addObject(),value,
                List.of("productName","category","balance","status","maturityDate")));
        var maturities=result.putArray("maturities");LocalDate today=evaluatedAt.atZone(ZoneOffset.UTC).toLocalDate();
        detail.path("maturities").forEach(value->{
            ObjectNode item=maturities.addObject();copyDisplayFact(item,value,
                    List.of("productName","amount","status","maturityDate"));
            String date=value.path("maturityDate").asText("");
            try {
                if(!date.isBlank()&&LocalDate.parse(date).isBefore(today)) {
                    item.put("verificationStatus","PAST_DATE_STATUS_UNVERIFIED");
                    item.put("effectiveStatus","PAST_DUE_UNVERIFIED");
                    item.put("warning","到期日已过，实际兑付状态和资金去向待核实");
                } else {
                    item.put("verificationStatus","SOURCE_STATUS_CURRENTLY_UNVERIFIED");
                    item.put("effectiveStatus","NOT_PAST_DUE");
                }
            } catch(java.time.format.DateTimeParseException invalid) {
                item.put("verificationStatus","DATE_UNPARSEABLE");item.put("effectiveStatus","DATE_UNPARSEABLE");
                item.put("warning","到期日期无法核验");
            }
        });
        return result;
    }
    private static ObjectNode temporalAssessment(JsonNode presentation) {
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.set("evaluatedAt",presentation.path("evaluatedAt").deepCopy());
        var maturities=result.putArray("maturities");
        for(int i=0;i<presentation.path("maturities").size();i++) {
            JsonNode source=presentation.path("maturities").path(i);ObjectNode item=maturities.addObject();
            for(String field:List.of("productName","maturityDate","status","effectiveStatus","verificationStatus","warning"))
                if(source.has(field))item.set(field,source.path(field).deepCopy());
        }
        return result;
    }
    private static void copyDisplayFact(ObjectNode target,JsonNode source,List<String> fields) {
        for(String field:fields)if(source.has(field))target.set(field,source.path(field).deepCopy());
    }
    private static JsonNode findProfile(JsonNode facts,String type) {
        for(JsonNode profile:facts.path("profiles"))if(type.equals(profile.path("profileType").asText()))return profile;
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }
    private static String profilePath(JsonNode facts,String type) {
        for(int i=0;i<facts.path("profiles").size();i++)if(type.equals(facts.path("profiles").path(i).path("profileType").asText()))return "/profiles/"+i;
        return "/profiles";
    }
    private static boolean hasAny(JsonNode node,List<String> names) {
        for(String name:names)if(node.has(name)&&!node.path(name).isNull()&&!node.path(name).isMissingNode())return true;return false;
    }
    private static boolean hasPath(JsonNode node,String path) {
        JsonNode current=node;for(String part:path.split("/"))current=current.path(part);return !current.isMissingNode()&&!current.isNull()&&!current.asText("").isBlank();
    }
    private static void addAvailable(com.fasterxml.jackson.databind.node.ArrayNode values,String code,String label,List<String> refs) {
        ObjectNode item=values.addObject();item.put("code",code);item.put("label",label);item.set("factRefs",HarnessJson.mapper().valueToTree(refs));
    }
    private static void addMissing(com.fasterxml.jackson.databind.node.ArrayNode values,String code,String label,String impact) {
        ObjectNode item=values.addObject();item.put("code",code);item.put("label",label);item.put("impact",impact);item.put("state","NOT_PROVIDED");
    }
    private static void removeMissing(com.fasterxml.jackson.databind.node.ArrayNode values,String code) {
        for(int i=values.size()-1;i>=0;i--)if(code.equals(values.path(i).path("code").asText()))values.remove(i);
    }
    private static ReportGenerator legacy(Explanation explanation) {
        Objects.requireNonNull(explanation);
        return (method, goal, ids, context) -> {
            List<String> focus = explanation.selectFocus(goal, ids, context);
            ObjectNode generated = HarnessJson.mapper().createObjectNode();
            var points = generated.putArray("communicationFocus");
            if (focus != null) for (String ref : focus) {
                var point=points.addObject();point.put("text","请经理结合该事实准备沟通");point.putArray("factRefs").add(ref);
            }
            var advice=generated.putArray("preparationAdvice");
            if(focus!=null&&!focus.isEmpty()) {
                var item=advice.addObject();item.put("text","先核对已取得事实，再确认本次服务目标");
                item.putArray("factRefs").add(focus.getFirst());
            }
            generated.putArray("pendingItems");
            generated.putArray("reviewChecklist").add("核对事实来源和业务日期");
            return generated;
        };
    }
    private static void validateGenerated(ObjectNode generated, List<String> ids, JsonNode facts, String goal) {
        if (generated == null || !generated.fieldNames().hasNext()) throw new IllegalArgumentException("MODEL_REPORT_EMPTY");
        Set<String> expected=Set.of("communicationFocus","preparationAdvice","pendingItems","reviewChecklist");
        Set<String> actual=new HashSet<>();generated.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
        for(String section:List.of("communicationFocus","preparationAdvice")) {
            JsonNode values=generated.path(section);if(!values.isArray()||values.isEmpty()||values.size()>8)
                throw new IllegalArgumentException("MODEL_REPORT_EMPTY");
            for(JsonNode value:values) {
                if(!value.isObject()||value.path("text").asText().isBlank()||value.path("text").asText().length()>500
                        ||!value.path("factRefs").isArray()||value.path("factRefs").isEmpty())
                    throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
                Set<String> refs=new HashSet<>();value.path("factRefs").forEach(refsNode->refs.add(refsNode.asText()));
                if(!ids.containsAll(refs))throw unknownFactRef(refs,ids);
            }
        }
        for(String section:List.of("pendingItems","reviewChecklist")) {
            JsonNode values=generated.path(section);if(!values.isArray()||values.size()>12)throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
            for(JsonNode value:values)if(!value.isTextual()||value.asText().isBlank()||value.asText().length()>500)
                throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
        }
        String allowed=facts.toString()+" "+goal;
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?").matcher(generated.toString());
        while(matcher.find())if(!allowed.contains(matcher.group()))throw new IllegalArgumentException("UNSUPPORTED_NUMBER");
        validateProductSemantics(generated.toString(),facts);
        validateTemporalSemantics(generated.toString(),facts);
        validateProvenanceSemantics(generated.toString(),facts);
    }
    private static void validateDelta(ReportDelta delta, List<String> ids, JsonNode facts, String goal) {
        if (!Set.of("communicationFocus","preparationAdvice","pendingItems","reviewChecklist").contains(delta.section())
                || delta.text()==null || delta.text().isBlank() || delta.text().length()>500)
            throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
        boolean evidenced=Set.of("communicationFocus","preparationAdvice").contains(delta.section());
        if (evidenced && (delta.factRefs().isEmpty() || !ids.containsAll(delta.factRefs()))
                || !evidenced && !delta.factRefs().isEmpty())
            throw unknownFactRef(new LinkedHashSet<>(delta.factRefs()),ids);
        String allowed=facts.toString()+" "+goal;
        var matcher=java.util.regex.Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?").matcher(delta.text());
        while(matcher.find())if(!allowed.contains(matcher.group()))throw new IllegalArgumentException("UNSUPPORTED_NUMBER");
        validateProductSemantics(delta.text(),facts);
        validateTemporalSemantics(delta.text(),facts);
        validateProvenanceSemantics(delta.text(),facts);
    }
    static void validateProductSemantics(String text, JsonNode facts) {
        if(facts.path("productReview").path("eligibleProducts").isEmpty())return;
        String compact=text.replaceAll("\\s+","");
        for(String conflict:List.of("没有同时满足","不存在同时满足","无可推荐合格产品","没有合格候选","无合格候选"))
            if(compact.contains(conflict))throw new IllegalArgumentException("PRODUCT_ELIGIBILITY_CONFLICT");
    }
    static void validateTemporalSemantics(String text,JsonNode facts) {
        String compact=text.replaceAll("\\s+","");
        JsonNode maturities=facts.path("temporalAssessment").path("maturities");
        boolean future=false;
        for(JsonNode item:maturities)
            if("NOT_PAST_DUE".equals(item.path("effectiveStatus").asText()))future=true;
        if(future||maturities.isEmpty())return;
        for(String marker:List.of("即将到期","临近到期","快到期")) {
            for(int at=compact.indexOf(marker);at>=0;at=compact.indexOf(marker,at+marker.length()))
                if(!negatedTemporalMention(compact,at))throw new IllegalArgumentException("TEMPORAL_STATUS_CONFLICT:"+marker);
        }
    }
    static void validateProvenanceSemantics(String text,JsonNode facts) {
        String compact=text.replaceAll("\\s+","");
        for(JsonNode fact:facts.path("taskContext").path("facts")) {
            if(!"DETERMINISTIC_DERIVATION".equals(fact.path("source").path("kind").asText()))continue;
            for(String rendered:renderedNumbers(fact.path("value"))) {
                for(String clause:compact.split("[。；;！!？?，,]")) {
                    if(!clause.contains(rendered)||!mentionsDirectStatement(clause))continue;
                    if(!mentionsDerivation(clause))throw new IllegalArgumentException("FACT_PROVENANCE_CONFLICT:"+fact.path("key").asText());
                }
            }
        }
    }
    private static List<String> renderedNumbers(JsonNode value) {
        if(!value.isNumber())return List.of();
        double number=value.asDouble();var values=new LinkedHashSet<String>();
        if(number==Math.rint(number))values.add(Long.toString((long)number));else values.add(Double.toString(number));
        if(number%10000==0)values.add(Long.toString((long)(number/10000))+"万");
        return List.copyOf(values);
    }
    private static boolean mentionsDirectStatement(String clause) {
        return clause.contains("客户口述")||clause.contains("客户原话")||clause.contains("客户直接提供")
                ||clause.contains("客户直接说明")||clause.contains("均为口述")||clause.contains("均由客户提供");
    }
    private static boolean mentionsDerivation(String clause) {
        return clause.contains("推导")||clause.contains("计算")||clause.contains("相减")||clause.contains("据此得出")
                ||clause.contains("由口述得出")||clause.contains("基于口述得出");
    }
    private static boolean negatedTemporalMention(String text,int markerAt) {
        int boundary=-1;
        for(char value:new char[]{'。','；',';','！','!','？','?','，',','})boundary=Math.max(boundary,text.lastIndexOf(value,markerAt-1));
        String prefix=text.substring(boundary+1,markerAt);
        for(String denial:List.of("不得表述为","不能表述为","不可表述为","不应表述为","不要表述为","避免表述为",
                "避免误述为","避免误写为","避免误称为","避免说成",
                "不得描述为","不能描述为","不可描述为","不得称为","不能称为","不可称为","不要说成",
                "不是","并非","并不是","不属于","不应","不可","不能","不得","禁止","严禁"))
            if(prefix.endsWith(denial))return true;
        if(prefix.matches(".*避免(?:将.{0,24})?误(?:述|写|称)为$"))return true;
        return false;
    }
    private static ReportDelta normalizeDeltaFactRefs(ReportDelta delta,List<String> ids) {
        return new ReportDelta(delta.section(),delta.text(),normalizeFactRefs(delta.factRefs(),ids));
    }
    private static void normalizeGeneratedFactRefs(ObjectNode generated,List<String> ids) {
        for(String section:List.of("communicationFocus","preparationAdvice"))
            for(JsonNode value:generated.path(section))if(value instanceof ObjectNode item&&item.path("factRefs").isArray())
                item.set("factRefs",HarnessJson.mapper().valueToTree(normalizeFactRefs(jsonStrings(item.path("factRefs")),ids)));
    }
    static List<String> normalizeFactRefs(List<String> refs,List<String> ids) {
        var normalized=new LinkedHashSet<String>();
        for(String ref:refs) {
            if(ids.contains(ref)){normalized.add(ref);continue;}
            if(!safeAggregateRef(ref)){normalized.add(ref);continue;}
            String prefix=ref+"/";
            List<String> leaves=ids.stream().filter(value->value.startsWith(prefix)).sorted().toList();
            if(leaves.isEmpty())normalized.add(ref);else normalized.addAll(leaves);
        }
        return List.copyOf(normalized);
    }
    private static boolean safeAggregateRef(String ref) {
        return ref.matches("/taskContext/facts/[0-9]+")
                ||ref.matches("/profiles/[0-9]+/detail/(portfolioSummary|riskAssessment)")
                ||ref.matches("/temporalAssessment/maturities/[0-9]+");
    }
    private static IllegalArgumentException unknownFactRef(Set<String> refs,List<String> ids) {
        List<String> unknown=refs.stream().filter(ref->!ids.contains(ref)).sorted().limit(8).toList();
        return new IllegalArgumentException("UNKNOWN_FACT_REF:"+String.join(",",unknown)+";allowed="+ids.size());
    }
    private static ObjectNode deterministicFallback(JsonNode facts) {
        ObjectNode fallback=HarnessJson.mapper().createObjectNode();
        var focus=fallback.putArray("communicationFocus");var advice=fallback.putArray("preparationAdvice");
        var pending=fallback.putArray("pendingItems");var checklist=fallback.putArray("reviewChecklist");
        String name=facts.at("/customer/displayName").asText("该客户");
        if(name.startsWith("合成客户")&&name.length()>4)name=name.substring(4);
        String status=facts.at("/customer/customerStatus").asText("");
        String statusLabel="active".equalsIgnoreCase(status)?"有效":status;
        var first=focus.addObject();first.put("text",status.isBlank()
                ? "本次服务对象为"+name+"，请先确认本次回访目标。"
                : "本次服务对象为"+name+"，当前客户状态为"+statusLabel+"，请先确认本次回访目标。");
        var firstRefs=first.putArray("factRefs");firstRefs.add("/customer/displayName");
        if(!status.isBlank())firstRefs.add("/customer/customerStatus");
        JsonNode products=facts.path("productReview").path("eligibleProducts");
        if(products.isArray()&&!products.isEmpty()) {
            var product=focus.addObject();product.put("text","当前规则筛选得到"+products.size()+"个待经理复核的产品候选，候选资格以产品卡片和规则版本为准。");
            var refs=product.putArray("factRefs");for(int i=0;i<products.size();i++)refs.add("/productReview/eligibleProducts/"+i+"/name");
            var prepare=advice.addObject();prepare.put("text","与客户沟通前，请复核候选产品的期限、风险等级、最低金额和办理渠道，不将待复核候选表述为购买建议。");
            prepare.putArray("factRefs").add("/productReview/policySource/ruleVersion");
        } else {
            var prepare=advice.addObject();prepare.put("text","回访前请结合已取得的客户资料，确认服务目标、缺失信息和需要进一步授权查询的事项。");
            prepare.putArray("factRefs").add("/customer/displayName");
        }
        pending.add("自然语言建议未完整生成，请经理依据已核验事实继续复核。");
        checklist.add("核对客户、事实来源、业务日期和本次服务目标。");
        checklist.add("确认页面中的内容未被当作CRM写入、购买建议或交易指令。");
        return fallback;
    }
    private static List<String> jsonStrings(JsonNode values) {
        var result=new ArrayList<String>(); values.forEach(value->result.add(value.asText())); return List.copyOf(result);
    }
    private static void collectFactRefs(JsonNode node, String path, List<String> refs, int limit) {
        if (refs.size() >= limit || node == null || node.isNull()) return;
        if (node.isValueNode()) {
            if (!path.isBlank()) refs.add(path);
            return;
        }
        if (node.isArray()) {
            for (int i=0; i<node.size() && refs.size()<limit; i++) collectFactRefs(node.path(i), path+"/"+i, refs, limit);
            return;
        }
        node.fields().forEachRemaining(entry -> {
            if (refs.size() < limit) collectFactRefs(entry.getValue(), path+"/"+entry.getKey().replace("~","~0").replace("/","~1"), refs, limit);
        });
    }
    private static final class ListenerFailure extends RuntimeException {
        ListenerFailure(Exception cause) { super(cause); }
    }
    private static boolean causedByTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String type = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT);
            if (type.contains("timeout") || message.contains("timeout") || message.contains("timed out")) return true;
        }
        return false;
    }
    private static boolean causedBy(Throwable failure,String marker) {
        for(Throwable current=failure;current!=null;current=current.getCause())
            if(String.valueOf(current.getMessage()).contains(marker))return true;
        return false;
    }
    private static String modelFailureReason(Throwable failure) {
        if(causedByTimeout(failure))return "MODEL_TIMEOUT";
        for(String marker:List.of("UNKNOWN_FACT_REF","UNSUPPORTED_NUMBER","PRODUCT_ELIGIBILITY_CONFLICT","TEMPORAL_STATUS_CONFLICT",
                "MODEL_REPORT_EMPTY","MODEL_REPORT_SHAPE_INVALID","INVALID_REPORT_FACT_REF",
                "INVALID_REPORT_STREAM_SECTION","INVALID_REPORT_STREAM_ITEM","INVALID_REPORT_SECTION",
                "INVALID_REPORT_ITEM","INVALID_REPORT_OBJECT"))if(causedBy(failure,marker))return marker;
        if(failure instanceof ListenerFailure||causedBy(failure,"ListenerFailure"))return "STREAM_DELIVERY_FAILED";
        return "MODEL_PROVIDER_OR_RUNTIME_FAILED";
    }
    static ObjectNode modelTransportObservation(Throwable failure) {
        ObjectNode result=HarnessJson.mapper().createObjectNode();
        result.put("schemaVersion","model-transport-observation.v0.1");
        result.put("terminationReason",transportTerminationReason(failure));
        result.put("threadInterruptedObserved",Thread.currentThread().isInterrupted()||hasCauseType(failure,"InterruptedException"));
        result.put("cancellationSource",hasCauseType(failure,"CancellationException")?"EXCEPTION_CHAIN":
                hasCauseType(failure,"InterruptedException")||Thread.currentThread().isInterrupted()?"THREAD_INTERRUPT_SIGNAL":"NOT_OBSERVED");
        result.put("httpStatusObserved",false);result.put("upstreamRequestIdObserved",false);
        result.put("httpSendCountObserved",false);result.put("downstreamDisconnectObserved",false);
        result.put("downstreamDeliveryFailureObserved",failure instanceof ListenerFailure||hasCauseType(failure,"ListenerFailure"));
        var causes=result.putArray("causeChain");
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&causes.size()<8&&seen.add(current);current=current.getCause()){
            ObjectNode item=causes.addObject();String type=current.getClass().getSimpleName();
            item.put("type",type.matches("[A-Za-z0-9_$]{1,96}")?type:"Throwable");
            item.put("category",transportCategory(type));
        }
        return result;
    }
    private static String transportTerminationReason(Throwable failure) {
        if(hasCauseType(failure,"SocketTimeoutException"))return "READ_TIMEOUT";
        if(hasCauseType(failure,"TimeoutException"))return "TIMEOUT_SOURCE_UNSPECIFIED";
        if(hasCauseType(failure,"CancellationException"))return "EXPLICIT_CANCELLATION";
        if(hasCauseType(failure,"InterruptedException"))return "THREAD_INTERRUPTED";
        if(hasCauseType(failure,"JsonProcessingException")||hasCauseType(failure,"JsonParseException"))return "MALFORMED_STREAM_PAYLOAD";
        if(hasCauseType(failure,"EOFException"))return "UNEXPECTED_EOF";
        if(hasCauseType(failure,"SocketException"))return "SOCKET_FAILURE";
        if(hasCauseType(failure,"IOException"))return "IO_FAILURE";
        return "UNKNOWN_RUNTIME_FAILURE";
    }
    private static boolean hasCauseType(Throwable failure,String simpleName){
        Set<Throwable> seen=Collections.newSetFromMap(new IdentityHashMap<>());
        for(Throwable current=failure;current!=null&&seen.add(current);current=current.getCause())if(simpleName.equals(current.getClass().getSimpleName()))return true;
        return false;
    }
    private static String transportCategory(String type){
        if(type.contains("Timeout"))return "TIMEOUT";if(type.contains("Interrupt"))return "INTERRUPTION";
        if(type.contains("Cancel"))return "CANCELLATION";if(type.contains("Socket")||type.contains("EOF")||type.contains("IO"))return "IO";
        if(type.contains("Json")||type.contains("Parse"))return "PARSE";return "RUNTIME";
    }
    private static String modelFailureStage(Throwable failure,String current) {
        String reason=modelFailureReason(failure);
        if("MODEL_TIMEOUT".equals(reason)||"MODEL_PROVIDER_OR_RUNTIME_FAILED".equals(reason))return "MODEL_TRANSPORT";
        if(reason.startsWith("INVALID_REPORT_"))return "MODEL_OUTPUT_PARSE";
        if(Set.of("UNKNOWN_FACT_REF","UNSUPPORTED_NUMBER","PRODUCT_ELIGIBILITY_CONFLICT","TEMPORAL_STATUS_CONFLICT",
                "MODEL_REPORT_EMPTY","MODEL_REPORT_SHAPE_INVALID").contains(reason))return "FINAL_OUTPUT_VALIDATION";
        if("STREAM_DELIVERY_FAILED".equals(reason))return "STREAM_DELIVERY";
        return current==null?"UNKNOWN":current;
    }
    private static String modelValidationDetail(Throwable failure,String reason) {
        if(!Set.of("UNKNOWN_FACT_REF","UNSUPPORTED_NUMBER","PRODUCT_ELIGIBILITY_CONFLICT","TEMPORAL_STATUS_CONFLICT")
                .contains(reason))return null;
        for(Throwable current=failure;current!=null;current=current.getCause()) {
            String message=current.getMessage();
            if(message!=null&&message.contains(reason))return message.length()>600?message.substring(0,600):message;
        }
        return reason;
    }
    @Override public synchronized void close() { closed=true;generation++;selected=null;activeRun=null;access.close(); }
}
