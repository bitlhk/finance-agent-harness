package io.github.bitlhk.finance.harness.integrations.taskinteraction;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.execution.*;
import io.github.bitlhk.finance.harness.providers.taskinteraction.*;
import io.github.bitlhk.finance.harness.taskinteraction.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Protocol fixtures only. Actual original Python service and independent Host are separate acceptance gates. */
class TaskInteractionSessionTest {
    @TempDir Path directory;
    static final ObjectMapper JSON=TaskInteractionMapper.json();
    static final String HASH="sha256:"+"1".repeat(64);
    static String sha(byte[] raw) throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
    static final class Fixture implements AutoCloseable {
        final Path root,state;
        final HttpServer server;
        final AtomicInteger writes=new AtomicInteger();
        final ObjectNode snapshot;
        final ObjectNode authority;
        final ManagedScenarioExtensionV01.Context context;
        Fixture(Path directory,ManagedScenarioExtensionV01.ModelConnection model) throws Exception {
            root=Files.createDirectory(directory.resolve("artifact"));state=Files.createDirectory(directory.resolve("state"));
            snapshot=JSON.createObjectNode();snapshot.put("schemaVersion","finance-task-snapshot.v1").put("taskRef","task_1").put("sourceObjectRef","instruction_1")
                .put("sourceCommit",TaskInteractionV1.SOURCE_COMMIT).put("sourceRelease",HASH).put("version",HASH).put("materialVersion",HASH).put("originalState","pending").put("displayState","WAITING_INPUT");
            snapshot.putArray("allowedActions").add("claim");snapshot.putArray("inputRequired");snapshot.putArray("unresolvedRequestIds");snapshot.putObject("rawResult").put("id","instruction_1").put("status","pending");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                JsonNode value=snapshot;
                if(exchange.getRequestMethod().equals("POST")){
                    var input=JSON.readTree(exchange.getRequestBody());writes.incrementAndGet();
                    var receipt=JSON.createObjectNode();receipt.put("schemaVersion","finance-task-receipt.v1").put("requestId",input.path("requestId").asText()).put("taskRef","task_1").put("action","claim")
                        .put("actorRef","actor").put("beforeVersion",input.path("expectedVersion").asText()).put("materialVersion",HASH).put("status","SUCCEEDED").put("originalHttpStatus",200)
                        .put("message","OK").put("afterVersion","sha256:"+"2".repeat(64)).put("recordedAt",Instant.now().toString());
                    receipt.putObject("rawResult").put("id","instruction_1").put("status","pending_submit");value=receipt;
                    snapshot.put("version","sha256:"+"2".repeat(64)).put("originalState","pending_submit");snapshot.withArray("allowedActions").removeAll().add("submit");snapshot.with("rawResult").put("status","pending_submit");
                }else if(exchange.getRequestURI().getPath().endsWith("history")){var h=JSON.createObjectNode();h.putArray("entries");value=h;}
                byte[] raw=JSON.writeValueAsBytes(value);exchange.sendResponseHeaders(200,raw.length);try(var out=exchange.getResponseBody()){out.write(raw);}exchange.close();
            });server.start();
            var binding=new ManagedOriginalTaskFactoryV01.Config("original-task-bindings.v1",List.of(new ManagedOriginalTaskFactoryV01.Binding("actor","customer","session_1","task_1","instruction_1",HASH)));
            Path file=root.resolve("bindings.json");byte[] bytes=JSON.writeValueAsBytes(binding);Files.write(file,bytes);String configDigest=sha(bytes).substring(7);
            String mid="custody-test-method",version="0.1.0",relative="assets/private-methods/"+mid+"/"+version+"/skills/"+mid+"/SKILL.md";
            Path method=root.resolve(relative);Files.createDirectories(method.getParent());Files.writeString(method,"读取原托管指令现状并说明等待项；复核和执行分别确认。");
            var plan=JSON.createObjectNode();plan.putObject("profileRef").put("id","original-task-review-managed");plan.putObject("runtime").put("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15");
            byte[] planBytes=JSON.writeValueAsBytes(plan);Files.write(root.resolve("component-plan.json"),planBytes);
            var lock=JSON.createObjectNode();lock.putObject("scenarioExtension").put("id",TaskInteractionScenarioExtensionV01.ID);lock.putObject("method").put("assetId",mid).put("version",version);
            lock.putObject("providerConfiguration").putObject(ManagedOriginalTaskProvider.ID).put("binding-file-sha256",configDigest);
            lock.putObject("files").put("component-plan.json",sha(planBytes).substring(7)).put(relative,sha(Files.readAllBytes(method)).substring(7));Files.write(root.resolve("service-lock.json"),JSON.writeValueAsBytes(lock));
            authority=JSON.createObjectNode();authority.put("schemaVersion","original-task-host-authority.v1").put("ownerRef","actor").put("endpoint","http://127.0.0.1:"+server.getAddress().getPort()).put("timeoutMs",3000)
                .put("bindingsFile",file.toString()).put("bindingsDigest",configDigest);
            var lease=authority.putArray("leases").addObject();lease.put("principalId","actor").put("subjectRef","customer").put("taskRef","task_1").put("sessionRef","session_1")
                .put("credential","synthetic-unit-credential-for-source").put("expiresAt",Instant.now().plusSeconds(300).toString());lease.putArray("scopes").add(ManagedOriginalTaskProvider.READ).add(ManagedOriginalTaskProvider.WRITE);saveAuthority();
            context=new ManagedScenarioExtensionV01.Context(root,state,"build_1","instance_1","actor",model);
        }
        void saveAuthority() throws Exception{Files.write(state.resolve("task-authority.json"),JSON.writeValueAsBytes(authority));}
        TaskInteractionSession open() throws Exception{return new TaskInteractionSession(context,new FileTaskJournal(state.resolve("journal")));}
        @Override public void close(){server.stop(0);}
    }
    static ObjectNode claim(){var n=JSON.createObjectNode();n.put("action","claim").put("expectedVersion",HASH).put("materialVersion",HASH);n.putObject("parameters");return n;}
    @Test void usesExistingDurableCoordinationAndDoesNotRepeatConfirmation() throws Exception {
        try(var fixture=new Fixture(directory,null);var session=fixture.open()){
            var proposal=session.call("propose",claim());assertEquals("NEED_CONFIRMATION",proposal.get("status"));assertEquals(0,fixture.writes.get());
            String handle=proposal.get(TaskCoordination.HANDLE_ARGUMENT).toString();String binding=session.taskBinding(handle);
            var approval=new TaskCoordination.Approval(UUID.randomUUID().toString(),binding,Instant.now(),Instant.now().plusSeconds(60));
            assertEquals("COMPLETED",JSON.valueToTree(session.taskConfirm(handle,approval)).path("status").asText());
            session.taskConfirm(handle,approval);assertEquals(1,fixture.writes.get());
            try(var reopened=fixture.open()){assertEquals("COMPLETED",JSON.valueToTree(reopened.taskResume(handle)).path("status").asText());assertEquals(1,fixture.writes.get());}
            assertEquals("pending_submit",JSON.valueToTree(session.call("taskSnapshot",JSON.createObjectNode())).at("/taskInteraction/originalState").asText());
        }
    }
    @Test void revocationAndLocalCancellationDoNotBecomeOriginalSuccess() throws Exception {
        try(var fixture=new Fixture(directory,null);var session=fixture.open()){
            assertEquals("CANCELLED",session.call("cancel",JSON.createObjectNode()).get("status"));assertEquals(0,fixture.writes.get());
            fixture.authority.withArray("leases").removeAll();fixture.saveAuthority();
            assertThrows(SecurityException.class,session::bootstrap);
            assertThrows(SecurityException.class,()->session.call("history",JSON.createObjectNode()));assertEquals(0,fixture.writes.get());
        }
    }
    @Test void refusesForgedConfirmationCommandAndMissingHostJournal(){
        assertThrows(IllegalArgumentException.class,()->TaskInteractionCommands.validate("confirm",JSON.createObjectNode()));
        assertThrows(IllegalStateException.class,()->new TaskInteractionScenarioExtensionV01().open(null));
    }
}
