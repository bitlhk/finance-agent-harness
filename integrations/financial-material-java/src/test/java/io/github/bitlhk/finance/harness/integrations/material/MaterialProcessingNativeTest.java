package io.github.bitlhk.finance.harness.integrations.material;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.openjiuwen.core.runner.Runner;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.providers.material.FinancialMaterialProvider;
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

/** Genuine SDK/model loop -> Provider -> source fixture -> unchanged original PDF function. */
class MaterialProcessingNativeTest {
    static final ObjectMapper JSON=HarnessJson.mapper();
    @TempDir Path tmp;
    @Test void originalToolThroughRuntimeAndCurrentAuthority() throws Exception {
        String python=required("fh.python"),source=required("fh.materialServiceSource"),upstream=required("fh.originalMaterialSource");
        Path ready=tmp.resolve("ready.json"),authority=tmp.resolve("source-authority.json");
        String credential=UUID.randomUUID()+"-"+UUID.randomUUID();
        var lease=new LinkedHashMap<String,Object>();lease.put("principalId","reviewer");lease.put("subjectRef","customer");
        lease.put("taskRef","task");lease.put("sessionRef","session");lease.put("generation",1);
        lease.put("scopes",List.of("material:read","material:write","material:correct"));lease.put("expiresAt",Instant.now().plusSeconds(300).toString());lease.put("credential",credential);
        Files.write(authority,JSON.writeValueAsBytes(Map.of("schemaVersion","financial-material-authority.v1","ownerRef","test-owner","leases",List.of(lease))));
        String script="""
          import io,json,sys
          from pathlib import Path
          sys.path.insert(0,sys.argv[1])
          from publish_original_tool import publish
          from material_processing_service import Application,serve
          from reportlab.platypus import SimpleDocTemplate,Table,TableStyle
          from reportlab.lib import colors
          release=Path(sys.argv[3]).parent/'release'
          publish(Path(sys.argv[2]),release)
          app=Application(release,Path(sys.argv[3]).parent/'material-state',Path(sys.argv[4]))
          token=json.loads(Path(sys.argv[4]).read_text())['leases'][0]['credential']
          data=io.BytesIO();table=Table([['Item','Amount'],['Cash','750000']])
          table.setStyle(TableStyle([('GRID',(0,0),(-1,-1),1,colors.black)]))
          SimpleDocTemplate(data).build([table])
          doc=app.store.put(app.authority.current(token),data.getvalue(),'application/pdf')
          server=serve(app,0)
          Path(sys.argv[3]).write_text(json.dumps({'endpoint':'http://127.0.0.1:'+str(server.server_port),'document':doc,'toolReleaseDigest':app.engines.identity}))
          server.serve_forever()
          """;
        var builder=new ProcessBuilder(python,"-c",script,source,upstream,ready.toString(),authority.toString());
        builder.directory(tmp.toFile());builder.environment().remove("PYTHONPATH");builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
        builder.redirectErrorStream(true).redirectOutput(tmp.resolve("material-source.log").toFile());
        Process process=builder.start();HttpServer model=null;MaterialProcessingSession session=null;
        List<String> requests=new CopyOnWriteArrayList<>();
        try {
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(40).toNanos();
            while(!Files.isRegularFile(ready)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(Files.isRegularFile(ready),"Original source fixture did not start; see material-source.log");
            var startup=JSON.readTree(Files.readAllBytes(ready));
            model=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            model.createContext("/",x->{
                String raw=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);requests.add(raw);
                var input=JSON.readTree(raw);boolean returned=false;
                for(JsonNode message:input.path("messages"))if("tool".equals(message.path("role").asText()))returned=true;
                Map<String,Object> delta=returned?Map.of("role","assistant","content","已取得本次原材料表格，请核对原文。"):
                    Map.of("role","assistant","tool_calls",List.of(Map.of("index",0,"id","material-call","type","function","function",Map.of("name","financial_material_extract","arguments","{}"))));
                String body="data: "+JSON.writeValueAsString(Map.of("id","fixture","model","controlled","choices",List.of(Map.of("index",0,"delta",delta))))+"\n\n"+
                    "data: "+JSON.writeValueAsString(Map.of("id","fixture","model","controlled","choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",returned?"stop":"tool_calls"))))+"\n\ndata: [DONE]\n\n";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","text/event-stream");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
            });model.start();
            Path artifact=artifact();Path state=tmp.resolve("private-host");Files.createDirectories(state);
            var doc=startup.path("document");
            var binding=Map.of("principalId","reviewer","subjectRef","customer","sessionRef","session","taskRef","task",
                    "mode","PDF_TABLES","toolReleaseDigest",startup.path("toolReleaseDigest").asText());
            byte[] bindings=JSON.writeValueAsBytes(Map.of("schemaVersion","financial-material-bindings.v1","bindings",List.of(binding)));
            Path bindingFile=state.resolve("bindings.json");Files.write(bindingFile,bindings);
            var hostLease=new MaterialProcessingSession.Lease("reviewer","customer","task","session",credential,Instant.now().plusSeconds(300),Set.of("material:read"),1);
            var hostAuthority=new MaterialProcessingSession.Authority("financial-material-host-authority.v1","test-owner",startup.path("endpoint").asText(),30000,bindingFile.toString(),hash(bindings),List.of(hostLease));
            Path hostFile=state.resolve("material-host-authority.json");Files.write(hostFile,JSON.writeValueAsBytes(hostAuthority));
            var context=new ManagedScenarioExtensionV01.Context(artifact,state,"material-component-fixture","native-instance","test-owner",
                    new ManagedScenarioExtensionV01.ModelConnection("OpenAI","controlled-fixture-no-real-key","http://127.0.0.1:"+model.getAddress().getPort()+"/v1","controlled"));
            session=new MaterialProcessingSession(context);assertEquals("READY_NOT_EXECUTED",session.bootstrap().get("status"));
            var result=session.call("chat",JSON.readTree("{\"query\":\"按原方法提取这份材料的表格，并保留原文页码。\"}"));
            JsonNode tree=JSON.valueToTree(result);
            assertEquals("COMPLETED",result.get("status"),tree.toString());assertEquals(1,tree.path("toolCallCount").asInt());
            assertEquals("750000",tree.at("/capabilityResult/data/material/tables/0/rows/0/1").asText());
            assertEquals(doc.path("documentRef").asText(),tree.at("/capabilityResult/data/material/document/documentRef").asText());
            assertEquals(2,requests.size());assertTrue(requests.getFirst().contains("ORIGINAL-MATERIAL-PROCEDURE"));assertTrue(requests.get(1).contains("750000"));
            assertFalse(JSON.writeValueAsString(result).contains(credential));
            var revoked=(com.fasterxml.jackson.databind.node.ObjectNode)JSON.readTree(Files.readAllBytes(hostFile));revoked.set("leases",JSON.createArrayNode());Files.write(hostFile,JSON.writeValueAsBytes(revoked));
            var active=session;assertThrows(SecurityException.class,active::status);
            Path evidence=Path.of(required("fh.materialEvidence"));Files.createDirectories(evidence);
            JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("native-material-run.json").toFile(),result);
            JSON.writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("native-model-requests.json").toFile(),requests);
            Files.copy(tmp.resolve("material-source.log"),evidence.resolve("native-source.log"),StandardCopyOption.REPLACE_EXISTING);
        }finally{
            Path logs=Path.of(required("fh.materialEvidence"));Files.createDirectories(logs);
            if(Files.isRegularFile(tmp.resolve("material-source.log")))Files.copy(tmp.resolve("material-source.log"),logs.resolve("native-source.log"),StandardCopyOption.REPLACE_EXISTING);
            if(session!=null)session.close();if(model!=null)model.stop(0);Runner.stop();
            process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS))process.destroyForcibly().waitFor();
        }
    }
    private Path artifact() throws Exception {
        Path root=tmp.resolve("artifact");Files.createDirectories(root);Map<String,byte[]> files=new TreeMap<>();
        List<Map<String,Object>> capabilities=new ArrayList<>();
        for(String cap:List.of(FinancialMaterialProvider.EXTRACT,FinancialMaterialProvider.LOCATE))capabilities.add(Map.of("capabilityId",cap,
                "toolName",cap.replace('.','_'),"name","原材料处理","description","仅处理当前授权绑定的原材料","operation","QUERY",
                "inputSchema",Map.of("type","object","properties",Map.of(),"additionalProperties",false),"requiredScopes",List.of("material:read")));
        files.put("assets/cap/pack.yaml",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-capability-pack.v1","packId","material-cap","version","0.1.0","systemPrompt","Use the selected original material only.","capabilities",capabilities)));
        files.put("assets/domain/pack.yaml",JSON.writeValueAsBytes(Map.of("schemaVersion","finance-domain-pack.v1","packId","material-method","version","0.1.0","profile","financial-material-processing",
                "capabilities",List.of(FinancialMaterialProvider.EXTRACT,FinancialMaterialProvider.LOCATE),"skills",List.of(Map.of("directory","material-method","entry","skill/SKILL.md")))));
        files.put("assets/domain/skill/SKILL.md","# 原方法派生投影\nORIGINAL-MATERIAL-PROCEDURE\n提取原表格，保留原值、页码、缺失与部分失败；不得把提取成功当业务审核通过。\n".getBytes(StandardCharsets.UTF_8));
        var plan=Map.of("runtime",Map.of("templateRef","maven://com.openjiuwen:agent-core-java:0.1.15"),"profileRef",Map.of("id","financial-material-processing"),
                "capabilityPackRefs",List.of(Map.of("source","repo://cap/pack.yaml")),"domainPackRefs",List.of(Map.of("source","repo://domain/pack.yaml")),
                "capabilityBindings",Map.of(FinancialMaterialProvider.EXTRACT,FinancialMaterialProvider.ID,FinancialMaterialProvider.LOCATE,FinancialMaterialProvider.ID));
        files.put("component-plan.json",JSON.writeValueAsBytes(plan));Map<String,String> hashes=new TreeMap<>();
        for(var file:files.entrySet()){Path path=root.resolve(file.getKey());Files.createDirectories(path.getParent());Files.write(path,file.getValue());hashes.put(file.getKey(),hash(file.getValue()).substring(7));}
        Files.write(root.resolve("service-lock.json"),JSON.writeValueAsBytes(Map.of("files",hashes,"scenarioExtension",Map.of("id",MaterialProcessingExtensionV01.ID))));return root;
    }
    static String required(String name){String value=System.getProperty(name);assertNotNull(value,name+" required; do not skip actual native evidence");return value;}
    static String hash(byte[] bytes)throws Exception{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
