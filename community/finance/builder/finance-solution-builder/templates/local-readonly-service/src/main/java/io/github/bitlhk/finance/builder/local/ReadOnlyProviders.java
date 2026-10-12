package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.spi.*;
import io.github.bitlhk.finance.harness.core.observation.*;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Managed classpath factories; no capability-specific construction or source-scope routing. */
final class ReadOnlyProviders {
    private static String connectionKind(Path root) {
        try {
            String value = HarnessJson.mapper().readTree(root.resolve("component-plan.json").toFile())
                    .path("connectionKind").asText();
            if (!Set.of("synthetic-bank-http.v1", "in-process-smart-audit.v1").contains(value))
                throw new SecurityException("COMPONENT_CONNECTION_KIND_UNSUPPORTED");
            return value;
        } catch (java.io.IOException invalid) {
            throw new SecurityException("COMPONENT_PLAN_INVALID", invalid);
        }
    }
    static Set<String> sourceScopes(Path root) {
        return ManagedProviderFactoriesV01.installed().sourceScopes(ComponentAdmission.selectedProviders(root));
    }
    static Map<String, Object> plan(Map<String, Object> snapshot) {
        var result = new LinkedHashMap<String, Object>(snapshot);
        var selected = new HashMap<String, Set<String>>();
        for (var cap : HarnessJson.mapper().valueToTree(snapshot).path("capabilities"))
            selected.computeIfAbsent(cap.path("providerId").asText(), ignored -> new HashSet<>())
                    .add(cap.path("definition").path("capabilityId").asText());
        result.put("hostRequiredSourceScopes", ManagedProviderFactoriesV01.installed().sourceScopes(selected));
        result.put("hostAuthorizationPolicy", "ALL_SELECTED_AND_SOURCE_SCOPES_EACH_MODEL_AND_TOOL_BOUNDARY");
        return Collections.unmodifiableMap(result);
    }
    static Map<String, FinanceProvider> create(Path root, BankAccess bank, BankAccess.Login login,
            Supplier<ObservationRun> observation, Runnable guard,
            Supplier<BoundFinanceProvider.Grant> invocationGrant, Consumer<Object> calls) {
        var connection = new ManagedProviderFactoryV01.Connection(connectionKind(root), bank.base,
                Duration.ofSeconds(10), () -> {
                    var session = bank.bankSession(login);
                    return new ManagedProviderFactoryV01.SourceSession(session.bankSessionId(), session.customerRef(),
                            session.principalId(), session.hostSessionRef(), session.expiresAt());
                }, observation, calls::accept);
        return ManagedProviderFactoriesV01.installed().create(connection,
                () -> { guard.run(); return invocationGrant.get(); }, ComponentAdmission.selectedProviders(root).keySet());
    }
    static Map<String, FinanceProvider> forPlan(Path root) {
        var connection = new ManagedProviderFactoryV01.Connection(connectionKind(root),
                java.net.URI.create("http://127.0.0.1:1"), Duration.ofSeconds(10),
                () -> { throw new SecurityException("PLAN_ONLY_NO_SESSION"); }, () -> null, ignored -> {});
        return ManagedProviderFactoriesV01.installed().create(connection,
                () -> { throw new SecurityException("PLAN_ONLY_NO_GRANT"); }, ComponentAdmission.selectedProviders(root).keySet());
    }
}
