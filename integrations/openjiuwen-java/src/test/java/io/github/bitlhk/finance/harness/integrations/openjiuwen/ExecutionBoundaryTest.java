package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.*;
import io.github.bitlhk.finance.harness.core.spi.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionBoundaryTest {
    static final String CAP = "test.transfer";
    static final Set<String> SCOPES = Set.of("test:write");
    final AtomicReference<Set<String>> grants = new AtomicReference<>(SCOPES);
    final AtomicBoolean authorized = new AtomicBoolean(true);
    final Bank bank = new Bank();
    final BoundFinanceProvider bound = new BoundFinanceProvider(bank, () -> authorized.get()
            ? new BoundFinanceProvider.Grant("host-user", FinanceContextV1.PrincipalType.CUSTOMER,
                Set.of("bank-customer"), Set.of("TEST"), grants.get(), "session", "fixture:host-binding",
                Instant.now().plusSeconds(60)) : null, Map.of(CAP, SCOPES));

    OpenJiuwenFinanceBridge bridge() {
        return new OpenJiuwenFinanceBridge(bound, "fixture-bank", new OpenJiuwenFinanceBridge.InvocationDefaults(
                "host-user", "bank-customer", FinanceContextV1.PrincipalType.CUSTOMER, "test", "TEST",
                FinanceContextV1.AuthenticationLevel.STRONG, SCOPES, grants::get), Set.of(CAP), true).hostSession("session");
    }
    InstalledTaskController controller() {
        var tasks = new InstalledTaskController("agent", "scope", new TaskCheckpointStore.Memory());
        tasks.register(new FinanceCapabilityPackV1.Capability(CAP, "test_transfer", "Test", "Test",
                FinanceCapabilityPackV1.Operation.TASK, Map.of("type", "object"), SCOPES), bridge());
        return tasks;
    }
    @Test void requiredScopesCannotGrantPermissionOrExposeCachedResultAfterRevocation() {
        var tasks = controller();
        grants.set(Set.of());
        assertEquals("AUTHORIZATION_DENIED", tasks.invoke(CAP, Map.of("amount", 10)).get("errorCode"));
        assertEquals(0, bank.calls);
        grants.set(SCOPES);
        String handle = (String) tasks.invoke(CAP, Map.of("amount", 10)).get("financeTaskHandle");
        Instant now = Instant.now();
        tasks.confirm(handle, new HostTaskConfirmation("host-yes", tasks.confirmationBinding(handle), now, now.plusSeconds(30)));
        assertEquals(1, bank.writes);
        authorized.set(false);
        String denied = tasks.invoke(CAP, Map.of("financeTaskHandle", handle)).toString();
        assertTrue(denied.contains("AUTHORIZATION_DENIED"));
        assertFalse(denied.contains("private-result"));
        assertEquals(2, bank.calls);
    }
    @Test void missingInputCanBeSupplementedButFrozenAmountCannotChange() {
        var tasks = controller();
        String handle = (String) tasks.invoke(CAP, Map.of()).get("financeTaskHandle");
        assertEquals(0, bank.creates);
        assertEquals(handle, tasks.invoke(CAP, Map.of("financeTaskHandle", handle, "amount", 10)).get("financeTaskHandle"));
        assertEquals(1, bank.creates);
        assertEquals(10, bank.last.parameters().get("amount"));
        assertEquals("TASK_PARAMETERS_CHANGED", tasks.invoke(CAP, Map.of("financeTaskHandle", handle, "amount", 20)).get("errorCode"));
        assertEquals(1, bank.creates);
    }
    @Test void lostCreationResponseNeverAllowsChangedParametersOrBlindRetry() {
        bank.loseCreation = true;
        var tasks = controller();
        String handle = (String) tasks.invoke(CAP, Map.of("amount", 10)).get("financeTaskHandle");
        assertNotNull(handle);
        assertEquals("TASK_PARAMETERS_CHANGED", tasks.invoke(CAP, Map.of("financeTaskHandle", handle, "amount", 20)).get("errorCode"));
        assertEquals("ORIGINAL_TASK_REQUIRES_VERIFICATION", tasks.invoke(CAP, Map.of("financeTaskHandle", handle)).get("errorCode"));
        assertEquals(1, bank.creates);
    }
    @Test void explicitLocalCollectionAllowsBlankButNotNonblankReplacement() {
        var tasks = controller();
        String handle = (String) tasks.invoke(CAP, Map.of("amount", " ")).get("financeTaskHandle");
        assertEquals(0, bank.creates);
        assertEquals(handle, tasks.invoke(CAP, Map.of("financeTaskHandle", handle, "amount", 10)).get("financeTaskHandle"));
        assertEquals(1, bank.creates);
        assertEquals(10, bank.last.parameters().get("amount"));
        assertEquals("TASK_PARAMETERS_CHANGED", tasks.invoke(CAP, Map.of("financeTaskHandle", handle, "amount", 20)).get("errorCode"));
        assertEquals(1, bank.creates);
    }
    @Test void collectionProofCannotReplaceExistingValueOrOverrideUncertainTask() {
        var checkpoint = Map.<String, Object>of(OpenJiuwenFinanceBridge.CK_INPUTS, List.of("amount", "beneficiaryId"),
                OpenJiuwenFinanceBridge.CK_PARAMETERS, Map.of("amount", 10, "beneficiaryId", ""));
        assertTrue(OpenJiuwenFinanceBridge.canSupplement(checkpoint, Map.of("amount", 10, "beneficiaryId", "recipient")));
        assertFalse(OpenJiuwenFinanceBridge.canSupplement(checkpoint, Map.of("amount", 20, "beneficiaryId", "recipient")));
        assertFalse(OpenJiuwenFinanceBridge.canSupplement(checkpoint, Map.of("amount", 10, "beneficiaryId", "recipient", "extra", 1)));
        var uncertain = new LinkedHashMap<>(checkpoint);
        uncertain.put(OpenJiuwenFinanceBridge.CK_CONTINUATION, Map.of("submitOutcomeUnknown", true));
        assertFalse(OpenJiuwenFinanceBridge.canSupplement(uncertain, Map.of("amount", 10, "beneficiaryId", "recipient")));
        var frozen = new LinkedHashMap<>(checkpoint);
        frozen.put(OpenJiuwenFinanceBridge.CK_TASK_REF, "bank-task");
        assertFalse(OpenJiuwenFinanceBridge.canSupplement(frozen, Map.of("amount", 10, "beneficiaryId", "recipient")));
    }
    @Test void wrongSubjectAndUninstalledCapabilityNeverReachProvider() {
        var bridge = bridge();
        var wrong = new OpenJiuwenInvocationV1("task", CAP, "agent", SCOPES, Map.of("customerRef", "other"), Map.of());
        assertEquals("AUTHORIZATION_DENIED", bridge.query(wrong).errorCode());
        assertEquals("AUTHORIZATION_DENIED", bridge.query(new OpenJiuwenInvocationV1("task", "other", "agent",
                Set.of(), Map.of(), Map.of())).errorCode());
        assertEquals(0, bank.calls);
    }
    @Test void directBoundCallsCannotUnderDeclareScopesOrChangeIdentityPurposeOrSession() {
        for (var c : List.of(
                context("host-user", "bank-customer", "TEST", "session", Set.of()),
                context("other-user", "bank-customer", "TEST", "session", SCOPES),
                context("host-user", "other-customer", "TEST", "session", SCOPES),
                context("host-user", "bank-customer", "OTHER_PURPOSE", "session", SCOPES),
                context("host-user", "bank-customer", "TEST", "other-session", SCOPES))) {
            assertThrows(SecurityException.class, () -> bound.executeTask(new CapabilityRequestV1(
                    CapabilityRequestV1.SCHEMA_VERSION, CAP, CapabilityRequestV1.Operation.TASK, c,
                    Map.of("amount", 10), "original-key", null, Map.of(), null)));
        }
        assertEquals(0, bank.calls); assertEquals(0, bank.writes);
    }
    static FinanceContextV1 context(String principal, String subject, String purpose, String session, Set<String> scopes) {
        return new FinanceContextV1(FinanceContextV1.SCHEMA_VERSION, "req", "trace", principal,
                FinanceContextV1.PrincipalType.CUSTOMER, "test", purpose, subject, session,
                FinanceContextV1.AuthenticationLevel.STRONG, scopes);
    }
    static class Bank implements DescribedFinanceProvider, InputCollectionProvider {
        int calls, creates, writes;
        boolean loseCreation;
        CapabilityRequestV1 last;
        public ProviderDescriptorV1 descriptor() { return new ProviderDescriptorV1(ProviderDescriptorV1.SCHEMA_VERSION,
                "fixture-bank", "0.1", List.of(new ProviderDescriptorV1.CapabilitySupport(CAP, CapabilityRequestV1.Operation.TASK))); }
        public CapabilityResultV1 query(CapabilityRequestV1 request) { calls++; throw new UnsupportedOperationException(); }
        public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
            calls++; last = request;
            if (!request.parameters().containsKey("amount") || request.parameters().get("amount") instanceof String s && s.isBlank())
                return result(request, CapabilityResultV1.Status.NEED_USER, null, List.of("amount"));
            if (request.taskRef() == null) { creates++; if (loseCreation) throw new IllegalStateException("create ack lost"); }
            if (request.confirmation() != null) writes++;
            return result(request, writes > 0 ? CapabilityResultV1.Status.COMPLETED : CapabilityResultV1.Status.NEED_CONFIRMATION,
                    new CapabilityResultV1.TaskState("bank-task", writes > 0 ? "COMPLETED" : "CREATED", writes == 0, Map.of()), List.of());
        }
        public Set<String> collectableInputs(CapabilityRequestV1 req, CapabilityResultV1 res) {
            return res.task() == null && res.missingInputs().contains("amount") ? Set.of("amount") : Set.of();
        }
        CapabilityResultV1 result(CapabilityRequestV1 req, CapabilityResultV1.Status status, CapabilityResultV1.TaskState task, List<String> missing) {
            return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION, req.context().requestId(), req.context().traceId(), status,
                    "private-result", Map.of(), missing, List.of(), "fixture-bank", Instant.now(), task, null);
        }
    }
}
