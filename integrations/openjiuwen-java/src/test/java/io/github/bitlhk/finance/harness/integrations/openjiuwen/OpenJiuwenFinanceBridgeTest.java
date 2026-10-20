package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenJiuwenFinanceBridgeTest {
    @Test
    void strictQuerySeparatesExecutionIdentityAndRetainsTrustedSession() {
        AtomicReference<CapabilityRequestV1> captured = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        FinanceProvider provider = new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                calls.incrementAndGet();
                captured.set(request);
                return result(request, CapabilityResultV1.Status.COMPLETED, "read", Map.of(), null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("TASK must not dispatch");
            }
        };
        var input = invocation(QUERY, Set.of("wealth:read"), Map.of("productRef", "demo"), Map.of());
        var legacy = new OpenJiuwenFinanceBridge(provider, "test-provider", DEFAULTS,
                Set.of(QUERY), true, "trusted-session");
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, legacy.query(input).status());
        assertEquals(input.taskId(), captured.get().taskRef());
        var strict = new OpenJiuwenFinanceBridge(provider, "test-provider",
                DEFAULTS.withStrictReadOnlyQueries().withPurpose("STRICT_READ"),
                Set.of(QUERY, TASK), true, "trusted-session");
        var output = strict.query(input);
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, output.status());
        assertEquals(null, captured.get().taskRef());
        assertEquals(input.taskId(), output.taskId());
        assertEquals("trusted-session", captured.get().context().sessionRef());
        assertEquals("STRICT_READ", captured.get().context().purpose());
        assertEquals(input.arguments(), captured.get().parameters());
        assertEquals(2, calls.get(), "one dispatch per query");
        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, strict.executeTask(input).status());
        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, strict.executeTask(input, null).status());
        assertEquals(2, calls.get());
        assertFalse(DEFAULTS.strictReadOnlyQueries(), "legacy defaults are not mutated");
    }

    @Test
    void strictQueryStillChecksCurrentScopesAndDoesNotStripBusinessArguments() {
        AtomicReference<Set<String>> scopes = new AtomicReference<>(Set.of("wealth:read"));
        AtomicInteger calls = new AtomicInteger();
        FinanceProvider provider = new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                calls.incrementAndGet();
                assertEquals("injected-task", request.parameters().get("taskRef"),
                        "strict mapping is not a Provider parameter sanitizer");
                return result(request, CapabilityResultV1.Status.COMPLETED, "read", Map.of(), null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("TASK must not dispatch");
            }
        };
        var defaults = new OpenJiuwenFinanceBridge.InvocationDefaults("customer-001", "customer-001",
                FinanceContextV1.PrincipalType.CUSTOMER, "test", "STRICT_READ",
                FinanceContextV1.AuthenticationLevel.BASIC, Set.of("wealth:read"), scopes::get);
        assertFalse(defaults.strictReadOnlyQueries(), "legacy eight-argument constructor");
        var strict = new OpenJiuwenFinanceBridge(provider, "test-provider",
                defaults.withStrictReadOnlyQueries(), Set.of(QUERY), true, "trusted-session");
        var input = invocation(QUERY, Set.of("wealth:read"), Map.of("taskRef", "injected-task"), Map.of());
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, strict.query(input).status());
        scopes.set(Set.of());
        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, strict.query(input).status());
        assertEquals(1, calls.get());
    }

    private static final String QUERY = "wealth.customer-context.query";
    private static final String TASK = "channel.preferences.update";
    private static final OpenJiuwenFinanceBridge.InvocationDefaults DEFAULTS =
            new OpenJiuwenFinanceBridge.InvocationDefaults(
                    "customer-001",
                    "customer-001",
                    FinanceContextV1.PrincipalType.CUSTOMER,
                    "mobile-banking",
                    "WEALTH_DIAGNOSIS",
                    FinanceContextV1.AuthenticationLevel.BASIC,
                    Set.of("fallback:read", "wealth:read", "channel:write"));

    @Test
    void queryPreservesExplicitNullsAndListPositionsInCanonicalFacts() throws Exception {
        var valuation = new java.util.LinkedHashMap<String, Object>();
        valuation.put("consensus", null);
        valuation.put("zero", 0);
        var periods = new java.util.ArrayList<>(java.util.Arrays.asList(10, null, 30));
        var source = new java.util.LinkedHashMap<String, Object>();
        source.put("valuation", valuation);
        source.put("periods", periods);
        source.put("nextPageToken", null);
        var bridge = bridge(new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return result(request, CapabilityResultV1.Status.COMPLETED, "read", source, null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("Read-only test");
            }
        });
        var output = bridge.query(invocation(QUERY, Set.of("wealth:read"), Map.of(), Map.of()));
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, output.status());
        var mapper = io.github.bitlhk.finance.harness.core.HarnessJson.mapper();
        var facts = mapper.valueToTree(output).path("data").path("result");
        assertTrue(facts.has("nextPageToken"));
        assertTrue(facts.path("nextPageToken").isNull());
        assertTrue(output.data().containsKey("nextPageToken"), "legacy flattened view must also preserve null");
        assertEquals(null, output.data().get("nextPageToken"));
        assertTrue(facts.path("valuation").has("consensus"), "explicit missing value must not disappear");
        assertTrue(facts.path("valuation").path("consensus").isNull());
        assertEquals(0, facts.path("valuation").path("zero").asInt());
        assertFalse(facts.path("valuation").has("absent"));
        assertEquals(3, facts.path("periods").size());
        assertTrue(facts.path("periods").get(1).isNull());
        assertEquals(30, facts.path("periods").get(2).asInt());
        valuation.put("consensus", "later mutation");
        periods.set(1, 999);
        assertEquals(facts, mapper.valueToTree(output).path("data").path("result"));
        assertEquals(facts, mapper.readTree(mapper.writeValueAsBytes(output)).path("data").path("result"));
    }

    @Test
    void externalHostSessionIsNotTakenFromModelArgumentsOrTaskId() {
        AtomicReference<CapabilityRequestV1> captured = new AtomicReference<>();
        FinanceProvider provider = new FinanceProvider() {
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                captured.set(request);
                return result(request, CapabilityResultV1.Status.COMPLETED, "read", Map.of(), null, null);
            }
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new AssertionError("Read-only test");
            }
        };
        var bridge = new OpenJiuwenFinanceBridge(provider, "test-provider", DEFAULTS,
                Set.of(QUERY), true, "authenticated-session");
        var output = bridge.query(invocation(QUERY, Set.of("wealth:read"),
                Map.of("sessionRef", "model-session"), Map.of()));
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, output.status());
        assertEquals("authenticated-session", captured.get().context().sessionRef());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new OpenJiuwenFinanceBridge(provider, "test-provider", DEFAULTS,
                        Set.of(QUERY), true, " "));
    }

    @Test
    void queryMapsContextAndReturnsMinimalEvidence() {
        AtomicReference<CapabilityRequestV1> captured = new AtomicReference<>();
        OpenJiuwenFinanceBridge bridge = bridge(new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                captured.set(request);
                return result(request, CapabilityResultV1.Status.COMPLETED,
                        "财富客户上下文查询已完成。",
                        Map.of("wealthAggregate", Map.of("totalAssets", 1_000_000)),
                        null,
                        null);
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new UnsupportedOperationException();
            }
        });

        OpenJiuwenExecutionV1 output = bridge.query(invocation(
                QUERY, Set.of("wealth:read"), Map.of(), Map.of()));

        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, output.status());
        assertEquals(CapabilityRequestV1.Operation.QUERY, captured.get().operation());
        assertEquals("WEALTH_DIAGNOSIS", captured.get().context().purpose());
        assertEquals(Set.of("wealth:read"), captured.get().context().scopes());
        assertTrue(output.data().get("wealthAggregate") instanceof Map<?, ?>);
        assertEquals(OpenJiuwenFinanceBridge.ADAPTER_ID,
                ((Map<?, ?>) output.data().get("harness")).get("adapter"));
        assertNotNull(output.evidence().getFirst().get("receiptId"));
        assertFalse(output.evidence().getFirst().containsKey("wealthAggregate"));
    }

    @Test
    void taskCarriesCheckpointConfirmationAndBoundedIdempotencyKey() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> firstKey = new AtomicReference<>();
        OpenJiuwenFinanceBridge bridge = bridge(new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                if (calls.getAndIncrement() == 0) {
                    assertEquals(Map.of("largeTextEnabled", true), request.parameters());
                    assertEquals("CHANNEL_SETTINGS", request.context().purpose());
                    assertNotEquals("x".repeat(180), request.idempotencyKey());
                    assertTrue(request.idempotencyKey().length() <= 128);
                    firstKey.set(request.idempotencyKey());
                    return result(request, CapabilityResultV1.Status.NEED_CONFIRMATION,
                            "请确认后更新渠道展示偏好。",
                            Map.of("taskStatus", "CREATED"),
                            new CapabilityResultV1.TaskState(
                                    "task-pref-001", "CREATED", true,
                                    Map.of("preflightId", "pf-pref-001")),
                            null);
                }
                assertEquals("task-pref-001", request.taskRef());
                assertEquals(firstKey.get(), request.idempotencyKey());
                assertEquals(Map.of("largeTextEnabled", true), request.parameters());
                assertTrue(request.confirmation().confirmed());
                assertEquals("pf-pref-001", request.continuation().get("preflightId"));
                return result(request, CapabilityResultV1.Status.COMPLETED,
                        "渠道展示偏好已更新。",
                        Map.of("taskStatus", "COMPLETED"),
                        new CapabilityResultV1.TaskState(
                                "task-pref-001", "COMPLETED", false, request.continuation()),
                        null);
            }
        }, "CHANNEL_SETTINGS");

        OpenJiuwenExecutionV1 first = bridge.executeTask(invocation(
                TASK,
                Set.of("channel:write"),
                Map.of("largeTextEnabled", true, "idempotencyKey", "x".repeat(180)),
                Map.of()));
        OpenJiuwenExecutionV1 second = bridge.executeTask(invocation(
                TASK,
                Set.of("channel:write"),
                Map.of(
                        "confirmedCapabilityId", TASK,
                        "confirmationText", "确认保存渠道偏好"),
                first.checkpoint()), new HostTaskConfirmation("host-event-001",
                String.valueOf(first.checkpoint().get(OpenJiuwenFinanceBridge.CK_BINDING)),
                Instant.now(), Instant.now().plusSeconds(60)));

        assertEquals(OpenJiuwenExecutionV1.Status.NEED_USER, first.status());
        assertEquals("task-pref-001", first.checkpoint().get(OpenJiuwenFinanceBridge.CK_TASK_REF));
        assertEquals(OpenJiuwenExecutionV1.Status.COMPLETED, second.status());
        assertEquals(2, calls.get());
        assertEquals("TASK", first.evidence().getFirst().get("adapterMode"));
    }

    @Test
    void providerFailureAndExceptionDoNotLeakImplementationDetails() {
        OpenJiuwenFinanceBridge failed = bridge(new FinanceProvider() {
            @Override
            public CapabilityResultV1 query(CapabilityRequestV1 request) {
                return result(request, CapabilityResultV1.Status.FAILED,
                        "当前无法查询。", Map.of(), null,
                        new CapabilityResultV1.Failure(
                                "TEMPORARY_FAILURE", "UPSTREAM_503", true,
                                "service unavailable", List.of()));
            }

            @Override
            public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
                throw new IllegalStateException("secret endpoint");
            }
        });

        OpenJiuwenExecutionV1 query = failed.query(invocation(QUERY, Set.of(), Map.of(), Map.of()));
        OpenJiuwenExecutionV1 task = failed.executeTask(invocation(TASK, Set.of(), Map.of(), Map.of()));

        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, query.status());
        assertEquals("UPSTREAM_503", query.errorCode());
        assertEquals("FAILED", query.data().get("resultStatus"));
        assertEquals("UPSTREAM_503", query.data().get("sourceCode"));
        assertEquals(true, query.data().get("retryable"));
        assertEquals(OpenJiuwenExecutionV1.Status.REJECTED, task.status());
        assertEquals("FINANCE_HARNESS_ERROR", task.errorCode());
        assertFalse(task.content().contains("secret"));
    }

    private static OpenJiuwenFinanceBridge bridge(FinanceProvider provider) {
        return bridge(provider, DEFAULTS.purpose());
    }

    private static OpenJiuwenFinanceBridge bridge(FinanceProvider provider, String purpose) {
        return new OpenJiuwenFinanceBridge(
                provider,
                "test-provider",
                DEFAULTS.withPurpose(purpose),
                Set.of(QUERY, TASK),
                true);
    }

    private static OpenJiuwenInvocationV1 invocation(
            String capabilityId,
            Set<String> scopes,
            Map<String, Object> arguments,
            Map<String, Object> checkpoint) {
        return new OpenJiuwenInvocationV1(
                "task-001", capabilityId, "agent.test", scopes, arguments, checkpoint);
    }

    private static CapabilityResultV1 result(
            CapabilityRequestV1 request,
            CapabilityResultV1.Status status,
            String content,
            Map<String, Object> data,
            CapabilityResultV1.TaskState task,
            CapabilityResultV1.Failure failure) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                status,
                content,
                data,
                List.of(),
                status == CapabilityResultV1.Status.NEED_CONFIRMATION
                        ? List.of("CONFIRM_TASK")
                        : List.of(),
                "non-ai-banking",
                Instant.parse("2026-09-02T01:00:00Z"),
                task,
                failure);
    }
}
