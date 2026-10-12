package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.integrations.agentscope.*;
import java.nio.file.*;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Actual artifact admission. This command has no bank credentials or model invocation. */
public final class AgentScopeAdmission {
    static RuntimeQualification qualification() throws Exception {
        return RuntimeQualification.load(Path.of(BankAccess.env("FH_AGENTSCOPE_QUALIFICATION_FILE")),
                BankAccess.env("FH_AGENTSCOPE_QUALIFICATION_SHA256"));
    }
    static Map<String, DescribedFinanceProvider> providers(Path root, ManagedProviderFactoryV01.Connection connection) {
        var selected = ComponentAdmission.selectedProviders(root);
        var factories = new HashMap<String, ManagedProviderFactoryV01>();
        for (var factory : ServiceLoader.load(ManagedProviderFactoryV01.class))
            if (factories.putIfAbsent(factory.providerId(), factory) != null)
                throw new IllegalArgumentException("PROVIDER_FACTORY_DUPLICATE");
        var values = new LinkedHashMap<String, DescribedFinanceProvider>();
        for (var entry : selected.entrySet()) {
            var factory = factories.get(entry.getKey());
            if (factory == null || !factory.requirements().keySet().containsAll(entry.getValue()))
                throw new IllegalArgumentException("PROVIDER_FACTORY_CAPABILITY_UNAVAILABLE");
            if (!factory.connectionKind().equals(connection.kind()))
                throw new IllegalArgumentException("PROVIDER_FACTORY_CONNECTION_UNSUPPORTED");
            if (!factory.dependencies().isEmpty()) throw new IllegalArgumentException("PROVIDER_DEPENDENCIES_NOT_QUALIFIED");
            var provider = factory.create(connection, Map.of());
            if (!entry.getKey().equals(provider.descriptor().providerId()))
                throw new IllegalArgumentException("PROVIDER_FACTORY_IDENTITY_MISMATCH");
            values.put(entry.getKey(), provider);
        }
        return Map.copyOf(values);
    }
    static AgentScopeQueryAssembler.Prepared prepare(Path root, Map<String, DescribedFinanceProvider> providers) throws Exception {
        ComponentAdmission.definitions(root);
        locked(root, "solution.json");
        locked(root, "agentscope-support.json");
        var prepared = new AgentScopeQueryAssembler().prepare(root, root.resolve("assets"), root.resolve("solution.json"),
                root.resolve("agentscope-support.json"), providers, qualification());
        ComponentAdmission.validate(root, prepared.snapshot());
        return prepared;
    }
    static Map<String, Object> plan(Path root) throws Exception {
        var connection = new ManagedProviderFactoryV01.Connection("synthetic-bank-http.v1", URI.create("http://127.0.0.1:1"),
                Duration.ofSeconds(10), () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); }, () -> null, ignored -> {});
        var prepared = prepare(root, providers(root, connection));
        var snapshot = new LinkedHashMap<>(prepared.snapshot());
        snapshot.put("hostRequiredSourceScopes", ManagedProviderFactoriesV01.installed().sourceScopes(ComponentAdmission.selectedProviders(root)));
        snapshot.put("hostAuthorizationPolicy", "ALL_SELECTED_AND_SOURCE_SCOPES_EACH_MODEL_AND_TOOL_BOUNDARY");
        snapshot.put("hostObservationAssessment", HostObservationAdmission.check(root,
                Path.of(System.getenv().getOrDefault("FH_HISTORY_DIR", root.resolveSibling("execution-history").toString())),
                !"false".equals(System.getenv("FH_HISTORY_ENABLED"))));
        return Collections.unmodifiableMap(snapshot);
    }
    static void locked(Path root, String relative) throws Exception {
        var lock = HarnessJson.mapper().readTree(root.resolve("service-lock.json").toFile());
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)
                || !path.toRealPath().startsWith(root.toRealPath()) || Files.size(path) > 2_000_000
                || !ExecutionArchive.hash(Files.readAllBytes(path)).equals(lock.path("files").path(relative).asText()))
            throw new SecurityException("HOST_ASSET_NOT_LOCKED");
    }
    static void publishPlan(Path output, Map<String, Object> plan) throws Exception {
        var mapper = HarnessJson.mapper();
        try {
            ExecutionArchive.writeImmutable(output, mapper.writeValueAsBytes(plan), 2_000_000);
        } catch (FileAlreadyExistsException existing) {
            // Preserve the original bytes and digest, including across independent JVMs.
            var previous = mapper.readTree(ExecutionArchive.readManifest(output, 2_000_000));
            if (!previous.equals(mapper.valueToTree(plan)))
                throw new SecurityException("INSTALLATION_PLAN_CHANGED");
        }
    }
    public static void main(String[] args) {
        try {
            if (args.length != 1) throw new IllegalArgumentException("ADMISSION_ROOT_REQUIRED");
            Path root = Path.of(args[0]).toAbsolutePath().normalize();
            var plan = plan(root);
            Path output = root.resolve("component-installation-plan.json");
            publishPlan(output, plan);
            System.out.println(HarnessJson.mapper().writeValueAsString(Map.of("schemaVersion", "finance-agentscope-host-admission.v0.1",
                    "status", "READY", "runtime", NativeRuntime.ID, "installation", "NOT_OBSERVED", "modelInvocation", "NOT_OBSERVED",
                    "installationPlan", "component-installation-plan.json", "installationPlanSha256", ExecutionArchive.hash(Files.readAllBytes(output)),
                    "observationAssessment", plan.get("observationAssessment"), "hostObservationAssessment", plan.get("hostObservationAssessment"))));
        } catch (Exception failure) {
            String code = String.valueOf(failure.getMessage());
            System.err.println("HOST_ADMISSION_REJECTED " + failure.getClass().getSimpleName()
                    + (code.matches("[A-Z][A-Z0-9_]{1,100}") ? " " + code : ""));
            System.exit(2);
        }
    }
}
