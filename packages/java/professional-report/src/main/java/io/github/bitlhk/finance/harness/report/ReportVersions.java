package io.github.bitlhk.finance.harness.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.Stream;

/** Private immutable report versions. Original facts and human notes are separate. */
public final class ReportVersions {
    private final Path root;
    private final ReportAccess access;
    private final Clock clock;
    public record Previous(String reportId,int revision,String digest) {
        public Previous {ReportMapper.id(reportId);ReportMapper.hash(digest);if(revision<1)ReportMapper.invalid();}
    }
    public ReportVersions(Path root,ReportAccess access,Clock clock) throws Exception {
        if(Files.isSymbolicLink(root))throw new SecurityException("REPORT_STORAGE_REJECTED");
        this.root=root.toAbsolutePath().normalize();this.access=Objects.requireNonNull(access);this.clock=Objects.requireNonNull(clock);
        Files.createDirectories(this.root);
    }
    private Path directory(String id) {
        ReportMapper.id(id);Path value=root.resolve(id);
        if(Files.isSymbolicLink(root)||Files.isSymbolicLink(value))throw new SecurityException("REPORT_STORAGE_REJECTED");
        return value;
    }
    private Path versionFile(String id,int revision) {
        if(revision<1||revision>32)throw new IllegalArgumentException("REPORT_VERSION_LIMIT");
        Path value=directory(id).resolve(String.format("v%06d.json",revision));
        if(Files.isSymbolicLink(value))throw new SecurityException("REPORT_STORAGE_REJECTED");
        return value;
    }
    private void capacity(int extra) throws Exception {
        long bytes=extra;int count=0;
        try(Stream<Path> files=Files.walk(root,2)) {
            for(Path p:files.toList()) {
                if(Files.isSymbolicLink(p))throw new SecurityException("REPORT_STORAGE_REJECTED");
                if(Files.isRegularFile(p)){bytes+=Files.size(p);count++;}
            }
        }
        if(bytes>512L*1024*1024||count>=8192)throw new IllegalStateException("REPORT_STORAGE_LIMIT");
    }
    public synchronized ProfessionalReportV1 save(String caller,ProfessionalReportV1 report,Previous previous) throws Exception {
        access.require(caller,report.scope());
        if(report.revision()!=1||report.status().equals("RUNNING"))throw new IllegalArgumentException("REPORT_TERMINAL_REQUIRED");
        if(previous!=null) {
            var old=read(caller,previous.reportId(),previous.revision());
            if(!old.scope().equals(report.scope())||!ReportMapper.sha(ReportMapper.bytes(old.document())).equals(previous.digest()))
                throw new SecurityException("REPORT_PREVIOUS_VERSION_MISMATCH");
        }
        Path directory=directory(report.reportId());
        if(Files.exists(directory))throw new IllegalStateException("REPORT_ALREADY_EXISTS");
        long reports;try(var dirs=Files.list(root)){reports=dirs.filter(Files::isDirectory).count();}
        if(reports>=256)throw new IllegalStateException("REPORT_COUNT_LIMIT");
        write(caller,report,previous);return read(caller,report.reportId(),1);
    }
    private void write(String caller,ProfessionalReportV1 report,Previous previous) throws Exception {
        access.require(caller,report.scope());
        ObjectNode envelope=ReportMapper.json().createObjectNode();envelope.set("report",report.document());
        envelope.put("reportDigest",ReportMapper.sha(ReportMapper.bytes(report.document())));
        var proofs=envelope.putArray("verifiedSources");report.verifiedSources().stream().sorted().forEach(proofs::add);
        if(previous==null)envelope.putNull("previous");
        else envelope.putObject("previous").put("reportId",previous.reportId()).put("revision",previous.revision()).put("digest",previous.digest());
        envelope.put("createdAt",clock.instant().toString());envelope.put("recordDigest",ReportMapper.sha(ReportMapper.bytes(envelope)));byte[] bytes=ReportMapper.bytes(envelope);
        if(bytes.length>4*1024*1024)throw new IllegalArgumentException("REPORT_STORAGE_SIZE_LIMIT");
        capacity(bytes.length);Path target=versionFile(report.reportId(),report.revision());Files.createDirectories(target.getParent());
        access.require(caller,report.scope());
        Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        // No mutable latest-success slot: list/read derives actual immutable versions.
        access.require(caller,report.scope());
    }
    public synchronized ProfessionalReportV1 read(String caller,String id,int revision) throws Exception {
        access.caller(caller);Path file=versionFile(id,revision);
        if(!Files.isRegularFile(file)||Files.size(file)>4*1024*1024)throw new IllegalArgumentException("REPORT_VERSION_UNAVAILABLE");
        JsonNode envelope=ReportMapper.parse(Files.readAllBytes(file));
        ReportMapper.fields(envelope,Set.of("report","reportDigest","verifiedSources","previous","createdAt","recordDigest"));
        ObjectNode unsigned=envelope.deepCopy();unsigned.remove("recordDigest");
        if(!ReportMapper.sha(ReportMapper.bytes(unsigned)).equals(envelope.path("recordDigest").asText()))throw new SecurityException("REPORT_RECORD_DIGEST_MISMATCH");
        try{java.time.Instant.parse(envelope.path("createdAt").asText());}catch(Exception failure){ReportMapper.invalid();}
        if(!envelope.path("previous").isNull()) {
            JsonNode previous=envelope.path("previous");ReportMapper.fields(previous,Set.of("reportId","revision","digest"));
            new Previous(ReportMapper.text(previous,"reportId"),previous.path("revision").asInt(),ReportMapper.text(previous,"digest"));
        }
        JsonNode document=envelope.path("report");access.require(caller,document.path("scope"));
        if(!id.equals(document.path("reportId").asText())||revision!=document.path("revision").asInt()
                ||!ReportMapper.sha(ReportMapper.bytes(document)).equals(envelope.path("reportDigest").asText()))
            throw new SecurityException("REPORT_VERSION_DIGEST_MISMATCH");
        var proof=envelope.path("verifiedSources");if(!proof.isArray()||proof.size()>2048)ReportMapper.invalid();
        Set<String> sources=new HashSet<>();for(JsonNode p:proof){ReportMapper.id(p.asText());if(!sources.add(p.asText()))ReportMapper.invalid();}
        var result=ReportMapper.retained(document,sources);access.require(caller,result.scope());return result;
    }
    public synchronized List<Integer> versions(String caller,String id) throws Exception {
        access.caller(caller);Path directory=directory(id);List<Integer> versions=new ArrayList<>();
        if(!Files.isDirectory(directory))throw new IllegalArgumentException("REPORT_VERSION_UNAVAILABLE");
        try(var files=Files.list(directory)) {
            for(Path p:files.toList()) {
                String name=p.getFileName().toString();if(!name.matches("v[0-9]{6}\\.json"))continue;
                int revision=Integer.parseInt(name.substring(1,7));read(caller,id,revision);versions.add(revision);
            }
        }
        Collections.sort(versions);return List.copyOf(versions);
    }
    public synchronized ProfessionalReportV1 note(String caller,String id,int expectedRevision,String text) throws Exception {
        if(text==null||text.isBlank()||text.length()>2000)throw new IllegalArgumentException("REPORT_NOTE_REQUIRED");
        List<Integer> versions=versions(caller,id);if(versions.isEmpty())throw new IllegalArgumentException("REPORT_VERSION_UNAVAILABLE");int latest=versions.getLast();
        if(latest!=expectedRevision)throw new IllegalStateException("REPORT_VERSION_CONFLICT");
        var old=read(caller,id,latest);ObjectNode document=(ObjectNode)old.document();document.put("revision",latest+1);
        document.withArray("notes").addObject().put("id",UUID.randomUUID().toString()).put("text",text).put("actorRef",caller).put("createdAt",clock.instant().toString());
        var next=ReportMapper.retained(document,old.verifiedSources());
        write(caller,next,new Previous(id,latest,ReportMapper.sha(ReportMapper.bytes(old.document()))));
        return read(caller,id,latest+1); // Review/qualification and original facts remain unchanged.
    }
    public synchronized List<Map<String,Object>> history(String caller) throws Exception {
        access.caller(caller);List<Map<String,Object>> rows=new ArrayList<>();
        try(var directories=Files.list(root)) {
            for(Path dir:directories.toList()) {
                String id=dir.getFileName().toString();if(!id.matches("[A-Za-z0-9_-]{1,128}")||!Files.isDirectory(dir))continue;
                try {
                    var list=versions(caller,id);if(list.isEmpty())continue;var report=read(caller,id,list.getLast());
                    rows.add(Map.of("reportId",id,"runId",report.runId(),"revision",report.revision(),"status",report.status(),"scope",report.scope()));
                }catch(SecurityException denied){
                    if(!Set.of("REPORT_OWNER_REJECTED","REPORT_CURRENT_AUTHORITY_REQUIRED").contains(denied.getMessage()))throw denied;
                    // Current authority excludes this entry; storage errors and authority outages are not empty history.
                }
            }
        }
        return List.copyOf(rows);
    }
    public synchronized Map<String,Object> lineage(String caller,String id,int revision) throws Exception {
        var current=read(caller,id,revision);JsonNode envelope=ReportMapper.parse(Files.readAllBytes(versionFile(id,revision)));
        JsonNode previous=envelope.path("previous");String state=previous.isNull()?"NONE":"AVAILABLE";
        if(!previous.isNull()) {
            try {
                var old=read(caller,previous.path("reportId").asText(),previous.path("revision").asInt());
                if(!old.scope().equals(current.scope())||!ReportMapper.sha(ReportMapper.bytes(old.document())).equals(previous.path("digest").asText()))throw new SecurityException("REPORT_PREVIOUS_VERSION_MISMATCH");
            }catch(SecurityException denied) {
                if(!Set.of("REPORT_OWNER_REJECTED","REPORT_CURRENT_AUTHORITY_REQUIRED").contains(denied.getMessage()))throw denied;
                previous=ReportMapper.json().getNodeFactory().nullNode();state="NOT_AUTHORIZED";
            }
        }
        access.require(caller,current.scope());return Map.of("createdAt",envelope.path("createdAt").asText(),"previous",previous.deepCopy(),"previousState",state);
    }
}
