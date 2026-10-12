package io.github.bitlhk.finance.builder.local;

import com.openjiuwen.core.runner.Runner;
import java.nio.file.Path;
import java.util.Map;

/** Runtime-specific bootstrap; the managed Host has no SDK imports. */
final class NativeRuntime {
    static final String ID = "openjiuwen-java-0.1.15";
    static final String DISPATCH_COVERAGE = "SDK_CALLBACK_ONLY_HTTP_NOT_OBSERVED";
    static boolean start() { return Runner.start(); }
    static void stop() { Runner.stop(); }
    static void validateCollectorEvidence(Path candidate, Path evidence, String collector, String identity) { }
    static Map<String, Object> plan(Path root) throws Exception {
        return ObservationAdmission.prepare(root, root.resolve("solution.json"), ReadOnlyProviders.forPlan(root)).snapshot();
    }
}
