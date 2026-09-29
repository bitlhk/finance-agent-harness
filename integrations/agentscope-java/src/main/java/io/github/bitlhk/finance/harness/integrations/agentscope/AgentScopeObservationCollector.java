package io.github.bitlhk.finance.harness.integrations.agentscope;

import io.github.bitlhk.finance.harness.core.observation.*;
import java.util.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationCapabilitiesV01.*;
import static io.github.bitlhk.finance.harness.core.observation.ObservationRequirementsV01.*;

/** Declarations match emitted Core ObservationEvents; VERIFIED is admitted only with pinned qualification. */
public final class AgentScopeObservationCollector {
    public static final String ID = "agentscope-query-collector";
    public static final String VERSION = "0.1.0";
    private AgentScopeObservationCollector() {}
    public static ObservationCapabilitiesV01 declaration(boolean verified) {
        return new ObservationCapabilitiesV01(ObservationCapabilitiesV01.VERSION, ID, VERSION, List.of(
                capability("host.installation", Boundary.HOST, Set.of("component", "operation", "at"), verified),
                capability("host.authorization", Boundary.HOST, Set.of("harnessRequestId", "outcome", "at"), verified),
                capability("runtime.tool", Boundary.INTEGRATOR, Set.of("toolCallId", "capabilityId", "at"), verified),
                capability("capability.result", Boundary.PROVIDER, Set.of("capabilityId", "providerId", "harnessRequestId", "businessStatus", "at"), verified),
                capability("model.call", Boundary.MODEL_CLIENT, Set.of("harnessRequestId", "component", "operation", "at"), verified)));
    }
    private static Capability capability(String id, Boundary boundary, Set<String> fields, boolean verified) {
        return new Capability(id, "0.1", boundary, fields, Support.SUPPORTED, Support.SUPPORTED,
                verified ? Verification.VERIFIED : Verification.NOT_CHECKED,
                verified ? List.of("agentscope-query-qualification-v01") : List.of(), Availability.ENABLED, Authorization.NOT_REQUIRED);
    }
    public static List<Requirement> baseline() {
        return declaration(false).capabilities().stream().map(c -> new Requirement(c.semanticId(), c.semanticVersion(),
                Obligation.REQUIRED, c.boundary(), Content.METADATA, c.fields(), Set.of())).toList();
    }

    /** Bridge for the existing NonAiBankingProvider supplier; bound and closed on its synchronous worker. */
    private static final ThreadLocal<ObservationRun> CURRENT = new ThreadLocal<>();
    public static ObservationRun currentRun() { return CURRENT.get(); }
    public static Scope bind(ObservationRun run, String parent) { return new Scope(run, parent); }
    public static final class Scope implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ObservationRun previous = CURRENT.get();
        private final ObservationRun.ParentScope parent;
        private Scope(ObservationRun run, String explicitParent) { CURRENT.set(run); parent = run.bindParent(explicitParent); }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("OBSERVATION_SCOPE_THREAD_MISMATCH");
            parent.close(); if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
