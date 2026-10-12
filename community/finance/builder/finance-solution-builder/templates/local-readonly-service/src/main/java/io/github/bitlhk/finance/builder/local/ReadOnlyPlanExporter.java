package io.github.bitlhk.finance.builder.local;

import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.integrations.openjiuwen.*;
import java.nio.file.*;

/** Resolves actual packaged Provider implementations without a bank identity or Runtime Agent. */
public final class ReadOnlyPlanExporter {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        var prepared = ObservationAdmission.prepare(root,
                root.resolve("bridge-solution.json"), ReadOnlyProviders.forPlan(root));
        if (prepared.capabilityPacks().stream().flatMap(pack -> pack.capabilities().stream())
                .anyMatch(cap -> cap.operation() != FinanceCapabilityPackV1.Operation.QUERY))
            throw new IllegalArgumentException("READONLY_INSTALLATION_REJECTS_TASK");
        for (var pack : prepared.capabilityPacks()) for (var cap : pack.capabilities())
            new QueryInputValidator(cap.inputSchema());
        HarnessJson.mapper().writeValue(root.resolve("installation-plan.json").toFile(), ReadOnlyProviders.plan(prepared.snapshot()));
    }
}
