package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentWealthSession;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import io.github.bitlhk.finance.harness.providers.employeeagent.EmployeeAgentWealthProvider;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
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
    private record Selection(long generation, String alias, String customer, String goal,
                             Mapping mapping, EmployeeAgentWealthSession.Authority authority) {}
    private final String owner;
    private final EmployeeAgentWealthSession session;
    private final Supplier<Mapping> mappings;
    private final Explanation explanation;
    private final Path records;
    private final Map<String,String> versions;
    private long generation;
    private Selection selected;
    private String activeRun;
    private boolean closed;

    public ServicePreparation(String owner, EmployeeAgentWealthSession session, Supplier<Mapping> mappings,
            Explanation explanation, Path records, Map<String,String> versions) throws Exception {
        this.owner = Objects.requireNonNull(owner); this.session = Objects.requireNonNull(session);
        this.mappings = Objects.requireNonNull(mappings); this.explanation = Objects.requireNonNull(explanation);
        this.records = records.toAbsolutePath(); this.versions = Map.copyOf(versions);
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
            try { session.authorizePrevisit(e.getValue()); return true; }
            catch (SecurityException denied) { return false; }
        }).map(Map.Entry::getKey).sorted().toList();
    }
    public synchronized long select(String caller, String alias, String goal) {
        owner(caller);
        // Even a rejected selection invalidates the old customer's active report generation.
        generation++; selected = null; activeRun = null;
        if (goal == null || goal.isBlank() || goal.length() > 500) throw new IllegalArgumentException("GOAL_REQUIRED");
        Mapping m = mappings.get();
        if (m == null || !m.customers().containsKey(alias)) throw new SecurityException("CUSTOMER_MAPPING_MISSING");
        String customer = m.customers().get(alias);
        var authority = session.authorizePrevisit(customer);
        selected = new Selection(generation, alias, customer, goal, m, authority);
        return generation;
    }
    private void current(Selection s, String run) {
        if (closed || selected != s || !Objects.equals(run, activeRun)
                || !s.mapping().equals(mappings.get())
                || !s.authority().equals(session.authorizePrevisit(s.customer()))) {
            throw new SecurityException("STALE_RUN_OR_AUTHORITY");
        }
    }
    public ObjectNode prepare(String caller, long expectedGeneration) throws Exception {
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
            report.put("mappingRevision",s.mapping().revision());
            report.put("authorityRef",s.authority().binding().authorityRef());
            report.put("startedAt",Instant.now().toString()); report.put("status","RUNNING");
            report.put("reviewStatus","PENDING"); report.put("reportRevision",1);
            report.set("assets",HarnessJson.mapper().valueToTree(versions));
            report.putArray("events").add("RUN_STARTED"); persist(report);
        }
        try {
            FinanceProvider adapter = new FinanceProvider() {
                public CapabilityResultV1 query(CapabilityRequestV1 r) {
                    synchronized (ServicePreparation.this) { current(s,run); }
                    return session.query(r.context().requestId(),r.context().traceId(),s.customer(),CAPABILITY,Map.of());
                }
                public CapabilityResultV1 executeTask(CapabilityRequestV1 r) {
                    throw new SecurityException("READ_ONLY_SCENARIO");
                }
            };
            var a = s.authority();
            var bridge = new OpenJiuwenFinanceBridge(adapter,EmployeeAgentWealthProvider.PROVIDER_ID,
                new OpenJiuwenFinanceBridge.InvocationDefaults(a.grant().principalId(),s.customer(),
                    FinanceContextV1.PrincipalType.EMPLOYEE,"finance-harness","WEALTH_PREVISIT",
                    a.authenticationLevel(),Set.of("wealth:previsit:read")),Set.of(CAPABILITY),true,a.grant().sessionRef());
            var result = bridge.query(new OpenJiuwenInvocationV1(run,CAPABILITY,"service-preparation",
                    Set.of("wealth:previsit:read"),Map.of(),Map.of()));
            synchronized (this) { current(s,run); }
            JsonNode data = HarnessJson.mapper().valueToTree(result.data());
            JsonNode context = data.path("previsitContext");
            report.set("execution",HarnessJson.mapper().valueToTree(result));
            var missing = report.putArray("missingEvidence");
            if (result.status() != OpenJiuwenExecutionV1.Status.COMPLETED || !context.isObject()) {
                report.put("status","FAILED"); missing.add("访前事实查询未完成");
                report.put("failureCode",result.errorCode());
            } else {
                String customer = context.path("customer").path("customerId").asText();
                if (!s.customer().equals(customer)) throw new SecurityException("RESULT_CUSTOMER_MISMATCH");
                report.set("facts",context.deepCopy());
                report.set("sourceReceipt",data.path("hostEvidence").deepCopy());
                String asOf = context.path("dataAsOf").asText("");
                report.put("dataAsOf",asOf); // Never substitute receipt time for business data time.
                if (asOf.isBlank()) missing.add("客户事实缺少业务数据日期");
                if (data.path("hostEvidence").path("receiptId").asText().isBlank()) missing.add("缺少来源回执");
                if (!"READY".equals(context.path("readiness").path("status").asText())) missing.add("来源未声明准备条件全部就绪");
                data.path("missingInputs").forEach(missing::add);
                context.path("readiness").path("reasons").forEach(missing::add);
                report.put("status",missing.isEmpty()?"READY_FOR_REVIEW":"PARTIAL");
                var ids = new ArrayList<String>();
                context.path("customer").fieldNames().forEachRemaining(k -> ids.add("/customer/"+k.replace("~","~0").replace("/","~1")));
                report.set("factRefs",HarnessJson.mapper().valueToTree(ids));
                String factsStatus = report.path("status").asText();
                report.put("status","PARTIAL"); report.put("explanationStatus","RUNNING");
                report.withArray("events").add("FACTS_PRESERVED");
                synchronized (this) { current(s,run); persist(report); }
                try {
                    synchronized (this) { current(s,run); }
                    List<String> focus = explanation.selectFocus(s.goal(),List.copyOf(ids),context.deepCopy());
                    if (focus == null || focus.isEmpty() || focus.size()>3 || !ids.containsAll(focus)
                            || new HashSet<>(focus).size()!=focus.size()) throw new IllegalArgumentException("UNKNOWN_FACT_REF");
                    report.set("focusFactRefs",HarnessJson.mapper().valueToTree(focus));
                    report.put("explanationStatus","READY");
                    report.put("status",factsStatus);
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                    report.put("explanationStatus","FAILED"); report.put("status","PARTIAL");
                    missing.add("沟通重点生成失败，事实已保留，准备报告尚未完整生成");
                }
            }
            report.put("finishedAt",Instant.now().toString());
            report.withArray("events").add("FACT_QUERY_FINISHED").add("REPORT_GENERATED");
            synchronized (this) { current(s,run); persist(report); return report.deepCopy(); }
        } catch (SecurityException stale) {
            synchronized (this) {
                ObjectNode discarded = report.deepCopy();
                discarded.remove(List.of("facts","execution","sourceReceipt","focusFactRefs"));
                discarded.put("status","DISCARDED"); discarded.withArray("events").add("LATE_RESULT_DISCARDED");
                persist(discarded);
            }
            throw stale;
        }
    }
    public synchronized ObjectNode read(String caller, String id) throws Exception {
        owner(caller); ObjectNode r = (ObjectNode)HarnessJson.mapper().readTree(Files.readAllBytes(path(id)));
        if (!owner.equals(r.path("ownerRef").asText())) throw new SecurityException("REPORT_OWNER_REJECTED");
        var a = session.authorizePrevisit(r.path("customerRef").asText());
        Mapping m = mappings.get();
        if (m == null || !m.revision().equals(r.path("mappingRevision").asText())
                || !r.path("customerRef").asText().equals(m.customers().get(r.path("customerAlias").asText()))
                || !a.binding().authorityRef().equals(r.path("authorityRef").asText()))
            throw new SecurityException("REPORT_AUTHORITY_CHANGED");
        return r;
    }
    public synchronized ObjectNode review(String caller, String id, int revision, String opinion) throws Exception {
        ObjectNode r = read(caller,id);
        if (selected == null || selected.generation()!=r.path("generation").asLong()
                || !id.equals(activeRun)) throw new SecurityException("STALE_REPORT");
        current(selected,id);
        if (revision!=r.path("reportRevision").asInt()) throw new IllegalStateException("REPORT_VERSION_CONFLICT");
        if ("RUNNING".equals(r.path("explanationStatus").asText())) throw new IllegalStateException("REPORT_STILL_GENERATING");
        if (!Set.of("READY_FOR_REVIEW","PARTIAL","FAILED").contains(r.path("status").asText())) throw new IllegalStateException("REPORT_NOT_REVIEWABLE");
        if (opinion==null || opinion.isBlank() || opinion.length()>2000) throw new IllegalArgumentException("REVIEW_REQUIRED");
        r.put("reviewStatus","REVIEWED"); r.put("reviewerRef",session.authorizePrevisit(selected.customer()).grant().principalId());
        r.put("reviewOpinion",opinion); r.put("reviewedAt",Instant.now().toString());
        var entry = r.withArray("reviewHistory").addObject();
        entry.put("revision",revision+1); entry.put("reviewerRef",r.path("reviewerRef").asText());
        entry.put("opinion",opinion); entry.put("reviewedAt",r.path("reviewedAt").asText());
        r.put("reportRevision",revision+1); r.withArray("events").add("MANAGER_REVIEW_SAVED");
        persist(r);return r.deepCopy(); // Review never upgrades execution or explanation status.
    }
    private Path path(String id) {
        if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("INVALID_REPORT_ID");
        Path p = records.resolve(id+".json");
        if (Files.isSymbolicLink(p)) throw new SecurityException("UNSAFE_REPORT_FILE");
        return p;
    }
    private void persist(ObjectNode r) throws Exception {
        byte[] bytes = HarnessJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(r);
        if (bytes.length>1024*1024) throw new IllegalStateException("REPORT_TOO_LARGE");
        Path target=path(r.path("reportId").asText());
        Path tmp=Files.createTempFile(records,"report-",".tmp");
        try { Files.write(tmp,bytes);Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(tmp); }
    }
    @Override public synchronized void close() { closed=true;generation++;selected=null;activeRun=null;session.close(); }
}
