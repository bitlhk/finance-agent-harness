package io.github.bitlhk.finance.builder.local;

import java.nio.file.Path;
import java.util.Map;

/** No process-global AgentScope engine or tool registry is started. */
final class NativeRuntime {
    static final String ID = "agentscope-java-2.0.3";
    static final String DISPATCH_COVERAGE = "CHECKED_CLIENT_TRANSPORT_INVOCATION_NOT_REMOTE_ACCEPTANCE";
    static boolean start() { return true; }
    static void stop() { }
    static Map<String, Object> plan(Path root) throws Exception { return AgentScopeAdmission.plan(root); }
    static void validateCollectorEvidence(Path candidate, Path evidence, String collector, String identity) throws Exception {
        HostedQualification.validate(candidate, evidence, collector, identity);
    }
}
