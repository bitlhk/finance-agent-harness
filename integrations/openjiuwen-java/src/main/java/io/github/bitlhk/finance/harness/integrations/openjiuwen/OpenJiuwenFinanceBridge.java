package io.github.bitlhk.finance.harness.integrations.openjiuwen;

import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceiptV1;
import io.github.bitlhk.finance.harness.core.EvidenceReceipts;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.execution.TaskCheckpointPolicy;
import io.github.bitlhk.finance.harness.execution.TaskProtocol;
import io.github.bitlhk.finance.harness.execution.TaskCoordination;
import io.github.bitlhk.finance.harness.execution.ProviderTaskExecution;
import io.github.bitlhk.finance.harness.core.spi.FinanceProvider;
import io.github.bitlhk.finance.harness.core.spi.InvocationAccess;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Converts OpenJiuwen host invocations to Finance Harness contracts.
 *
 * <p>The module deliberately does not import application-specific runtime classes. A host adapter maps
 * its native capability and execution types to the two small records exposed by this package.</p>
 */
public final class OpenJiuwenFinanceBridge {
    public static final String ADAPTER_ID = "finance-agent-harness";
    public static final String CK_TASK_REF = TaskCheckpointPolicy.CK_TASK_REF;
    public static final String CK_STATUS = TaskCheckpointPolicy.CK_STATUS;
    public static final String CK_CAPABILITY = TaskCheckpointPolicy.CK_CAPABILITY;
    public static final String CK_IDEMPOTENCY = TaskCheckpointPolicy.CK_IDEMPOTENCY;
    public static final String CK_CONTINUATION = TaskCheckpointPolicy.CK_CONTINUATION;
    public static final String CK_PARAMETERS = TaskCheckpointPolicy.CK_PARAMETERS;
    public static final String CK_BINDING = TaskCheckpointPolicy.CK_BINDING;
    public static final String CK_OWNER = TaskCheckpointPolicy.CK_OWNER;
    public static final String CK_INPUTS = TaskCheckpointPolicy.CK_INPUTS;

    private final FinanceProvider provider;
    private final String providerId;
    private final InvocationDefaults defaults;
    private final Set<String> supportedCapabilities;
    private final boolean simulated;
    private String hostSessionRef;

