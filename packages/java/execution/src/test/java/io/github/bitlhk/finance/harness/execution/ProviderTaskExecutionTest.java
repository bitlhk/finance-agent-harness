package io.github.bitlhk.finance.harness.execution;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class ProviderTaskExecutionTest {
    private static final String CAP = "example.task";
    private CapabilityRequestV1 base() {
        var context = new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "request", "trace", "principal",
                FinanceContextV1.PrincipalType.CUSTOMER, "mobile", "TRANSFER", "subject", "session",
                FinanceContextV1.AuthenticationLevel.STRONG, Set.of());
        return new CapabilityRequestV1(CapabilityRequestV1.SCHEMA_VERSION, CAP, CapabilityRequestV1.Operation.TASK,
                context, Map.of("amount", 10), null, null, Map.of(), null);
    }
    private ProviderTaskExecution.Invocation invocation() {
        return new ProviderTaskExecution.Invocation("task", CAP, "owner", Map.of("amount", 10),
                TaskProtocol.initial(CAP, "task", "owner", Map.of("amount", 10)));
    }
    @Test void mismatchedResultsNeverReachCollectorOrBusinessOutcome() {
        for (boolean wrongTrace : new boolean[]{false, true}) {
            var collected = new AtomicInteger();
            InputCollectionProvider provider = new InputCollectionProvider() {
                public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError(); }
                public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                    return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                            wrongTrace ? request.context().requestId() : "other-request",
                            wrongTrace ? "other-trace" : request.context().traceId(), CapabilityResultV1.Status.NEED_USER,
                            "missing input", Map.of(), List.of(), List.of(), "synthetic", Instant.now(), null, null);
                }
                public Set<String> collectableInputs(CapabilityRequestV1 request, CapabilityResultV1 result) {
                    collected.incrementAndGet(); throw new SecurityException("MUST_NOT_RUN");
                }
            };
            var outcome = ProviderTaskExecution.execute(provider, this::base, invocation(), null);
            assertEquals(ProviderTaskExecution.Decision.UNOBSERVED, outcome.decision());
            assertTrue(outcome.observationRequired());
            assertEquals(invocation().checkpoint(), outcome.checkpoint());
            assertEquals(0, collected.get()); assertNull(outcome.result());
        }
    }
    @Test void authorizationAndContextAreRecheckedBeforeProvider() {
        var order = new java.util.ArrayList<String>();
        class Bank implements FinanceProvider, InvocationAccess {
            public void checkAccess(CapabilityRequestV1 request) { order.add("access"); }
            public CapabilityResultV1 query(CapabilityRequestV1 request) { throw new AssertionError(); }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) { order.add("provider"); throw new AssertionError("must not run"); }
        }
        var requests = new AtomicInteger();
        var outcome = ProviderTaskExecution.execute(new Bank(), () -> {
            order.add("context");
            if (requests.incrementAndGet() == 2) throw new SecurityException("REVOKED");
            return base();
        }, invocation(), null);
        assertEquals(ProviderTaskExecution.Decision.ACCESS_DENIED, outcome.decision());
        assertEquals(List.of("context", "access", "context"), order);
    }
}
