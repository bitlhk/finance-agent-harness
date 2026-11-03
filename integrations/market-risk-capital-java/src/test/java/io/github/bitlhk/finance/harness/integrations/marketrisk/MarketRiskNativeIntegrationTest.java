package io.github.bitlhk.finance.harness.integrations.marketrisk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.runner.Runner;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.providers.marketrisk.MarketRiskCapitalProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SDK -> HTTP -> original Python function. Fixed test model, synthetic positions. */
class MarketRiskNativeIntegrationTest {
    static final ObjectMapper JSON=HarnessJson.mapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    static final String CAP="market.risk.capital.query";
    @TempDir Path temporary;

    @Test @Timeout(90) void nativeMethodToolLoopIsTaskBoundAndFailureIsNotSuccess() throws Exception {
        Path release=Path.of(requiredProperty("fh.originalToolRelease"));
        String releaseDigest=requiredProperty("fh.originalToolReleaseDigest");
        assertTrue(releaseDigest.matches("sha256:[a-f0-9]{64}"));
        JsonNode manifest=JSON.readTree(Files.readAllBytes(release.resolve("release-manifest.json")));
        assertEquals(releaseDigest,manifest.path("releaseDigest").asText());
        Path sessions=temporary.resolve("tool-sessions.json"),ready=temporary.resolve("tool-ready.json");
        Map<String,Object> alpha=sourceSession("a",1),beta=sourceSession("b",2);
        writeSessions(sessions,alpha,beta);
        String python=release.resolve(System.getProperty("os.name").startsWith("Windows")?".venv/Scripts/python.exe":".venv/bin/python").toString();
        ProcessBuilder command=new ProcessBuilder(python,"-B",release.resolve("market_risk_capital_service.py").toString(),
                "--release-root",release.toString(),"--expected-release-digest",releaseDigest,"--session-file",sessions.toString(),
                "--ready-file",ready.toString());
        command.directory(temporary.toFile());command.environment().remove("PYTHONPATH");
        command.environment().put("PYTHONUTF8","1");command.environment().put("PYTHONDONTWRITEBYTECODE","1");
        command.redirectErrorStream(true).redirectOutput(temporary.resolve("original-tool.log").toFile());
        Process tool=command.start();HttpServer model=null;MarketRiskSession first=null,second=null;
        List<String> modelRequests=new CopyOnWriteArrayList<>();
        try {
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(25).toNanos();
            while(!Files.isRegularFile(ready) && tool.isAlive() && System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(Files.isRegularFile(ready),"Original tool did not start; see owned original-tool.log");
            JsonNode startup=JSON.readTree(Files.readAllBytes(ready));assertEquals(releaseDigest,startup.path("toolReleaseDigest").asText());
            String endpoint=startup.path("endpoint").asText();
            model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            model.createContext("/",exchange->{
                String raw=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);modelRequests.add(raw);
                JsonNode request=JSON.readTree(raw);boolean returned=false;
                for(JsonNode message:request.path("messages"))if("tool".equals(message.path("role").asText()))returned=true;
                Map<String,Object> delta=returned?Map.of("role","assistant","content","请核对本次原算法返回的资本结果。"):
                        Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","controlled-call-1","type","function",
                                "function",Map.of("name","market_risk_capital_query","arguments","{}"))));
                String body="data: "+JSON.writeValueAsString(Map.of("id","fixture","model","controlled","choices",List.of(Map.of("index",0,"delta",delta))))+"\n\n"+
                        "data: "+JSON.writeValueAsString(Map.of("id","fixture","model","controlled","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",returned?"stop":"tool_calls"))))+
                        "\n\ndata: [DONE]\n\n";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });model.start();
            String modelBase="http://127.0.0.1:"+model.getAddress().getPort()+"/v1";
            Path artifact=artifact();
            first=new MarketRiskSession(context(artifact,"a",alpha,manifest,releaseDigest,endpoint,modelBase));
            second=new MarketRiskSession(context(artifact,"b",beta,manifest,releaseDigest,endpoint,modelBase));
            assertEquals("READY_NOT_EXECUTED",first.bootstrap().get("status"));
            Map<String,Object> a=first.call("chat",JSON.readTree("{\"query\":\"按已安装方法查询本次标准法资本。\"}"));
            Map<String,Object> b=second.call("chat",JSON.readTree("{\"query\":\"按已安装方法查询本次标准法资本。\"}"));
            JsonNode aj=JSON.valueToTree(a),bj=JSON.valueToTree(b);
            assertEquals("COMPLETED",a.get("status"),aj.toString());assertEquals("COMPLETED",b.get("status"),bj.toString());
            assertEquals(120000,aj.at("/capabilityResult/data/capital/values/total").asDouble());
            assertEquals(240000,bj.at("/capabilityResult/data/capital/values/total").asDouble());
            assertEquals("task-a",aj.at("/capabilityResult/data/capital/source/taskRef").asText());
            assertEquals("task-b",bj.at("/capabilityResult/data/capital/source/taskRef").asText());
            assertEquals(1,aj.path("toolCallCount").asInt());assertEquals(1,bj.path("toolCallCount").asInt());
            assertEquals(4,modelRequests.size());assertTrue(modelRequests.getFirst().contains("ORIGINAL-PROCEDURE-MARKER"));
            assertTrue(modelRequests.get(1).contains("120000"));
            assertEquals(aj.at("/capabilityResult/data/originalReturn/values"),aj.at("/capabilityResult/data/capital/values"));
            assertTrue(bj.at("/capabilityResult/data/capital/source/asOf").isNull());
            assertFalse(JSON.writeValueAsString(a).contains(alpha.get("credential").toString()));
            // Managed artifacts pin configuration independently from host authority.
            Path pinnedArtifact=temporary.resolve("pinned-artifact");
            try(var files=Files.walk(artifact)) {
                for(Path file:files.toList()) {
                    Path target=pinnedArtifact.resolve(artifact.relativize(file));
                    if(Files.isDirectory(file))Files.createDirectories(target);else Files.copy(file,target);
                }
            }
            Path pinnedState=temporary.resolve("pinned-state");Files.createDirectories(pinnedState);
            Path pinnedAuthorityPath=temporary.resolve("state-a/market-risk-authority.json");
            Files.copy(pinnedAuthorityPath,pinnedState.resolve("market-risk-authority.json"));
            String bindingDigest=JSON.readTree(Files.readAllBytes(pinnedAuthorityPath)).path("bindingsDigest").asText().substring(7);
            ObjectNode pinnedLock=(ObjectNode)JSON.readTree(Files.readAllBytes(pinnedArtifact.resolve("service-lock.json")));
            pinnedLock.putObject("providerConfiguration").putObject(MarketRiskCapitalProvider.ID)
                    .put("binding-file-sha256",bindingDigest);
            Files.write(pinnedArtifact.resolve("service-lock.json"),JSON.writeValueAsBytes(pinnedLock));
            var pinnedContext=new ManagedScenarioExtensionV01.Context(pinnedArtifact,pinnedState,"native-component-fixture","pinned","owner-a",
                    new ManagedScenarioExtensionV01.ModelConnection("OpenAI","fixed-test-key",modelBase,"controlled"));
            try(var pinned=new MarketRiskSession(pinnedContext)) {assertEquals("READY_NOT_EXECUTED",pinned.bootstrap().get("status"));}
            ((ObjectNode)pinnedLock.at("/providerConfiguration/"+MarketRiskCapitalProvider.ID)).put("binding-file-sha256","0".repeat(64));
            Files.write(pinnedArtifact.resolve("service-lock.json"),JSON.writeValueAsBytes(pinnedLock));
            assertEquals("MARKET_RISK_CONFIGURATION_NOT_LOCKED",
                    assertThrows(SecurityException.class,()->new MarketRiskSession(pinnedContext)).getMessage());
            // The same session must not reuse its previous success when source data goes missing.
            alpha.remove("positions");writeSessions(sessions,alpha,beta);
            var failed=first.call("chat",JSON.readTree("{\"query\":\"再次查询本次资本。\"}"));
            JsonNode fj=JSON.valueToTree(failed);assertEquals("FAILED",failed.get("status"));
            assertEquals("FAILED",fj.at("/capabilityResult/status").asText());assertTrue(fj.at("/capabilityResult/data").isEmpty());
            assertFalse(failed.get("response").toString().contains("已完成"));
            Path output=Path.of("target/native-evidence");Files.createDirectories(output);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("owner-a.json").toFile(),a);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("owner-b.json").toFile(),b);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("source-missing.json").toFile(),failed);
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.resolve("model-requests.json").toFile(),modelRequests);
            // Current access is required even to read the last result.
            Path ownerState=temporary.resolve("state-a/market-risk-authority.json");
            ObjectNode authority=(ObjectNode)JSON.readTree(Files.readAllBytes(ownerState));authority.set("leases",JSON.createArrayNode());
            Files.write(ownerState,JSON.writeValueAsBytes(authority));MarketRiskSession active=first;
            assertThrows(SecurityException.class,active::status);
        } finally {
            if(first!=null)first.close();if(second!=null)second.close();if(model!=null)model.stop(0);
            Runner.stop();
            tool.descendants().forEach(ProcessHandle::destroyForcibly);tool.destroyForcibly();tool.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private Map<String,Object> sourceSession(String suffix,int scale) {
        Map<String,Object> source=new TreeMap<>();
        for(String key:List.of("principalId","subjectRef","taskRef","sessionRef","snapshotRef")) {
            String prefix=switch(key){case "principalId"->"owner";case "subjectRef"->"portfolio";case "taskRef"->"task";case "sessionRef"->"session";default->"snapshot";};
            source.put(key,prefix+"-"+suffix);
        }
        source.put("credential",UUID.randomUUID()+"-"+UUID.randomUUID());source.put("expiresAt",Instant.now().plusSeconds(300).toString());
        source.put("capabilities",List.of("market-risk.capital.query"));
        source.put("positions",List.of(new TreeMap<>(Map.of("market_value",1000000*scale,"currency","CNY","direction",1,"delta",1,"risk_category","股票","factor","EQ_INDEX"))));
        source.put("fxRates",new TreeMap<>(Map.of("CNY",1)));source.put("sourceAsOf",suffix.equals("b")?null:"2026-09-06");return source;
    }
    @SafeVarargs private void writeSessions(Path path,Map<String,Object>... sessions) throws Exception {
        Files.write(path,JSON.writeValueAsBytes(Map.of("schemaVersion","finance-original-tool-sessions.v1","sessions",List.of(sessions))));
    }
    private ManagedScenarioExtensionV01.Context context(Path artifact,String suffix,Map<String,Object> source,JsonNode manifest,
            String releaseDigest,String endpoint,String modelBase) throws Exception {
        Path state=temporary.resolve("state-"+suffix);Files.createDirectories(state);
        Map<String,Object> snapshot=new TreeMap<>();snapshot.put("positions",source.get("positions"));snapshot.put("fxRates",source.get("fxRates"));snapshot.put("sourceAsOf",source.get("sourceAsOf"));
        Map<String,Object> binding=new TreeMap<>();for(String key:List.of("principalId","subjectRef","taskRef","sessionRef","snapshotRef"))binding.put(key,source.get(key));
        binding.put("snapshotDigest",hash(JSON.writeValueAsBytes(snapshot)));binding.put("toolReleaseDigest",releaseDigest);
        binding.put("upstreamRevision",manifest.path("upstreamRevision").asText());binding.put("parametersDigest",manifest.at("/files/original~1mktrisk_engine~1config.yaml").asText());
        binding.put("paramVersion",manifest.path("paramVersion").asText());binding.put("engineVersion",manifest.path("engineVersion").asText());
        byte[] bindings=JSON.writeValueAsBytes(Map.of("schemaVersion","market-risk-capital-bindings.v1","bindings",List.of(binding)));
        Path bindingFile=state.resolve("bindings.json");Files.write(bindingFile,bindings);
        var lease=new MarketRiskSession.Lease((String)source.get("principalId"),(String)source.get("subjectRef"),(String)source.get("taskRef"),
                (String)source.get("sessionRef"),(String)source.get("snapshotRef"),(String)source.get("credential"),Instant.parse((String)source.get("expiresAt")),Set.of("market-risk:read"));
        var authority=new MarketRiskSession.Authority("market-risk-host-authority.v1","owner-"+suffix,endpoint,5000,bindingFile.toAbsolutePath().toString(),hash(bindings),List.of(lease));
        Files.write(state.resolve("market-risk-authority.json"),JSON.writeValueAsBytes(authority));
        return new ManagedScenarioExtensionV01.Context(artifact,state,"native-component-fixture",suffix,"owner-"+suffix,
                new ManagedScenarioExtensionV01.ModelConnection("OpenAI","fixed-test-key",modelBase,"controlled"));
    }
    private Path artifact() throws Exception {
        Path root=temporary.resolve("artifact");Files.createDirectories(root);
        var capRef=Map.of("id","capital-readonly","version","0.1.0","source","repo://cap/capability-pack.v1.json");
        var domainRef=Map.of("id","capital-method","version","0.1.0","source","repo://domain/domain-pack.v1.json");
        Map<String,Object> plan=Map.of("runtime",Map.of("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15"),
                "profileRef",Map.of("id","market-risk-capital-readonly"),"domainPackRefs",List.of(domainRef),
                "capabilityPackRefs",List.of(capRef),"capabilityBindings",Map.of(CAP,"market-risk-capital-original"));
        Map<String,byte[]> files=new TreeMap<>();files.put("component-plan.json",JSON.writeValueAsBytes(plan));
        files.put("assets/cap/capability-pack.v1.json",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-capability-pack.v1","packId","capital-readonly",
                "version","0.1.0","systemPrompt","Use only the installed original calculation.","capabilities",List.of(Map.of("capabilityId",CAP,"toolName","market_risk_capital_query",
                        "name","原标准法资本","description","调用本次获准原标准法资本工具","operation","QUERY","inputSchema",Map.of("type","object","properties",Map.of(),"additionalProperties",false),"requiredScopes",List.of("market-risk:read"))))));
        files.put("assets/domain/domain-pack.v1.json",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-domain-pack.v1","packId","capital-method","version","0.1.0",
                "profile","market-risk-capital-readonly","capabilities",List.of(CAP),"skills",List.of(Map.of("directory","capital-method","entry","skills/capital-method/SKILL.md")))));
        files.put("assets/domain/skills/capital-method/SKILL.md","# Derived procedure\nORIGINAL-PROCEDURE-MARKER\n调用已安装原标准法工具，保留版本与数据日期，不自行替代计算。\n".getBytes(StandardCharsets.UTF_8));
        Map<String,String> hashes=new TreeMap<>();for(var item:files.entrySet()) {
            Path file=root.resolve(item.getKey());Files.createDirectories(file.getParent());Files.write(file,item.getValue());hashes.put(item.getKey(),hash(item.getValue()).substring(7));
        }
        Files.write(root.resolve("service-lock.json"),JSON.writeValueAsBytes(Map.of("files",hashes,"scenarioExtension",Map.of("id",MarketRiskScenarioExtensionV01.ID))));return root;
    }
    private static String requiredProperty(String name) { String value=System.getProperty(name);assertNotNull(value,name+" required: native evidence cannot be skipped");return value; }
    private static String hash(byte[] bytes) throws Exception { return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
