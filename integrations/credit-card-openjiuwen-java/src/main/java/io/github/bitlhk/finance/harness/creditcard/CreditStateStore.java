package io.github.bitlhk.finance.harness.creditcard;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Scene-owned atomic operation state. Exactly one active coordinator per private directory. */
public final class CreditStateStore implements AutoCloseable {
    private final Path root;
    private final FileChannel lockChannel;
    private final FileLock lock;
    public CreditStateStore(Path directory) throws IOException {
        Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory))throw new IOException("UNSAFE_STATE_ROOT");
        root=directory.toRealPath();
        Path guard=root.resolve("coordinator.lock");
        if(Files.isSymbolicLink(guard))throw new IOException("UNSAFE_LOCK");
        lockChannel=FileChannel.open(guard,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try{acquired=lockChannel.tryLock();}
        catch(RuntimeException|IOException failure){lockChannel.close();throw new IOException("COORDINATOR_ALREADY_ACTIVE");}
        if(acquired==null){lockChannel.close();throw new IOException("COORDINATOR_ALREADY_ACTIVE");}
        lock=acquired;
        try(var files=Files.newDirectoryStream(root,"*.json")){
            for(Path path:files){
                if(Files.isSymbolicLink(path)||Files.size(path)>2*1024*1024)throw new IOException("INVALID_STATE");
                ObjectNode record=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(path)));
                if("running".equals(record.path("status").asText())){
                    record.put("status","failed");
                    record.putObject("result").put("code","PREVIOUS_COORDINATOR_STOPPED");writePath(path,record);
                }
            }
        }catch(RuntimeException|IOException failure){lock.release();lockChannel.close();throw failure;}
    }
    private Path file(CreditContracts.Turn turn) {
        var key=CreditContracts.JSON.createArrayNode().add(turn.binding().owner())
                .add(turn.sessionRef()).add(turn.operationKey());
        return root.resolve(CreditContracts.hash(key)+".json");
    }
    private ObjectNode load(CreditContracts.Turn turn) throws IOException {
        if(!lock.isValid())throw new IOException("STATE_STORE_CLOSED");
        Path path=file(turn);
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2*1024*1024)
            throw new IOException("INVALID_STATE");
        ObjectNode value=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(path)));
        if(!CreditContracts.hash(turn.fingerprint()).equals(value.path("input_digest").asText()))
            throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");
        return value;
    }
    private void write(CreditContracts.Turn turn,ObjectNode value) throws IOException {
        writePath(file(turn),value);
    }
    private void writePath(Path destination,ObjectNode value) throws IOException {
        if(!lock.isValid())throw new IOException("STATE_STORE_CLOSED");
        byte[] bytes=CreditContracts.JSON.writeValueAsBytes(value);
        if(bytes.length>2*1024*1024)throw new IOException("STATE_TOO_LARGE");
        Path tmp=Files.createTempFile(root,"credit-state-",".tmp");
        try {
            try(var channel=FileChannel.open(tmp,WRITE)){
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            if(Files.isSymbolicLink(destination))throw new IOException("UNSAFE_STATE");
            Files.move(tmp,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally {Files.deleteIfExists(tmp);}
    }
    /** Null means this caller owns a newly persisted operation; otherwise return its prior state. */
    public synchronized ObjectNode begin(CreditContracts.Turn turn) throws IOException {
        ObjectNode prior=load(turn);if(prior!=null)return publicState(prior);
        ObjectNode record=CreditContracts.JSON.createObjectNode();
        record.put("schema_version","credit-java-run.v1").put("input_digest",CreditContracts.hash(turn.fingerprint()))
                .put("session_ref",turn.sessionRef()).put("operation_key",turn.operationKey())
                .put("status","running").put("scene",turn.scene()).put("owner",turn.binding().owner())
                .put("created_at",java.time.Instant.now().toString());
        record.set("input",turn.fingerprint());
        ObjectNode previous=null;
        try(var files=Files.newDirectoryStream(root,"*.json")){
            for(Path path:files){
                if(Files.isSymbolicLink(path)||Files.size(path)>2*1024*1024)throw new IOException("INVALID_STATE");
                var candidate=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(path)));
                if(turn.binding().owner().equals(candidate.path("owner").asText())&&turn.sessionRef().equals(candidate.path("session_ref").asText())
                        &&turn.scene().equals(candidate.path("scene").asText())){
                    if("running".equals(candidate.path("status").asText()))throw new IllegalArgumentException("SESSION_BUSY");
                    if(previous==null||candidate.path("created_at").asText().compareTo(previous.path("created_at").asText())>0)previous=candidate;
                }
            }
        }
        if(previous!=null)record.put("parent_operation_key",previous.path("operation_key").asText());
        write(turn,record);return null;
    }
    public synchronized ObjectNode finish(CreditContracts.Turn turn,String status,ObjectNode result) throws IOException {
        if(!Set.of("complete","needs-input","failed","insufficient","cancelled","timeout").contains(status))
            throw new IllegalArgumentException("INVALID_TERMINAL");
        ObjectNode current=load(turn);if(current==null)throw new IllegalArgumentException("RUN_NOT_FOUND");
        if(!"running".equals(current.path("status").asText()))return publicState(current);
        current.put("status",status);ObjectNode terminal=result.deepCopy();
        if(current.path("execution").isArray())terminal.set("execution",current.get("execution").deepCopy());
        current.set("result",terminal);write(turn,current);return publicState(current);
    }
    /** Append audit independently of business terminal state, including late completion after cancellation. */
    public synchronized void appendAudit(CreditContracts.Turn turn,ObjectNode event) throws IOException {
        CreditContracts.fields(event,Set.of("kind","name","status","elapsed_ms","usage_status","model","call_id"));
        if(!event.path("elapsed_ms").isNumber()||!Double.isFinite(event.path("elapsed_ms").doubleValue())
                ||event.path("elapsed_ms").doubleValue()<0)throw new IllegalArgumentException("INVALID_AUDIT");
        ObjectNode state=load(turn);if(state==null)throw new IOException("RUN_NOT_FOUND");
        var events=state.withArray("execution");
        boolean updated=false;
        if(event.path("call_id").isTextual()){
            for(var prior:events)if(event.path("call_id").equals(prior.path("call_id"))){
                if(!event.path("kind").equals(prior.path("kind"))||!event.path("name").equals(prior.path("name")))
                    throw new IOException("AUDIT_IDENTITY_CONFLICT");
                ((ObjectNode)prior).setAll(event);updated=true;break;
            }
        }
        if(!updated){
            if(events.size()>=64)throw new IOException("AUDIT_BUDGET_EXHAUSTED");
            events.add(event.deepCopy());
        }
        if(state.path("result").isObject())((ObjectNode)state.path("result")).set("execution",events.deepCopy());
        write(turn,state);
    }
    private static ObjectNode publicState(ObjectNode value){
        if(value==null)return null;var result=value.deepCopy();result.remove("retained_verified_results");return result;
    }
    /** Private recovery evidence only. Never release partial facts without a new source recheck. */
    public synchronized void retainVerified(CreditContracts.Turn turn,ObjectNode engines) throws IOException {
        turn.check();ObjectNode current=load(turn);
        if(current==null||!"running".equals(current.path("status").asText()))return;
        current.set("retained_verified_results",engines.deepCopy());write(turn,current);
    }
    public synchronized ObjectNode read(CreditContracts.Turn turn) throws IOException {return publicState(load(turn));}
    public synchronized boolean running(CreditContracts.Turn turn) throws IOException {
        ObjectNode state=load(turn);return state!=null&&"running".equals(state.path("status").asText());
    }
    public synchronized List<String> history(CreditContracts.Turn turn) throws IOException {
        var values=new ArrayList<ObjectNode>();
        try(var files=Files.newDirectoryStream(root,"*.json")){
            for(Path p:files){
                if(Files.isSymbolicLink(p)||Files.size(p)>2*1024*1024)throw new IOException("INVALID_STATE");
                ObjectNode v=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(p)));
                if(turn.sessionRef().equals(v.path("session_ref").asText())
                        &&turn.binding().owner().equals(v.path("owner").asText())
                        &&turn.scene().equals(v.path("scene").asText())
                        &&turn.binding().customer().equals(v.path("input").path("binding").path("customer_id").asText())
                        &&!turn.operationKey().equals(v.path("operation_key").asText())
                        &&Set.of("complete","needs-input").contains(v.path("status").asText()))values.add(v);
            }
        }
        values.sort(Comparator.comparing(v->v.path("created_at").asText()));
        return values.stream().skip(Math.max(0,values.size()-10)).map(v->v.path("input").path("question").asText()).toList();
    }
    public synchronized ObjectNode benefitsMemory(CreditContracts.Turn turn) throws IOException {
        if(!lock.isValid())throw new IOException("STATE_STORE_CLOSED");
        ObjectNode latest=null;
        try(var files=Files.newDirectoryStream(root,"*.json")){
            for(Path p:files){
                if(Files.isSymbolicLink(p)||Files.size(p)>2*1024*1024)throw new IOException("INVALID_STATE");
                ObjectNode v=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(p)));
                if(turn.sessionRef().equals(v.path("session_ref").asText())
                        &&turn.binding().owner().equals(v.path("owner").asText())
                        &&"benefits".equals(v.path("scene").asText())
                        &&turn.binding().customer().equals(v.path("input").path("binding").path("customer_id").asText())
                        &&!turn.operationKey().equals(v.path("operation_key").asText())
                        &&Set.of("complete","needs-input","insufficient").contains(v.path("status").asText())
                        &&(latest==null||v.path("created_at").asText().compareTo(latest.path("created_at").asText())>0))latest=v;
            }
        }
        ObjectNode result=CreditContracts.JSON.createObjectNode();
        boolean benefitsVisible=false;for(var source:turn.binding().value().path("visible_source_ids"))
            if("benefits".equals(source.asText()))benefitsVisible=true;
        if(latest!=null&&benefitsVisible&&turn.binding().value().equals(latest.path("input").path("binding"))){
            result.set("excluded_benefit_ids",latest.path("result").path("excluded_benefit_ids").deepCopy());
            result.put("goal",latest.path("result").path("goal").asText());
            var ids=result.putArray("previous_recommendation_ids");
            for(var engine:latest.path("result").path("results"))for(var item:engine.path("items"))
                ids.add(item.path("benefit").path("benefit_id").asText());
        }
        if(latest!=null&&!result.has("excluded_benefit_ids"))result.put("previous_context","UNAVAILABLE_SOURCE_CHANGED");
        return result;
    }
    public synchronized ObjectNode createConversation(String owner,String scene,String customerRef) throws IOException {
        if(!Set.of("limit-risk","benefits").contains(scene))throw new IllegalArgumentException("INVALID_SCENE");
        ObjectNode value=CreditContracts.JSON.createObjectNode().put("id","conversation-"+UUID.randomUUID().toString().replace("-",""))
                .put("owner",owner).put("scene",scene).put("revision",0).put("title","新对话")
                .put("customerRef",customerRef).put("thread","session-"+UUID.randomUUID().toString().replace("-",""))
                .put("updatedAt",java.time.Instant.now().toString());value.putArray("messages");
        writePath(conversationFile(value.path("id").asText()),value);return value.deepCopy();
    }
    private Path conversationFile(String id){
        if(!id.matches("conversation-[a-f0-9]{32}"))throw new IllegalArgumentException("INVALID_CONVERSATION");
        return root.resolve(id+".json");
    }
    public synchronized ObjectNode conversation(String owner,String scene,String id) throws IOException {
        Path path=conversationFile(id);
        if(!lock.isValid()||Files.isSymbolicLink(path)||!Files.isRegularFile(path)||Files.size(path)>2*1024*1024)
            throw new IOException("CONVERSATION_UNAVAILABLE");
        var value=CreditContracts.object(CreditContracts.JSON.readTree(Files.readAllBytes(path)));
        if(!owner.equals(value.path("owner").asText())||!scene.equals(value.path("scene").asText()))
            throw new SecurityException("CONVERSATION_UNAVAILABLE");return value;
    }
    public synchronized com.fasterxml.jackson.databind.node.ArrayNode conversations(String owner,String scene) throws IOException {
        var values=new ArrayList<ObjectNode>();
        try(var files=Files.newDirectoryStream(root,"conversation-*.json")){
            for(Path file:files){
                try{values.add(conversation(owner,scene,file.getFileName().toString().replace(".json","")));}
                catch(SecurityException denied){/* Other owner/scene is outside this directory view. */}
            }
        }
        values.sort(Comparator.comparing((ObjectNode v)->v.path("updatedAt").asText()).reversed());
        var result=CreditContracts.JSON.createArrayNode();
        for(var value:values.subList(0,Math.min(values.size(),100))){
            var summary=value.deepCopy();summary.remove(List.of("messages","owner","thread"));result.add(summary);
        }return result;
    }
    public synchronized ObjectNode saveConversation(String owner,String scene,ObjectNode proposed) throws IOException {
        ObjectNode current=conversation(owner,scene,CreditContracts.required(proposed,"id"));
        if(!current.path("revision").equals(proposed.path("revision")))throw new IllegalArgumentException("CONVERSATION_CHANGED");
        if(!owner.equals(proposed.path("owner").asText())||!scene.equals(proposed.path("scene").asText()))
            throw new SecurityException("CONVERSATION_UNAVAILABLE");
        var value=proposed.deepCopy();value.put("revision",current.path("revision").asInt()+1)
                .put("updatedAt",java.time.Instant.now().toString());
        writePath(conversationFile(value.path("id").asText()),value);return value;
    }
    @Override public synchronized void close() throws IOException {lock.release();lockChannel.close();}
}
