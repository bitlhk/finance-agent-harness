package io.github.bitlhk.finance.harness.integrations.agentscope.isolatedhost;

import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Test-only launcher kept separate from dependency-free operator Host workers. */
public final class MethodOnlyTestWorker {
    public static void main(String[] args) {
        String test = "io.github.bitlhk.finance.harness.integrations.agentscope.MethodOnlyNativeTest";
        if (args.length > 1 || args.length == 1 && !"fresh-admission".equals(args[0]))
            throw new IllegalArgumentException("TEST_MODE_INVALID");
        var selector = args.length == 0 ? DiscoverySelectors.selectClass(test)
                : DiscoverySelectors.selectMethod(test, "operatorPinnedEvidenceAdmitsFreshNativeSession");
        var request = LauncherDiscoveryRequestBuilder.request().selectors(selector).build();
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request, listener);
        var summary = listener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.err, true));
        long expected = args.length == 0 ? 11 : 1;
        if (summary.getTestsSucceededCount() != expected || summary.getTotalFailureCount() != 0
                || summary.getTestsAbortedCount() != 0)
            throw new IllegalStateException("PACKAGED_METHOD_TESTS_NOT_PASSED");
    }
}
