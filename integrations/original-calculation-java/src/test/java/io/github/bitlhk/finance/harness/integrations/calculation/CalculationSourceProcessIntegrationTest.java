package io.github.bitlhk.finance.harness.integrations.calculation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.providers.calculation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Requires real separately prepared original environments; missing inputs fail rather than skip. */
class CalculationSourceProcessIntegrationTest {
    static final ObjectMapper JSON=HarnessJson.mapper();
    @TempDir Path temp;
    final AtomicReference<String> family=new AtomicReference<>("ftp");
    final AtomicInteger modelCalls=new AtomicInteger();

    @Test @Timeout(900) void allFixedOperationsUseRealOriginalProcessesAndRetainControlledHistory() throws Exception {
        String configured=System.getProperty("fh.calculationConfig");
        assertNotNull(configured,"Prepare real original tools and pass -Dfh.calculationConfig; this qualification cannot be skipped.");
        JsonNode config=JSON.readTree(Path.of(configured).toFile());
        Path release=Path.of(config.path("toolRoot").asText()),harness=Path.of(config.path("harnessRoot").asText());
        assertEquals(config.path("toolReleaseDigest").asText(),CalculationNativeIntegrationTest.hash(Files.readAllBytes(release.resolve("release.json"))));
        JsonNode manifest=JSON.readTree(release.resolve("release.json").toFile());
        JsonNode catalog=JSON.readTree(Objects.requireNonNull(getClass().getResourceAsStream("/calculation-native-cases.v1.json")));
        List<Map<String,Object>> sourceBindings=new ArrayList<>();
        for(JsonNode row:catalog.path("cases"))sourceBindings.add(Map.of("token",token(row.path("caseId").asText()),
            "context",context(row.path("caseId").asText()),"assetIds",List.of(row.path("assetId").asText())));
        Path sourceConfig=temp.resolve("source-bindings.json");Files.write(sourceConfig,JSON.writeValueAsBytes(Map.of("bindings",sourceBindings)));
        Path sourceState=temp.resolve("source-private");
        Path timeoutMarker=temp.resolve("hold-timeout-worker");
        Path workerGate=workerGate(temp),sourceEntry=sourceEntry(temp);
        var processBuilder=new ProcessBuilder(config.path("workerPython").asText(),"-B",sourceEntry.toString(),
            release.resolve("adapter/server.py").toString(),timeoutMarker.toString(),workerGate.toString(),
            "--release",release.toString(),"--digest",config.path("toolReleaseDigest").asText(),"--private-root",sourceState.toString(),"--bindings",sourceConfig.toString());
        var environment=processBuilder.environment();environment.keySet().removeIf(k->k.startsWith("FH_")||k.startsWith("OPENAI_")||k.startsWith("DEEPSEEK_")||k.startsWith("DASHSCOPE_")||k.endsWith("_API_KEY")||k.equals("PYTHONPATH")||k.equals("PYTHONHOME"));
        environment.put("PYTHONUTF8","1");environment.put("PYTHONDONTWRITEBYTECODE","1");
        processBuilder.redirectError(temp.resolve("source-error.log").toFile());
        Process source=processBuilder.start();HttpServer model=model();
        List<Map<String,Object>> evidence=new ArrayList<>();
        try(var reader=source.inputReader(StandardCharsets.UTF_8)) {
            String line=CompletableFuture.supplyAsync(()->{try{return reader.readLine();}catch(IOException e){throw new UncheckedIOException(e);}}).get(60,TimeUnit.SECONDS);
            assertNotNull(line,"Actual source service did not start; inspect private source-error.log");
            int port=JSON.readTree(line).path("port").asInt();assertTrue(port>0);
            String endpoint="http://127.0.0.1:"+port;
            for(JsonNode row:catalog.path("cases")) {
                String caseId=row.path("caseId").asText(),assetId=row.path("assetId").asText();
                JsonNode asset=null;for(JsonNode candidate:manifest.path("assets"))if(candidate.path("assetId").asText().equals(assetId))asset=candidate;
                assertNotNull(asset);family.set(asset.path("operationId").asText());
                Path tool=Path.of(config.path("agentStoreRoot").asText()).resolve("community/finance/workshop/tools/original-calculation-readonly");
                ObjectNode request=(ObjectNode)JSON.readTree(tool.resolve("fixtures/"+assetId+".input.v1.json").toFile());
                ((ObjectNode)request.path("parameters")).put("action",row.path("action").asText());
                Path state=temp.resolve(caseId+"-private");Files.createDirectories(state);
                var binding=new OriginalCalculationFactoryV01.Binding("qualification","synthetic",caseId,caseId,assetId,asset.path("operationId").asText(),
                    manifest.path("sourceCommit").asText(),asset.path("sourceEntity").asText(),asset.path("filesDigest").asText(),
                    manifest.path("adapterDigest").asText(),asset.path("lockDigest").asText(),request);
                Path bindingFile=state.resolve("bindings.json");byte[] bindingBytes=JSON.writeValueAsBytes(new OriginalCalculationFactoryV01.Config("original-calculation-bindings.v1",List.of(binding)));Files.write(bindingFile,bindingBytes);
                var authority=new CalculationSourceScope.Authority("original-calculation-host-authority.v1","qualification",endpoint,60000,
                    bindingFile.toString(),CalculationNativeIntegrationTest.hash(bindingBytes),List.of(new CalculationSourceScope.Lease("qualification","synthetic",caseId,caseId,
                        token(caseId),Instant.now().plusSeconds(600),Set.of(OriginalCalculationProvider.SCOPE))));
                Path authorityFile=state.resolve("calculation-authority.json");Files.write(authorityFile,JSON.writeValueAsBytes(authority));
                Path artifact=artifact(temp.resolve(caseId+"-artifact"),harness,binding.capability(),CalculationNativeIntegrationTest.hash(bindingBytes));
                var sessionContext=new ManagedScenarioExtensionV01.Context(artifact,state,"native-"+caseId,"instance","qualification",
                    new ManagedScenarioExtensionV01.ModelConnection("OpenAI","controlled-test","http://127.0.0.1:"+model.getAddress().getPort()+"/v1","controlled"));
                int before=modelCalls.get();long start=System.nanoTime();
                try(var session=new CalculationSession(sessionContext)) {
                    Map<String,Object> result=session.call("calculate",JSON.readTree("{\"query\":\"按本次固定原资产完成计算，并保留结果依据。\"}"));
                    assertEquals("COMPLETED",result.get("status"),caseId+" "+JSON.writeValueAsString(result));
                    JsonNode raw=JSON.valueToTree(result).path("capabilityResult").path("data").path("originalReturn");
                    assertEquals(assetId,raw.path("assetId").asText());assertEquals(1,((Number)result.get("toolCallCount")).intValue());
                    assertEquals(2,modelCalls.get()-before);assertTrue(raw.path("rawDigest").asText().matches("[a-f0-9]{64}"));
                    String first=raw.path("runId").asText();
                    assertEquals("COMPLETED",session.call("result",JSON.createObjectNode()).get("status"));
                    if(caseId.equals("ftp-price")) {
                        Map<String,Object> recalculated=session.call("calculate",JSON.readTree("{\"query\":\"使用现金流方法重新计算\",\"parameters\":{\"action\":\"price\",\"method\":\"cashflow\"}}"));
                        assertEquals("COMPLETED",recalculated.get("status"),JSON.writeValueAsString(recalculated));
                        String next=JSON.valueToTree(recalculated).path("capabilityResult").path("data").path("originalReturn").path("runId").asText();
                        assertNotEquals(first,next);assertEquals(first,recalculated.get("previousRunId"));
                        assertEquals(2,JSON.valueToTree(session.call("history",JSON.createObjectNode())).path("items").size());
                        assertEquals(first,JSON.valueToTree(session.call("result",JSON.valueToTree(Map.of("runId",first)))).path("capabilityResult").path("data").path("originalReturn").path("runId").asText());
                        var failed=session.call("calculate",JSON.readTree("{\"query\":\"非法参数应拒绝\",\"parameters\":{\"action\":\"price\",\"module\":\"arbitrary\"}}"));
                        assertEquals("FAILED",failed.get("status"));assertThrows(IllegalStateException.class,()->session.call("result",JSON.createObjectNode()));
                        assertEquals("COMPLETED",session.call("result",JSON.valueToTree(Map.of("runId",first))).get("status"));
                        long previousRuns;try(var runs=Files.list(sourceState.resolve("runs"))){previousRuns=runs.count();}
                        var pending=CompletableFuture.supplyAsync(()->{try{return session.call("calculate",JSON.readTree("{\"query\":\"再次执行本次原FTP计算\"}"));}catch(Exception e){throw new CompletionException(e);}});
                        boolean started=false;
                        for(int i=0;i<600;i++){
                            try(var runs=Files.list(sourceState.resolve("runs"))){if(runs.count()>previousRuns){started=true;break;}}
                            if(pending.isDone())break;Thread.sleep(100);
                        }
                        assertTrue(started,"Actual original worker was not started before cancellation check");
                        var cancelled=session.call("cancel",JSON.createObjectNode());
                        assertEquals("CANCELLED",cancelled.get("status"));assertEquals(true,cancelled.get("workerStopped"));
                        assertEquals("CANCELLED",pending.get(20,TimeUnit.SECONDS).get("status"));
                        assertThrows(IllegalStateException.class,()->session.call("result",JSON.createObjectNode()));
                        ObjectNode revoked=(ObjectNode)JSON.valueToTree(authority);revoked.set("leases",JSON.createArrayNode());Files.write(authorityFile,JSON.writeValueAsBytes(revoked));
                        assertThrows(SecurityException.class,()->session.call("result",JSON.valueToTree(Map.of("runId",first))));
                        // Only this execution is held at the actual worker's initialization.
                        // Normal operations below still execute the unmodified original algorithms.
                        Path timeoutState=temp.resolve("ftp-timeout-private");Files.createDirectories(timeoutState);
                        ObjectNode shortAuthority=(ObjectNode)JSON.valueToTree(authority);shortAuthority.put("timeoutMs",15000);
                        Files.write(timeoutState.resolve("calculation-authority.json"),JSON.writeValueAsBytes(shortAuthority));
                        var timeoutContext=new ManagedScenarioExtensionV01.Context(artifact,timeoutState,"native-ftp-timeout","timeout-instance","qualification",sessionContext.model());
                        ProcessHandle heldWorker=null;
                        Files.writeString(timeoutMarker,"hold only the designated timeout execution",StandardCharsets.UTF_8);
                        try(var timedSession=new CalculationSession(timeoutContext)){
                            Set<String> earlier;try(var runs=Files.list(sourceState.resolve("runs"))){earlier=new HashSet<>(runs.map(p->p.getFileName().toString()).toList());}
                            var timedPending=CompletableFuture.supplyAsync(()->{try{return timedSession.call("calculate",JSON.readTree("{\"query\":\"原算法计算超时后不得披露旧结果\"}"));}catch(Exception e){throw new CompletionException(e);}});
                            Path gateProof=null;long admissionDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                            while(System.nanoTime()<admissionDeadline && gateProof==null){
                                try(var runs=Files.list(sourceState.resolve("runs"))){
                                    gateProof=runs.filter(p->!earlier.contains(p.getFileName().toString()))
                                        .map(p->p.resolve("qualification-worker-gate.json")).filter(Files::isRegularFile).findFirst().orElse(null);
                                }
                                if(gateProof==null){if(timedPending.isDone())break;Thread.sleep(10);}
                            }
                            assertNotNull(gateProof,"Actual worker initialization gate was not reached before the deadline");
                            JsonNode gate=JSON.readTree(gateProof.toFile());long workerPid=gate.path("pid").asLong();
                            assertEquals("worker.run entered",gate.path("phase").asText());
                            assertEquals(release.resolve("adapter/worker.py").toRealPath(),Path.of(gate.path("worker").asText()).toRealPath());
                            assertEquals(CalculationNativeIntegrationTest.hash(Files.readAllBytes(release.resolve("adapter/worker.py"))),gate.path("workerSha256").asText());
                            assertTrue(source.descendants().anyMatch(p->p.pid()==workerPid),"Gate PID must be owned by this source service");
                            heldWorker=ProcessHandle.of(workerPid).orElseThrow();assertTrue(heldWorker.isAlive());
                            assertFalse(timedPending.isDone(),"Held worker must not have completed naturally");
                            var timed=timedPending.get(30,TimeUnit.SECONDS);
                            assertEquals("TIMED_OUT",timed.get("status"),"Held original worker must time out");
                            assertEquals("ORIGINAL_CALCULATION_TIMEOUT",timed.get("errorCode"));
                            assertFalse(heldWorker.isAlive(),"The actual gated worker must exit after timeout");
                            List<String> created;try(var runs=Files.list(sourceState.resolve("runs"))){created=runs.map(p->p.getFileName().toString()).filter(x->!earlier.contains(x)).toList();}
                            assertEquals(1,created.size(),"The real source run must exist before timeout cleanup is asserted");
                            assertEquals(sourceState.resolve("runs").resolve(created.getFirst()).resolve("request.json").toRealPath(),Path.of(gate.path("request").asText()).toRealPath());
                            var requestStatus=java.net.http.HttpRequest.newBuilder(URI.create(endpoint+"/v1/calculations/status"))
                                .header("Authorization","Bearer "+token(caseId)).header("Content-Type","application/json")
                                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(Map.of("context",context(caseId),"runId",created.getFirst())))).build();
                            var stopped=java.net.http.HttpClient.newHttpClient().send(requestStatus,java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                            assertEquals(200,stopped.statusCode());JsonNode actualStatus=JSON.readTree(stopped.body());
                            assertTrue(actualStatus.path("workerStopped").asBoolean());assertTrue(Set.of("TIMED_OUT","CANCELLED").contains(actualStatus.path("status").asText()));
                            assertNotEquals("CANCELLED_BEFORE_START",actualStatus.path("errorCode").asText());
                            Thread.sleep(300);assertEquals("TIMED_OUT",timedSession.status().get("status"));
                            assertThrows(IllegalStateException.class,()->timedSession.call("result",JSON.createObjectNode()));
                            Path proof=Path.of(config.path("evidenceRoot").asText());
                            Files.write(proof.resolve("native-timeout-session.json"),JSON.writeValueAsBytes(Map.of("session",timed,"sourceStatus",actualStatus,
                                "workerGate",gate,"workerPidExited",!heldWorker.isAlive(),"faultInjection","TEST_ONLY_WORKER_INITIALIZATION_GATE")));
                        }finally{
                            Files.deleteIfExists(timeoutMarker);
                            if(heldWorker!=null && heldWorker.isAlive()){
                                heldWorker.destroyForcibly();heldWorker.onExit().get(10,TimeUnit.SECONDS);
                            }
                        }
                    }
                    evidence.add(Map.of("caseId",caseId,"assetId",assetId,"status","PASS","sourceRunId",first,
                        "rawDigest",raw.path("rawDigest").asText(),"modelRequests",2,"toolCalls",1,"elapsedMs",(System.nanoTime()-start)/1000000));
                }
            }
            assertEquals(11,evidence.size());
            Path output=Path.of(config.path("evidenceRoot").asText());Files.createDirectories(output);
            Files.write(output.resolve("native-original-cases.json"),JSON.writeValueAsBytes(Map.of("status","PASS","cases",evidence,
                "sourceProcessId",source.pid(),"releaseDigest",config.path("toolReleaseDigest").asText(),"realModelCalls",0,
                "historyRecalculationBadParametersCancellationAndRevocation","PASS","timeoutAndCleanup","PASS","numericalQualification","SEPARATE_REQUIRED_CHECK")));
        } finally {
            Files.deleteIfExists(timeoutMarker);
            source.descendants().forEach(ProcessHandle::destroyForcibly);
            model.stop(0);source.destroy();
            if(!source.waitFor(15,TimeUnit.SECONDS)){source.destroyForcibly();assertTrue(source.waitFor(5,TimeUnit.SECONDS));}
        }
    }
    private static Path sourceEntry(Path root)throws IOException{
        Path script=root.resolve("source-with-test-gate.py");
        Files.writeString(script,"""
            import runpy
            import sys
            from pathlib import Path
            server, marker, gate = sys.argv[1:4]
            arguments = sys.argv[4:]
            sys.path.insert(0, str(Path(server).resolve().parent))
            from execution import Run
            original_init = Run.__init__
            def controlled_init(self, command, cwd, env, deadline=60, run_id=None):
                if Path(marker).is_file():
                    command = [command[0], '-I', gate, *command[2:]]
                original_init(self, command, cwd, env, deadline, run_id)
            Run.__init__ = controlled_init
            sys.argv = [server, *arguments]
            runpy.run_path(server, run_name='__main__')
            """,StandardCharsets.UTF_8);
        return script;
    }
    private static Path workerGate(Path root)throws IOException{
        Path script=root.resolve("worker-initialization-gate.py");
        Files.writeString(script,"""
            import hashlib
            import json
            import os
            import runpy
            import sys
            import time
            from pathlib import Path
            worker = Path(sys.argv[1]).resolve()
            arguments = sys.argv[2:]
            request = Path(arguments[arguments.index('--request') + 1]).resolve()
            def hold(frame, event, arg):
                if event != 'call' or frame.f_code.co_name != 'run' or Path(frame.f_code.co_filename).resolve() != worker:
                    return None
                sys.settrace(None)
                proof = request.parent / 'qualification-worker-gate.json'
                pending = proof.with_suffix('.pending')
                pending.write_text(json.dumps(dict(pid=os.getpid(), worker=str(worker), request=str(request),
                    phase='worker.run entered', workerSha256=hashlib.sha256(worker.read_bytes()).hexdigest())), encoding='utf-8')
                os.replace(pending, proof)
                time.sleep(60)
                raise RuntimeError('QUALIFICATION_GATE_UNEXPECTEDLY_RELEASED')
            sys.argv = [str(worker), *arguments]
            sys.settrace(hold)
            runpy.run_path(str(worker), run_name='__main__')
            """,StandardCharsets.UTF_8);
        return script;
    }
    private static Map<String,String> context(String id){return Map.of("subjectRef","qualification","customerRef","synthetic","taskRef",id);}
    private static String token(String id){return "controlled-source-token-"+id+"-00000000000000000000000000000000";}
    private HttpServer model() throws IOException {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            modelCalls.incrementAndGet();JsonNode request=JSON.readTree(exchange.getRequestBody().readAllBytes());boolean returned=false;
            for(JsonNode message:request.path("messages"))if("tool".equals(message.path("role").asText()))returned=true;
            Object delta=returned?Map.of("role","assistant","content","已调用受管原算法，请核对本次结构化结果。"):
                Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","fixed-original","type","function","function",Map.of("name",("calculation_"+family.get()+"_query").replace('-','_'),"arguments","{}"))));
            String body="data: "+JSON.writeValueAsString(Map.of("id","controlled","choices",List.of(Map.of("index",0,"delta",delta))))+"\n\n"+
                "data: "+JSON.writeValueAsString(Map.of("id","controlled","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",returned?"stop":"tool_calls"))))+"\n\ndata: [DONE]\n\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();return server;
    }
    private static Path artifact(Path root,Path harness,String capability,String bindingDigest)throws Exception{
        Files.createDirectories(root);Map<String,byte[]> files=new TreeMap<>();
        files.put("component-plan.json",JSON.writeValueAsBytes(Map.of("runtime",Map.of("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15"),
            "profileRef",Map.of("id","original-calculation-readonly"),"capabilityPackRefs",List.of(Map.of("source","repo://capability/pack.yaml")),
            "domainPackRefs",List.of(Map.of("source","repo://method/pack.yaml")))));
        files.put("assets/capability/pack.yaml",Files.readAllBytes(harness.resolve("capability-packs/original-calculation-readonly/pack.yaml")));
        files.put("assets/method/pack.yaml",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-domain-pack.v1","packId","qualification-original-method","version","0.1.0",
            "profile","original-calculation-readonly","capabilities",List.of(capability),"skills",List.of(Map.of("directory","original","entry","skills/original/SKILL.md")))));
        files.put("assets/method/skills/original/SKILL.md",Files.readAllBytes(harness.resolve("domain-packs/original-calculation/skill/SKILL.md")));
        Map<String,String> hashes=new TreeMap<>();for(var item:files.entrySet()){Path file=root.resolve(item.getKey());Files.createDirectories(file.getParent());Files.write(file,item.getValue());hashes.put(item.getKey(),CalculationNativeIntegrationTest.hash(item.getValue()));}
        Files.write(root.resolve("service-lock.json"),JSON.writeValueAsBytes(Map.of("files",hashes,"scenarioExtension",Map.of("id",CalculationScenarioExtensionV01.ID),
            "providerConfiguration",Map.of(OriginalCalculationProvider.ID,Map.of("binding-file-sha256",bindingDigest)))));
        return root;
    }
}
