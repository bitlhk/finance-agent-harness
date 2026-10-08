package io.github.bitlhk.finance.harness.examples.wealth;

import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.*;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.employeeagent.EmployeeAgentWealthSession;
import io.github.bitlhk.finance.harness.providers.employeeagent.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicitly isolated synthetic Host. No Linggan SSO, production credentials, CRM writes or products. */
public final class ServicePreparationDemo {
    private ServicePreparationDemo() {}
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (Exception failure) {
            System.err.println("ISOLATED_HOST_START_FAILED: "+failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static void run(String[] args) throws Exception {
        if (args.length<3 || !args[0].equals("--synthetic"))
            throw new IllegalArgumentException("--synthetic <port> <private-evidence-directory> [--native-model]");
        int port=Integer.parseInt(args[1]);Path out=Path.of(args[2]);
        if (port==8090 || port==18148 || port==18290) throw new IllegalArgumentException("USE_ISOLATED_PORT");
        boolean nativeModel=List.of(args).contains("--native-model");
        var executor=Executors.newFixedThreadPool(8);
        var source=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        source.setExecutor(executor);
        source.createContext("/api/internal/platform-tools/mcp",x->{
            var request=HarnessJson.mapper().readTree(x.getRequestBody().readNBytes(16384));
            String customer=request.at("/params/arguments/customer_id").asText();
            if (!Set.of("syn-client-a","syn-client-b").contains(customer)
                    || !request.at("/params/name").asText().equals("prepare_wealth_previsit_context")) {
                reply(x,403,Map.of("error","CUSTOMER_DENIED"));return;
            }
            var facts=Map.of("schema","ea.wealth-previsit-context.v1","status","ready",
                "customer",Map.of("customerId",customer,"displayName",customer.endsWith("a")?"合成客户甲":"合成客户乙",
                    "totalAssets",customer.endsWith("a")?125000:238000,"riskLevel","R2","visitGoal","持有期回访"),
                "dataAsOf","2026-10-08T00:00:00Z","missingInputs",List.of("未取得近期收支明细"),
                "readiness",Map.of("status","READY"));
            reply(x,200,Map.of("jsonrpc","2.0","id",request.path("id").asText(),"result",Map.of(
                "content",List.of(Map.of("type","text","text","EA_WEALTH_PREVISIT_CONTEXT:"+HarnessJson.mapper().writeValueAsString(facts))),
                "_meta",Map.of("eaContextReceipt",Map.of("receiptId","syn-receipt-"+UUID.randomUUID(),
                    "receiptFingerprint","a".repeat(64),"createdAt",Instant.now().toString())))));
        });source.start();
        var runtime=new EmployeeAgentRuntimeIdentity("synthetic-runtime","service-preparation","synthetic-manager-role");
        var expires=Instant.now().plus(Duration.ofHours(8));
        var authority=new EmployeeAgentWealthSession.Authority("synthetic-owner",
            new EmployeeAgentIdentityBinding("synthetic-manager",runtime,"synthetic-grant-v1",expires),
            new BoundFinanceProvider.Grant("synthetic-manager",FinanceContextV1.PrincipalType.EMPLOYEE,
                Set.of("syn-client-a","syn-client-b"),Set.of("WEALTH_PREVISIT"),Set.of("wealth:previsit:read"),
                "synthetic-session","synthetic-grant-v1",expires),FinanceContextV1.AuthenticationLevel.BASIC);
        var session=new EmployeeAgentWealthSession("synthetic-owner","synthetic-session",runtime,()->authority,c->"fixture-only",
            new ManagedProviderFactoryV01.Connection(EmployeeAgentWealthFactoryV01.CONNECTION_KIND,
                URI.create("http://127.0.0.1:"+source.getAddress().getPort()),Duration.ofSeconds(5),()->null,()->null,c->{}),
            Set.of(ServicePreparation.CAPABILITY));
        ServicePreparation.Explanation explain=(goal,ids,context)->ids.stream().filter(s->!s.endsWith("customerId")).limit(3).toList();
        if(nativeModel) explain=nativeExplanation();
        Map<String,String> assets=assetManifest();
        var host=new ServicePreparation("synthetic-owner",session,()->new ServicePreparation.Mapping("synthetic-mapping-v1",
            Map.of("合成客户甲","syn-client-a","合成客户乙","syn-client-b")),explain,out,assets);
        var http=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);http.setExecutor(executor);
        String cookie=UUID.randomUUID().toString();String csrf=UUID.randomUUID().toString();
        http.createContext("/",x->{
            try {
                String authorityHeader="127.0.0.1:"+port;
                if(!authorityHeader.equals(x.getRequestHeaders().getFirst("Host"))) {reply(x,403,Map.of("error","HOST_REJECTED"));return;}
                String path=x.getRequestURI().getPath();
                if(path.equals("/") && x.getRequestMethod().equals("GET")) {
                    try(var in=ServicePreparationDemo.class.getResourceAsStream("/service-preparation.html")) {
                        byte[] html=Objects.requireNonNull(in).readAllBytes();
                        x.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");
                        x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; frame-ancestors 'none'");
                        x.sendResponseHeaders(200,html.length);x.getResponseBody().write(html);x.close();return;
                    }
                }
                if(path.equals("/api/bootstrap") && x.getRequestMethod().equals("GET")) {
                    x.getResponseHeaders().set("Set-Cookie","prep="+cookie+"; HttpOnly; SameSite=Strict; Path=/");
                    reply(x,200,Map.of("csrf",csrf,"customers",host.customers("synthetic-owner"),"assets",assets,
                        "mode",nativeModel?"SYNTHETIC_SOURCE_NATIVE_MODEL":"CONTROLLED_HTTP_FIXTURE"));return;
                }
                if(!("prep="+cookie).equals(x.getRequestHeaders().getFirst("Cookie"))) throw new SecurityException("LOGIN_REQUIRED");
                if(!"POST".equals(x.getRequestMethod()) || !csrf.equals(x.getRequestHeaders().getFirst("X-CSRF-Token"))
                        || !("http://"+authorityHeader).equals(x.getRequestHeaders().getFirst("Origin"))) throw new SecurityException("REQUEST_REJECTED");
                byte[] bytes=x.getRequestBody().readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException("INPUT_TOO_LONG");
                JsonNode input=HarnessJson.mapper().readTree(bytes);
                Object result=switch(path) {
                    case "/api/select" -> {exact(input,Set.of("customer","goal"));yield Map.of("generation",host.select("synthetic-owner",input.path("customer").asText(),input.path("goal").asText()));}
                    case "/api/prepare" -> {exact(input,Set.of("generation"));yield host.prepare("synthetic-owner",input.path("generation").asLong());}
                    case "/api/read" -> {exact(input,Set.of("reportId"));yield host.read("synthetic-owner",input.path("reportId").asText());}
                    case "/api/review" -> {exact(input,Set.of("reportId","revision","opinion"));yield host.review("synthetic-owner",input.path("reportId").asText(),input.path("revision").asInt(),input.path("opinion").asText());}
                    default -> throw new IllegalArgumentException("NOT_FOUND");
                };reply(x,200,result);
            } catch(SecurityException denied){reply(x,403,Map.of("error",denied.getMessage()));}
              catch(IllegalArgumentException|IllegalStateException invalid){reply(x,409,Map.of("error","REQUEST_OR_STATE_REJECTED"));}
              catch(Exception failure){reply(x,500,Map.of("error","REPORT_OPERATION_FAILED"));}
        });http.start();
        Runtime.getRuntime().addShutdownHook(new Thread(()->{host.close();http.stop(0);source.stop(0);executor.shutdownNow();if(nativeModel)Runner.stop();}));
        System.out.println("ISOLATED_SYNTHETIC_HOST http://127.0.0.1:"+port+"/ mode="+(nativeModel?"NATIVE_MODEL":"CONTROLLED_FIXTURE"));
    }
    static void exact(JsonNode value,Set<String> expected) {
        var fields=new HashSet<String>();value.fieldNames().forEachRemaining(fields::add);
        if(!value.isObject() || !fields.equals(expected))throw new IllegalArgumentException("HOST_OWNED_FIELDS");
    }
    static Map<String,String> assetManifest() throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("capability-packs")))root=root.getParent();
        if(root==null)throw new IllegalArgumentException("HARNESS_REPOSITORY_REQUIRED");
        var assets=new LinkedHashMap<String,String>();
        for(String file:List.of("capability-packs/wealth-manager-previsit/pack.yaml",
                "domain-packs/wealth/service-preparation/pack.yaml",
                "domain-packs/wealth/service-preparation/skills/customer-manager-service-preparation/SKILL.md",
                "examples/solutions/customer-manager-service-preparation/solution.yaml"))
            assets.put(file,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(file)))));
        for(Class<?> type:List.of(EmployeeAgentWealthProvider.class,EmployeeAgentWealthSession.class,ServicePreparation.class)) {
            try(var in=type.getResourceAsStream(type.getSimpleName()+".class")) {
                assets.put(type.getName()+".class",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(in).readAllBytes())));
            }
        }
        assets.put("provider","employee-agent-reference@0.1.0-SNAPSHOT");assets.put("runtime","openjiuwen-java@0.1.15");
        assets.put("jdk",Runtime.version().toString());return assets;
    }
    static ServicePreparation.Explanation nativeExplanation() {
        String key=System.getenv("FINANCE_AGENT_MODEL_API_KEY");String base=System.getenv("FINANCE_AGENT_MODEL_BASE_URL");
        String model=System.getenv("FINANCE_AGENT_MODEL");
        if(key==null||base==null||model==null)throw new IllegalStateException("MODEL_CONFIGURATION_REQUIRED");
        return nativeExplanation(key,base,model);
    }
    static ServicePreparation.Explanation nativeExplanation(String key,String base,String model) {
        var agent=new ReActAgent(AgentCard.builder().id("preparation-focus").name("服务准备重点整理").description("仅选择已核实事实引用").build());
        var config=ReActAgentConfig.builder().promptTemplate(List.of(Map.of("role","system","content",
            "按服务目标从提供的 factIds 中选择1至3个沟通重点。只输出JSON字符串数组，不输出事实数值、产品或文字说明。")))
            .maxIterations(1).build().configureModelClient("OpenAI",key,base,model,true);
        config.getModelConfigObj().setTemperature(0.1);agent.configure(config);
        if(!Runner.start())throw new IllegalStateException("RUNTIME_START_FAILED");
        return (goal,ids,context)->{
            String sid=UUID.randomUUID().toString();
            try {
                Object value=Runner.runAgent(agent,Map.of("query",HarnessJson.mapper().writeValueAsString(Map.of(
                    "goal",goal,"factIds",ids,"facts",context))),sid,null);
                String text=render(value);JsonNode refs=HarnessJson.mapper().readTree(text);
                if(!refs.isArray())throw new IllegalArgumentException("INVALID_FOCUS_OUTPUT");
                var result=new ArrayList<String>();for(JsonNode ref:refs){if(!ref.isTextual())throw new IllegalArgumentException("INVALID_FOCUS_REF");result.add(ref.asText());}
                return result;
            }finally{Runner.release(sid);}
        };
    }
    private static String render(Object value) {
        if(value instanceof WorkflowOutput output)return render(output.getResult());
        if(value instanceof Map<?,?> map)for(String k:List.of("content","output","response","result"))if(map.get(k)!=null)return render(map.get(k));
        return String.valueOf(value);
    }
    static void reply(HttpExchange x,int code,Object body) throws java.io.IOException {
        byte[] bytes=HarnessJson.mapper().writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.getResponseHeaders().set("Cache-Control","no-store");
        x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);x.close();
    }
}
