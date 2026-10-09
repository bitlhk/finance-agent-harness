package io.github.bitlhk.finance.harness.integrations.customermanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.ManagedScenarioExtensionV01;
import io.github.bitlhk.finance.harness.examples.wealth.*;
import io.github.bitlhk.finance.harness.providers.customercrm.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Declarative managed-Host extension. Browser input cannot provide authority, credentials or asset paths. */
public final class CustomerManagerScenarioExtension implements ManagedScenarioExtensionV01 {
    public static final String ID = "customer-manager-service-preparation.v1";
    private static final Set<String> ACTIONS = Set.of("select", "prepare", "read", "review", "versions", "read-version", "replay");

    @Override public String id() { return ID; }
    @Override public Set<String> actions() { return ACTIONS; }

    @Override public Session open(Context context) throws Exception {
        Path mappingFile = outsideArtifact(requiredPath("FH_CRM_EMPLOYEE_MAPPING"), context.artifactRoot());
        Path reports = outsideArtifact(context.privateStateRoot().resolve("service-preparation-reports"), context.artifactRoot());
        String owner = required(context.ownerRef(), "OWNER_REQUIRED");
        var mapping = (java.util.function.Supplier<CustomerCrmEmployeeMapping>) () -> CustomerCrmEmployeeMapping.load(mappingFile, owner);
        CustomerCrmClient client = new CustomerCrmClient(URI.create(requiredEnv("FH_CRM_URL")),
                Duration.ofMillis(Long.parseLong(System.getenv().getOrDefault("FH_CRM_TIMEOUT_MILLIS", "8000"))),
                () -> requiredEnv("FH_CRM_SERVICE_TOKEN"));
        CustomerCrmProvider provider = new CustomerCrmProvider(owner, client, () -> mapping.get().authority(), Clock.systemUTC());
        String sessionId = UUID.randomUUID().toString();
        var access = new DirectCrmPreparationAccess(owner, sessionId, provider);
        InstalledPreparationMethod method = installedMethod(context.artifactRoot());
        ServicePreparation task = new ServicePreparation(owner, access,
                () -> { var value=mapping.get(); return new ServicePreparation.Mapping(value.authority().revision(), value.customers()); },
                new OpenJiuwenReportGenerator(requiredEnv("FH_MODEL_KEY"), requiredEnv("FH_MODEL_URL"), requiredEnv("FH_MODEL_NAME")),
                reports, Map.of("capability", "wealth-manager-previsit@0.1.0", "provider", "customer-crm-readonly@0.1.0-SNAPSHOT",
                        "runtime", "openjiuwen-java@0.1.15"), method);
        return new Managed(owner, sessionId, task, method);
    }

    private static final class Managed implements Session {
        private final String owner, sessionId;
        private final ServicePreparation task;
        private final InstalledPreparationMethod method;
        private volatile boolean closed;
        Managed(String owner, String sessionId, ServicePreparation task, InstalledPreparationMethod method) {
            this.owner=owner; this.sessionId=sessionId; this.task=task; this.method=method;
        }
        @Override public String sessionId() { return sessionId; }
        @Override public Map<String,Object> bootstrap() {
            current(); return Map.of("scenarioId", ID, "sessionId", sessionId, "customers", task.customers(owner),
                    "steps", List.of("CUSTOMER_AND_GOAL", "FACT_COLLECTION", "PREPARATION_REPORT", "MANAGER_REVIEW"),
                    "method", Map.of("id", method.id(), "version", method.version(), "digest", method.digest(), "lockDigest", method.lockDigest()));
        }
        @Override public Map<String,Object> call(String action, JsonNode body) throws Exception {
            current(); if (!ACTIONS.contains(action) || body == null || !body.isObject()) throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            return switch (action) {
                case "select" -> {
                    exact(body,"customerAlias","goal");
                    yield Map.of("generation",task.select(owner,text(body,"customerAlias"),text(body,"goal")),"status","SELECTED");
                }
                case "prepare" -> { exact(body,"generation"); yield value(task.prepare(owner,integer(body,"generation"))); }
                case "read", "replay" -> { exact(body,"reportId"); yield value(task.read(owner,text(body,"reportId"))); }
                case "review" -> { exact(body,"reportId","revision","opinion"); yield value(task.review(owner,text(body,"reportId"),integer(body,"revision"),text(body,"opinion"))); }
                case "versions" -> { exact(body,"reportId"); yield Map.of("reportId",text(body,"reportId"),"versions",task.versions(owner,text(body,"reportId"))); }
                case "read-version" -> { exact(body,"reportId","revision"); yield value(task.readVersion(owner,text(body,"reportId"),integer(body,"revision"))); }
                default -> throw new IllegalArgumentException("SCENARIO_ACTION_INVALID");
            };
        }
        @Override public Map<String,Object> status() {
            var value=new LinkedHashMap<String,Object>(bootstrap());value.put("status","ACTIVE");return value;
        }
        @Override public void close() { if(!closed){closed=true;task.close();} }
        private void current(){if(closed)throw new SecurityException("SCENARIO_SESSION_CLOSED");}
    }

    private static InstalledPreparationMethod installedMethod(Path root) throws Exception {
        JsonNode lock = HarnessJson.mapper().readTree(Files.readAllBytes(root.resolve("service-lock.json")));
        String lockDigest = "sha256:" + requiredEnv("FH_EXPECTED_LOCK");
        String id, version, relative;
        if (lock.has("method")) {
            id=lock.path("method").path("assetId").asText(); version=lock.path("method").path("version").asText();
            relative="assets/private-methods/"+id+"/"+version+"/skills/"+id+"/SKILL.md";
        } else {
            id="customer-manager-service-preparation"; version="0.1.0";
            relative="assets/domain-packs/wealth/service-preparation/skills/customer-manager-service-preparation/SKILL.md";
        }
        String digest=lock.path("files").path(relative).asText();
        if (digest.isBlank()) throw new SecurityException("INSTALLED_METHOD_NOT_LOCKED");
        return InstalledPreparationMethod.load(id,version,root.resolve(relative),"sha256:"+digest,lockDigest);
    }
    private static Map<String,Object> value(JsonNode node) { return HarnessJson.mapper().convertValue(node,new TypeReference<>(){}); }
    private static Path requiredPath(String name){return Path.of(requiredEnv(name)).toAbsolutePath().normalize();}
    private static Path outsideArtifact(Path path,Path artifact){if(path.startsWith(artifact.toAbsolutePath().normalize()))throw new SecurityException("PRIVATE_STATE_OUTSIDE_ARTIFACT_REQUIRED");return path;}
    private static String requiredEnv(String name){return required(System.getenv(name),name+"_REQUIRED");}
    private static String required(String value,String code){if(value==null||value.isBlank())throw new IllegalArgumentException(code);return value.trim();}
    private static String text(JsonNode body,String name){return required(body.path(name).asText(null),"SCENARIO_FIELD_REQUIRED");}
    private static int integer(JsonNode body,String name){if(!body.path(name).canConvertToInt())throw new IllegalArgumentException("SCENARIO_FIELD_INVALID");return body.path(name).asInt();}
    private static void exact(JsonNode body,String... names){Set<String> expected=Set.of(names),actual=new HashSet<>();body.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw new IllegalArgumentException("SCENARIO_BODY_INVALID");}
}