    public OpenJiuwenFinanceBridge(
            FinanceProvider provider,
            String providerId,
            InvocationDefaults defaults,
            Set<String> supportedCapabilities,
            boolean simulated) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.providerId = required(providerId, "providerId");
        this.defaults = Objects.requireNonNull(defaults, "defaults");
        this.supportedCapabilities = Set.copyOf(Objects.requireNonNull(
                supportedCapabilities, "supportedCapabilities"));
        this.simulated = simulated;
    }

    OpenJiuwenFinanceBridge hostSession(String sessionRef) { this.hostSessionRef = sessionRef; return this; }

    /** Session comes from the authenticated host, never invocation arguments. */
    public OpenJiuwenFinanceBridge(
            FinanceProvider provider, String providerId, InvocationDefaults defaults,
            Set<String> supportedCapabilities, boolean simulated, String hostSessionRef) {
        this(provider, providerId, defaults, supportedCapabilities, simulated);
        this.hostSessionRef = required(hostSessionRef, "hostSessionRef");
    }

    void checkAccess(OpenJiuwenInvocationV1 invocation, CapabilityRequestV1.Operation operation) {
        if (!supports(invocation.capabilityId())) throw new SecurityException("CAPABILITY_NOT_INSTALLED");
        CapabilityRequestV1 request = request(invocation, operation);
        if (provider instanceof InvocationAccess access) access.checkAccess(request);
    }

    public boolean supports(String capabilityId) {
        return capabilityId != null && supportedCapabilities.contains(capabilityId);
    }

    public OpenJiuwenExecutionV1 query(OpenJiuwenInvocationV1 invocation) {
        Instant startedAt = Instant.now();
        try {
            checkAccess(invocation, CapabilityRequestV1.Operation.QUERY);
            CapabilityRequestV1 request = request(invocation, CapabilityRequestV1.Operation.QUERY);
            CapabilityResultV1 result = provider.query(request);
            EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                    request, result, providerId, startedAt, Instant.now());
            Map<String, Object> data = resultData(invocation, result, receipt, true);
            List<Map<String, Object>> evidence = List.of(evidence(invocation, receipt, "QUERY"));
            if (result.status() == CapabilityResultV1.Status.COMPLETED) {
                return execution(OpenJiuwenExecutionV1.Status.COMPLETED, invocation, result,
                        data, evidence, "", "", Map.of());
            }
            String code = result.error() == null
                    ? "FINANCE_HARNESS_NOT_COMPLETED"
                    : result.error().sourceCode();
            boolean needsUser = result.status() == CapabilityResultV1.Status.NEED_USER;
            return execution(
                    needsUser ? OpenJiuwenExecutionV1.Status.NEED_USER : OpenJiuwenExecutionV1.Status.REJECTED,
                    invocation, result, data, evidence, code, needsUser ? result.content() : "", Map.of());
        } catch (SecurityException denied) {
            return accessDenied(invocation, startedAt);
        } catch (RuntimeException exception) {
            return new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.REJECTED, invocation.taskId(),
                    "暂时无法完成查询，请稍后重试。", Map.of(), List.of(localEvidence(invocation, startedAt, "UNOBSERVED")),
                    "FINANCE_HARNESS_ERROR", "", Map.of());
        }
    }

    public OpenJiuwenExecutionV1 executeTask(OpenJiuwenInvocationV1 invocation) {
        return executeTask(invocation, null);
    }

    /** The approval parameter belongs to the trusted host API, not tool arguments. */
    public OpenJiuwenExecutionV1 executeTask(OpenJiuwenInvocationV1 invocation, HostTaskConfirmation approval) {
        Instant startedAt = Instant.now();
        try {
            var outcome = ProviderTaskExecution.execute(provider, () -> {
                if (!supports(invocation.capabilityId())) throw new SecurityException("CAPABILITY_NOT_INSTALLED");
                return request(invocation, CapabilityRequestV1.Operation.TASK);
            }, new ProviderTaskExecution.Invocation(invocation.taskId(), invocation.capabilityId(), owner(invocation),
                    invocation.arguments(), invocation.checkpoint()), approval == null ? null : new TaskCoordination.Approval(
                    approval.eventId(), approval.bindingFingerprint(), approval.confirmedAt(), approval.expiresAt()));
            startedAt = outcome.startedAt();
            switch (outcome.decision()) {
                case ACCESS_DENIED: return accessDenied(invocation, startedAt);
                case OWNER_MISMATCH: return rejected(invocation, "续办身份与原任务不一致。");
                case PARAMETERS_CHANGED: return rejected(invocation, "原任务参数不可变更，请先处理原任务。");
                case CONFIRMATION_INVALID: return rejected(invocation, "确认事件已过期或与待确认任务不一致。");
                case UNOBSERVED: throw new IllegalStateException("TASK_OUTCOME_UNOBSERVED");
                case BUSINESS_RESULT: break;
            }
            CapabilityRequestV1 request = outcome.request();
            CapabilityResultV1 result = outcome.result();
            EvidenceReceiptV1 receipt = EvidenceReceipts.from(
                    request, result, providerId, startedAt, Instant.now());
            Map<String, Object> data = resultData(invocation, result, receipt, false);
            List<Map<String, Object>> evidence = List.of(evidence(invocation, receipt, "TASK"));
            if (result.status() == CapabilityResultV1.Status.COMPLETED) {
                return execution(OpenJiuwenExecutionV1.Status.COMPLETED, invocation, result,
                        data, evidence, "", "", Map.of());
            }
            if (TaskProtocol.pending(result)) {
                return execution(OpenJiuwenExecutionV1.Status.NEED_USER, invocation, result,
                        data, evidence, "", result.content(), outcome.checkpoint());
            }
            if (outcome.observationRequired()) {
                return new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.NEED_USER,
                        invocation.taskId(), "本次查询未能确认原任务结果，请沿原任务继续查证。",
                        data, evidence, result.error() == null ? "OBSERVATION_FAILED" : result.error().sourceCode(),
                        "请稍后查证原任务。", outcome.checkpoint());
            }
            String code = result.error() == null
                    ? "FINANCE_HARNESS_TASK_FAILED"
                    : result.error().sourceCode();
            return execution(OpenJiuwenExecutionV1.Status.REJECTED, invocation, result,
                    data, evidence, code, "", Map.of());
        } catch (SecurityException denied) {
            return accessDenied(invocation, startedAt);
        } catch (RuntimeException exception) {
            if (!invocation.checkpoint().isEmpty()) {
                return new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.NEED_USER,
                        invocation.taskId(), "当前无法确认原任务结果，请勿重新提交。",
                        Map.of("resultStatus", "UNKNOWN"), List.of(localEvidence(invocation, startedAt, "UNOBSERVED")), "OBSERVATION_FAILED",
                        "请稍后查证原任务。", recoveryCheckpoint(invocation));
            }
            return rejected(invocation, "暂时无法完成办理，请稍后重试。");
        }
    }

    private CapabilityRequestV1 request(
            OpenJiuwenInvocationV1 invocation,
            CapabilityRequestV1.Operation operation) {
        String invocationId = UUID.randomUUID().toString();
        Set<String> granted = Set.copyOf(defaults.currentScopes().get());
        if (!granted.containsAll(invocation.requiredScopes())) throw new SecurityException("INSUFFICIENT_SCOPES");
        Set<String> scopes = invocation.requiredScopes().isEmpty() ? Set.of() : invocation.requiredScopes();
        FinanceContextV1 context = new FinanceContextV1(
                FinanceContextV1.SCHEMA_VERSION,
                "fh-request-" + invocationId,
                "fh-trace-" + invocationId,
                defaults.principalId(),
                defaults.principalType(),
                defaults.channel(),
                defaults.purpose(),
                defaults.subjectRef(),
                hostSessionRef == null ? invocation.taskId() : hostSessionRef,
                defaults.authenticationLevel(),
                scopes);
        return new CapabilityRequestV1(
                CapabilityRequestV1.SCHEMA_VERSION,
                invocation.capabilityId(),
                operation,
                context,
                operation == CapabilityRequestV1.Operation.QUERY
                        ? sanitizeMap(invocation.arguments())
                        : businessParameters(invocation.arguments()),
                null,
                invocation.taskId(),
                Map.of(),
                null);
    }

    static Map<String, Object> businessParameters(Map<String, Object> args) {
        return TaskProtocol.parameters(args);
    }

    Map<String, Object> initialCheckpoint(OpenJiuwenInvocationV1 invocation) {
        return TaskProtocol.initial(invocation.capabilityId(), invocation.taskId(), owner(invocation), invocation.arguments());
    }

    private String owner(OpenJiuwenInvocationV1 invocation) {
        return fingerprint(List.of(defaults.principalId(), defaults.subjectRef(), defaults.channel(),
                defaults.purpose(), providerId, invocation.ownerAgentId(), invocation.taskId()));
    }

    static Map<String, Object> recoveryCheckpoint(OpenJiuwenInvocationV1 invocation) {
        return TaskCheckpointPolicy.recoveryCheckpoint(invocation.checkpoint());
    }

    static boolean canSupplement(Map<String, Object> checkpoint, Map<String, Object> merged) {
        return TaskCheckpointPolicy.canSupplement(checkpoint, merged);
    }

    private Map<String, Object> localEvidence(OpenJiuwenInvocationV1 invocation, Instant start, String status) {
        return Map.of("requestId", "fh-local-" + UUID.randomUUID(), "taskId", invocation.taskId(),
                "providerId", providerId, "startedAt", start.toString(), "finishedAt", Instant.now().toString(),
                "sourceStatus", status, "evidenceKind", "LOCAL_BOUNDARY", "simulated", simulated);
    }

    OpenJiuwenExecutionV1 accessDenied(OpenJiuwenInvocationV1 invocation, Instant start) {
        return new OpenJiuwenExecutionV1(OpenJiuwenExecutionV1.Status.REJECTED, invocation.taskId(),
                "当前身份或权限不允许此操作。", Map.of(), List.of(localEvidence(invocation, start, "ACCESS_DENIED")),
                "AUTHORIZATION_DENIED", "", Map.of());
    }

    static Map<String, Object> freeze(Map<String, Object> values) {
        return TaskCheckpointPolicy.freeze(values);
    }

    static String fingerprint(Object value) {
        return TaskCheckpointPolicy.fingerprint(value);
    }

    private static Map<String, Object> resultData(
            OpenJiuwenInvocationV1 invocation,
            CapabilityResultV1 result,
            EvidenceReceiptV1 receipt,
            boolean flattenBusinessData) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("capabilityId", invocation.capabilityId());
        Map<String, Object> businessData = sanitizeMap(result.data());
        data.put("result", businessData);
        if (flattenBusinessData) data.putAll(businessData);
        data.put("requestId", result.requestId());
        data.put("traceId", result.traceId());
        data.put("resultStatus", result.status().name());
        data.put("missingInputs", result.missingInputs());
        data.put("nextActions", result.nextActions());
        data.put("sourceSystem", result.sourceSystem());
        data.put("asOf", result.asOf().toString());
        if (result.task() != null) {
            data.put("taskState", Map.of(
                    "taskRef", result.task().taskRef(),
                    "status", result.task().status(),
                    "confirmationRequired", result.task().confirmationRequired()));
        }
        if (result.error() != null) {
            data.put("sourceCode", result.error().sourceCode());
            data.put("failureClass", result.error().failureClass());
            data.put("retryable", result.error().retryable());
        }
        data.put("harness", Map.of(
                "adapter", ADAPTER_ID,
                "providerId", receipt.providerId(),
                "contractVersion", CapabilityRequestV1.SCHEMA_VERSION,
                "receiptId", receipt.receiptId()));
        return Map.copyOf(data);
    }

    private Map<String, Object> evidence(
            OpenJiuwenInvocationV1 invocation,
            EvidenceReceiptV1 receipt,
            String mode) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("adapter", ADAPTER_ID);
        if ("TASK".equals(mode)) evidence.put("adapterMode", mode);
        evidence.put("capabilityId", invocation.capabilityId());
        evidence.put("ownerAgentId", invocation.ownerAgentId());
        evidence.put("providerId", receipt.providerId());
        evidence.put("sourceSystem", receipt.sourceSystem());
        evidence.put("contractVersion", CapabilityRequestV1.SCHEMA_VERSION);
        evidence.put("receiptSchemaVersion", receipt.schemaVersion());
        evidence.put("receiptId", receipt.receiptId());
        evidence.put("requestId", receipt.requestId());
        evidence.put("traceId", receipt.traceId());
        evidence.put("resultStatus", receipt.resultStatus().name());
        evidence.put("startedAt", receipt.startedAt().toString());
        evidence.put("finishedAt", receipt.finishedAt().toString());
        evidence.put("simulated", simulated);
        return Map.copyOf(evidence);
    }

    private static OpenJiuwenExecutionV1 execution(
            OpenJiuwenExecutionV1.Status status,
            OpenJiuwenInvocationV1 invocation,
            CapabilityResultV1 result,
            Map<String, Object> data,
            List<Map<String, Object>> evidence,
            String errorCode,
            String question,
            Map<String, Object> checkpoint) {
        return new OpenJiuwenExecutionV1(
                status, invocation.taskId(), result.content(), data, evidence,
                errorCode, question, checkpoint);
    }

    private static OpenJiuwenExecutionV1 rejected(OpenJiuwenInvocationV1 invocation, String content) {
        return new OpenJiuwenExecutionV1(
                OpenJiuwenExecutionV1.Status.REJECTED,
                invocation.taskId(),
                content,
                Map.of("capabilityId", invocation.capabilityId()),
                List.of(Map.of(
                        "adapter", ADAPTER_ID,
                        "capabilityId", invocation.capabilityId(),
                        "ownerAgentId", invocation.ownerAgentId(),
                        "resultStatus", CapabilityResultV1.Status.FAILED.name())),
                "FINANCE_HARNESS_ERROR",
                "",
                Map.of());
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Map.of();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Object clean = sanitize(value);
            if (key != null && clean != null) sanitized.put(key, clean);
        });
        return Map.copyOf(sanitized);
    }

    private static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.forEach((key, nested) -> {
                Object clean = sanitize(nested);
                if (key != null && clean != null) sanitized.put(String.valueOf(key), clean);
            });
            return Map.copyOf(sanitized);
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                Object clean = sanitize(item);
                if (clean != null) sanitized.add(clean);
            }
            return List.copyOf(sanitized);
        }
        return value;
    }

    static String normalizeIdempotencyKey(String raw) {
        return TaskProtocol.normalizeIdempotencyKey(raw);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    public record InvocationDefaults(
            String principalId,
            String subjectRef,
            FinanceContextV1.PrincipalType principalType,
            String channel,
            String purpose,
            FinanceContextV1.AuthenticationLevel authenticationLevel,
            Set<String> scopes,
            java.util.function.Supplier<Set<String>> currentScopes) {

        public InvocationDefaults(String principalId, String subjectRef, FinanceContextV1.PrincipalType principalType,
                String channel, String purpose, FinanceContextV1.AuthenticationLevel authenticationLevel, Set<String> scopes) {
            this(principalId, subjectRef, principalType, channel, purpose, authenticationLevel, scopes,
                    () -> scopes == null ? Set.of() : Set.copyOf(scopes));
        }
        public InvocationDefaults {
            principalId = required(principalId, "principalId");
            subjectRef = required(subjectRef, "subjectRef");
            principalType = Objects.requireNonNull(principalType, "principalType");
            channel = required(channel, "channel");
            purpose = required(purpose, "purpose");
            authenticationLevel = authenticationLevel == null
                    ? FinanceContextV1.AuthenticationLevel.UNKNOWN
                    : authenticationLevel;
            scopes = scopes == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(scopes));
            currentScopes = Objects.requireNonNull(currentScopes, "currentScopes");
        }

        public InvocationDefaults withPurpose(String newPurpose) {
            return newPurpose == null || newPurpose.isBlank()
                    ? this
                    : new InvocationDefaults(
                            principalId, subjectRef, principalType, channel, newPurpose,
                            authenticationLevel, scopes, currentScopes);
        }
    }
}
