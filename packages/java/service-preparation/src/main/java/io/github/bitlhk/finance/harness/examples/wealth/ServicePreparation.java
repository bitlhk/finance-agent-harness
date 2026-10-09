package io.github.bitlhk.finance.harness.examples.wealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.bitlhk.finance.harness.core.*;
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
    @FunctionalInterface public interface ReportGenerator {
        ObjectNode generate(InstalledPreparationMethod method, String goal,
                            List<String> factIds, JsonNode context) throws Exception;
    }
    private record Selection(long generation, String alias, String customer, String goal,
                             Mapping mapping, PreparationAccess.Snapshot authority) {}
    private final String owner;
    private final PreparationAccess access;
    private final Supplier<Mapping> mappings;
    private final ReportGenerator generator;
    private final InstalledPreparationMethod method;
    private final Path records;
    private final Map<String,String> versions;
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
        this.owner = Objects.requireNonNull(owner); this.access = Objects.requireNonNull(access);
        this.mappings = Objects.requireNonNull(mappings); this.generator = Objects.requireNonNull(generator);
        this.method = Objects.requireNonNull(method);
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
            try { access.authorize(e.getValue()); return true; }
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
        var authority = access.authorize(customer);
        selected = new Selection(generation, alias, customer, goal, m, authority);
        return generation;
    }
    private void current(Selection s, String run) {
        if (closed || selected != s || !Objects.equals(run, activeRun)
                || !s.mapping().equals(mappings.get())
                || !s.authority().equals(access.authorize(s.customer()))) {
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
            report.put("authorityRef",s.authority().authorityRef());
            report.put("startedAt",Instant.now().toString()); report.put("status","RUNNING");
            report.put("reviewStatus","PENDING"); report.put("reportRevision",0);
            report.set("assets",HarnessJson.mapper().valueToTree(versions));
            report.set("method", HarnessJson.mapper().valueToTree(Map.of("id", method.id(), "version", method.version(),
                    "digest", method.digest(), "lockDigest", method.lockDigest())));
            report.putArray("events").add("RUN_STARTED"); persist(report);
        }
        try {
            synchronized (this) { current(s,run); }
            CapabilityResultV1 result = access.query(run,run,s.customer(),CAPABILITY,Map.of());
            synchronized (this) { current(s,run); }
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
                    ObjectNode generated = generator.generate(method, s.goal(), List.copyOf(ids), context.deepCopy());
                    validateGenerated(generated, ids, context, s.goal());
                    report.set("modelSuggestion", generated.deepCopy());
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
                discarded.remove(List.of("facts","execution","sourceReceipt","focusFactRefs","modelSuggestion"));
                discarded.put("status","DISCARDED"); discarded.withArray("events").add("LATE_RESULT_DISCARDED");
                persist(discarded);
            }
            throw stale;
        }
    }
    public synchronized ObjectNode read(String caller, String id) throws Exception {
        owner(caller); ObjectNode r = (ObjectNode)HarnessJson.mapper().readTree(Files.readAllBytes(latestPath(id)));
        if (!owner.equals(r.path("ownerRef").asText())) throw new SecurityException("REPORT_OWNER_REJECTED");
        var a = access.authorize(r.path("customerRef").asText());
        Mapping m = mappings.get();
        if (m == null || !m.revision().equals(r.path("mappingRevision").asText())
                || !r.path("customerRef").asText().equals(m.customers().get(r.path("customerAlias").asText()))
                || !a.authorityRef().equals(r.path("authorityRef").asText()))
            throw new SecurityException("REPORT_AUTHORITY_CHANGED");
        return r;
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
        persist(r);return r.deepCopy(); // Review never upgrades execution or explanation status.
    }
    public synchronized ObjectNode readVersion(String caller, String id, int revision) throws Exception {
        read(caller,id); // Recheck current owner, mapping and CRM authority before historical access.
        if (revision < 1) throw new IllegalArgumentException("INVALID_REPORT_REVISION");
        Path file = reportDirectory(id).resolve(String.format("v%06d.json", revision));
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw new IllegalArgumentException("REPORT_VERSION_NOT_FOUND");
        ObjectNode value = (ObjectNode)HarnessJson.mapper().readTree(Files.readAllBytes(file));
        if (!owner.equals(value.path("ownerRef").asText())) throw new SecurityException("REPORT_OWNER_REJECTED");
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
        return path(id); // Historical flat-file reports remain readable.
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
    private static ReportGenerator legacy(Explanation explanation) {
        Objects.requireNonNull(explanation);
        return (method, goal, ids, context) -> {
            List<String> focus = explanation.selectFocus(goal, ids, context);
            ObjectNode generated = HarnessJson.mapper().createObjectNode();
            var points = generated.putArray("communicationFocus");
            if (focus != null) for (String ref : focus) {
                var point=points.addObject();point.put("text","请经理结合该事实准备沟通");point.putArray("factRefs").add(ref);
            }
            generated.putArray("preparationAdvice");generated.putArray("pendingItems");
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
            JsonNode values=generated.path(section);if(!values.isArray()||values.size()>8)throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
            for(JsonNode value:values) {
                if(!value.isObject()||value.path("text").asText().isBlank()||value.path("text").asText().length()>500
                        ||!value.path("factRefs").isArray()||value.path("factRefs").isEmpty())
                    throw new IllegalArgumentException("MODEL_REPORT_SHAPE_INVALID");
                Set<String> refs=new HashSet<>();value.path("factRefs").forEach(refsNode->refs.add(refsNode.asText()));
                if(!ids.containsAll(refs))throw new IllegalArgumentException("UNKNOWN_FACT_REF");
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
    }
    @Override public synchronized void close() { closed=true;generation++;selected=null;activeRun=null;access.close(); }
}
