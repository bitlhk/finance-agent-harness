package io.github.bitlhk.finance.harness.providers.nonaibanking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bitlhk.finance.harness.core.CapabilityRequestV1;
import io.github.bitlhk.finance.harness.core.CapabilityResultV1;
import io.github.bitlhk.finance.harness.core.FinanceContextV1;
import io.github.bitlhk.finance.harness.core.HarnessJson;
import io.github.bitlhk.finance.harness.core.ProviderDescriptorV1;
import io.github.bitlhk.finance.harness.core.spi.DescribedFinanceProvider;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/** Reference Provider Adapter for the non-ai-banking simulator. */
public final class NonAiBankingProvider implements DescribedFinanceProvider {
    public static final String PROVIDER_ID = "non-ai-banking-reference";
    public static final String PROVIDER_VERSION = "0.1.0-SNAPSHOT";
    public static final String ACCOUNT_BALANCE_QUERY = "account.balance.query";
    public static final String WEALTH_CUSTOMER_CONTEXT_QUERY = "wealth.customer-context.query";
    public static final String CHANNEL_PREFERENCES_UPDATE = "channel.preferences.update";
    public static final String TRANSFER_INTERNAL_CREATE = "transfer.internal.create";

    private static final Set<String> CHANNEL_PREFERENCE_PARAMETERS = Set.of(
            "action",
            "language",
            "largeTextEnabled",
            "screenReaderEnabled",
            "accessibilityMode",
            "defaultAccountId",
            "version");
    private static final Set<String> TRANSFER_PARAMETERS = Set.of(
            "fromAccountId",
            "beneficiaryId",
            "amount",
            "purpose");
    private static final Pattern TASK_REF = Pattern.compile("[A-Za-z0-9._:-]{1,160}");

    private final Client client;

    public NonAiBankingProvider(Config config) {
        this.client = new Client(Objects.requireNonNull(config, "config"));
    }

    @Override
    public ProviderDescriptorV1 descriptor() {
        return new ProviderDescriptorV1(
                ProviderDescriptorV1.SCHEMA_VERSION,
                PROVIDER_ID,
                PROVIDER_VERSION,
                List.of(
                        new ProviderDescriptorV1.CapabilitySupport(
                                ACCOUNT_BALANCE_QUERY, CapabilityRequestV1.Operation.QUERY),
                        new ProviderDescriptorV1.CapabilitySupport(
                                WEALTH_CUSTOMER_CONTEXT_QUERY, CapabilityRequestV1.Operation.QUERY),
                        new ProviderDescriptorV1.CapabilitySupport(
                                CHANNEL_PREFERENCES_UPDATE, CapabilityRequestV1.Operation.TASK),
                        new ProviderDescriptorV1.CapabilitySupport(
                                TRANSFER_INTERNAL_CREATE, CapabilityRequestV1.Operation.TASK)));
    }

    @Override
    public CapabilityResultV1 query(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.QUERY) {
            return localFailure(request, "INVALID_INPUT", "QUERY_OPERATION_REQUIRED", false,
                    "query requires operation QUERY", List.of("USE_QUERY_OPERATION"));
        }
        try {
            return switch (request.capabilityId()) {
                case ACCOUNT_BALANCE_QUERY -> accountBalance(request);
                case WEALTH_CUSTOMER_CONTEXT_QUERY -> wealthCustomerContext(request);
                default -> localFailure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                        "unsupported query capability: " + request.capabilityId(),
                        List.of("CHECK_CAPABILITY_ID"));
            };
        } catch (ProviderFailure failure) {
            return upstreamFailure(request, failure);
        }
    }

    private CapabilityResultV1 accountBalance(CapabilityRequestV1 request) {
        if (request.parameters().containsKey("accountId")) {
            Object selector = request.parameters().get("accountId");
            if (!(selector instanceof String accountId) || !accountId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
                return localFailure(request, "INVALID_INPUT", "ACCOUNT_ID_INVALID", false,
                        "accountId must identify one account", List.of("CHECK_ACCOUNT_ID"));
            }
            Response response = client.get("/api/v1/accounts/" + accountId, Map.of(), request.context());
            if (!accountId.equals(response.data().get("accountId"))) {
                return localFailure(request, "TEMPORARY_FAILURE", "ACCOUNT_ID_MISMATCH", false,
                        "bank response does not match selected account", List.of("CHECK_PROVIDER"));
            }
            return completed(request, "指定账户余额查询已完成。", Map.of("accounts", List.of(response.data())),
                    response.sourceSystem(), response.asOf());
        }
        Map<String, String> query = new LinkedHashMap<>();
        copyQueryParameter(request.parameters(), query, "page");
        copyQueryParameter(request.parameters(), query, "pageSize");
        Response response = client.get("/api/v1/accounts", query, request.context());
        return completed(request, "账户余额查询已完成。", response.data(),
                response.sourceSystem(), response.asOf());
    }

    private CapabilityResultV1 wealthCustomerContext(CapabilityRequestV1 request) {
        CompletableFuture<Response> aggregateFuture = CompletableFuture.supplyAsync(
                () -> client.get("/api/v1/wealth/aggregate", Map.of(), request.context()));
        CompletableFuture<Response> riskFuture = CompletableFuture.supplyAsync(
                () -> client.get("/api/v1/finance/risk-assessment", Map.of(), request.context()));

        Response aggregate = joinRequired(aggregateFuture);
        Response riskAssessment = joinOptional(riskFuture);
        Instant oldestSourceAsOf = riskAssessment == null || aggregate.asOf().isBefore(riskAssessment.asOf())
                ? aggregate.asOf()
                : riskAssessment.asOf();
        String readiness = riskAssessment == null ? "PARTIAL" : "READY";
        List<String> missingInputs = riskAssessment == null ? List.of("riskAssessment") : List.of();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("wealthAggregate", aggregate.data());
        data.put("riskAssessment", riskAssessment == null ? Map.of() : riskAssessment.data());
        List<Map<String, Object>> snapshots = new ArrayList<>();
        snapshots.add(sourceSnapshot("WEALTH_AGGREGATE", aggregate));
        snapshots.add(riskAssessment == null
                ? Map.of("section", "RISK_ASSESSMENT", "status", "UNAVAILABLE")
                : sourceSnapshot("RISK_ASSESSMENT", riskAssessment));
        data.put("sourceSnapshots", List.copyOf(snapshots));
        data.put("readiness", Map.of(
                "status", readiness,
                "requiredAvailable", List.of("wealthAggregate"),
                "optionalAvailable", riskAssessment == null ? List.of() : List.of("riskAssessment"),
                "missing", missingInputs));
        data.put("aggregation", Map.of(
                "mode", "PARALLEL",
                "joinPolicy", "ALL_REQUIRED",
                "required", List.of("wealthAggregate"),
                "optional", List.of("riskAssessment")));
        data.put("assembledAt", Instant.now().toString());
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                riskAssessment == null
                        ? "财富客户上下文已部分完成，风险测评暂不可用。"
                        : "财富客户上下文查询已完成。",
                data,
                missingInputs,
                riskAssessment == null ? List.of("REFRESH_RISK_ASSESSMENT") : List.of(),
                "non-ai-banking-wealth-context",
                oldestSourceAsOf,
                null,
                null);
    }

    private static Response joinRequired(CompletableFuture<Response> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof ProviderFailure failure) throw failure;
            throw exception;
        }
    }

    private static Response joinOptional(CompletableFuture<Response> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            return null;
        }
    }

    private static Map<String, Object> sourceSnapshot(String section, Response response) {
        return Map.of(
                "section", section,
                "sourceSystem", response.sourceSystem(),
                "asOf", response.asOf().toString());
    }

    private static CapabilityResultV1 completed(
            CapabilityRequestV1 request,
            String content,
            Map<String, Object> data,
            String sourceSystem,
            Instant asOf) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.COMPLETED,
                content,
                data,
                List.of(),
                List.of(),
                sourceSystem,
                asOf,
                null,
                null);
    }

    @Override
    public CapabilityResultV1 executeTask(CapabilityRequestV1 request) {
        Objects.requireNonNull(request, "request");
        if (request.operation() != CapabilityRequestV1.Operation.TASK) {
            return localFailure(request, "INVALID_INPUT", "TASK_OPERATION_REQUIRED", false,
                    "executeTask requires operation TASK", List.of("USE_TASK_OPERATION"));
        }
        if (!CHANNEL_PREFERENCES_UPDATE.equals(request.capabilityId())
                && !TRANSFER_INTERNAL_CREATE.equals(request.capabilityId())) {
            return localFailure(request, "INVALID_INPUT", "UNSUPPORTED_CAPABILITY", false,
                    "unsupported task capability: " + request.capabilityId(),
                    List.of("CHECK_CAPABILITY_ID"));
        }
        try {
            if (TRANSFER_INTERNAL_CREATE.equals(request.capabilityId())) {
                return request.taskRef() == null
                        ? startTransfer(request)
                        : resumeTransfer(request);
            }
            return request.taskRef() == null
                    ? startChannelPreferenceUpdate(request)
                    : resumeChannelPreferenceUpdate(request);
        } catch (ProviderFailure failure) {
            return request.taskRef() == null ? upstreamFailure(request, failure) : observationFailure(request, failure);
        } catch (RuntimeException failure) {
            if (request.taskRef() != null) {
                return observationFailure(request, new ProviderFailure(
                        "TEMPORARY_FAILURE", "PROVIDER_CONTRACT_FAILURE", false,
                        "unable to observe original bank task", List.of(), List.of("CHECK_TASK_STATUS"),
                        request.context().requestId(), request.context().traceId(), "non-ai-banking", Instant.now()));
            }
            return localFailure(request, "TEMPORARY_FAILURE", "PROVIDER_CONTRACT_FAILURE", false,
                    "provider could not map the bank task response", List.of("CHECK_PROVIDER"));
        }
    }

    private static CapabilityResultV1 observationFailure(CapabilityRequestV1 request, ProviderFailure failure) {
        return new CapabilityResultV1(CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(), request.context().traceId(), CapabilityResultV1.Status.UNKNOWN,
                "本次未能查证原任务，请保留原任务继续查询，勿重复提交。", Map.of(), List.of(),
                List.of("CHECK_TASK_STATUS", "CONTACT_HUMAN"), failure.sourceSystem(), failure.asOf(),
                new CapabilityResultV1.TaskState(request.taskRef(), "UNKNOWN", false, request.continuation()),
                new CapabilityResultV1.Failure("OBSERVATION_FAILED", failure.sourceCode(), failure.retryable(),
                        "observation failed; bank task outcome remains unverified", List.of()));
    }

    private CapabilityResultV1 startTransfer(CapabilityRequestV1 request) {
        if (request.confirmation() != null && request.confirmation().confirmed()) {
            return localFailure(request, "INVALID_INPUT", "TASK_REF_REQUIRED_FOR_CONFIRMATION", false,
                    "confirmation can only resume an existing task", List.of("START_TASK_FIRST"));
        }
        if (request.idempotencyKey() == null) {
            return localFailure(request, "INVALID_INPUT", "IDEMPOTENCY_KEY_REQUIRED", false,
                    "transfer requires an idempotency key", List.of("PROVIDE_IDEMPOTENCY_KEY"));
        }
        CapabilityResultV1 authentication = ensureTransferAuthentication(request);
        if (authentication != null) return authentication;

        Map<String, Object> parameters = copyParameters(request.parameters(), TRANSFER_PARAMETERS);
        Response preflight = client.post(
                "/api/v1/capabilities/" + TRANSFER_INTERNAL_CREATE + "/preflight",
                parameters,
                request.context());
        if (!Boolean.TRUE.equals(preflight.data().get("canSubmit"))) {
            return new CapabilityResultV1(
                    CapabilityResultV1.SCHEMA_VERSION,
                    request.context().requestId(),
                    request.context().traceId(),
                    CapabilityResultV1.Status.NEED_USER,
                    text(preflight.data().get("summary"), "转账办理条件未满足。"),
                    Map.of("preflight", preflight.data()),
                    List.of(),
                    List.of("UPDATE_TASK_INPUT"),
                    preflight.sourceSystem(),
                    preflight.asOf(),
                    null,
                    null);
        }

        String preflightId = requiredDataText(preflight.data(), "preflightId");
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("capabilityId", TRANSFER_INTERNAL_CREATE);
        createBody.put("operation", "SUBMIT");
        createBody.put("preflightId", preflightId);
        createBody.put("idempotencyKey", request.idempotencyKey());
        createBody.put("parameters", parameters);
        Response created = client.post("/api/v1/tasks", createBody, request.context());
        String taskRef = requiredDataText(created.data(), "taskId");

        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("preflightId", preflightId);
        continuation.put("idempotencyKey", request.idempotencyKey());
        copyIfPresent(preflight.data(), continuation, "capabilityVersion");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", text(preflight.data().get("summary"), "将发起一笔行内转账。"));
        data.put("requiredConfirmations", list(preflight.data().get("requiredConfirmations")));
        data.put("proposedParameters", Map.copyOf(parameters));
        copyIfPresent(preflight.data(), data, "transferBinding");

        return taskResult(
                request,
                CapabilityResultV1.Status.NEED_CONFIRMATION,
                "请核对转账摘要并回复「确认提交」。",
                data,
                created,
                taskRef,
                text(created.data().get("status"), "CREATED"),
                true,
                continuation,
                List.of("CONFIRM_TASK"));
    }

    private CapabilityResultV1 resumeTransfer(CapabilityRequestV1 request) {
        return resumeBankTask(request, true);
    }

    private CapabilityResultV1 resumeBankTask(CapabilityRequestV1 request, boolean transfer) {
        if (!TASK_REF.matcher(request.taskRef()).matches()) {
            return localFailure(request, "INVALID_INPUT", "TASK_REF_INVALID", false,
                    "taskRef does not match the provider task identity contract", List.of("CHECK_TASK_REF"));
        }
        Response observed = client.get("/api/v1/tasks/" + request.taskRef(), Map.of(), request.context());
        if (!request.capabilityId().equals(text(observed.data().get("capabilityId"), ""))) {
            return localFailure(request, "INVALID_INPUT", "TASK_CAPABILITY_MISMATCH", false,
                    "taskRef belongs to a different or unknown capability", List.of("CHECK_TASK_REF"));
        }
        String status = text(observed.data().get("status"), "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
        CapabilityResultV1 terminal = terminalTask(request, observed, status, transfer);
        if (terminal != null) return terminal;
        if ("UNKNOWN".equals(status) || Boolean.TRUE.equals(request.continuation().get("submitOutcomeUnknown"))) {
            if (transfer) {
                CapabilityResultV1 authentication = ensureTransferAuthentication(request);
                if (authentication != null) return authentication;
            }
            Response reconciled = client.post("/api/v1/tasks/" + request.taskRef() + "/reconcile",
                    Map.of(), request.context());
            String reconciledStatus = text(reconciled.data().get("status"), "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
            terminal = terminalTask(request, reconciled, reconciledStatus, transfer);
            if (terminal != null) return terminal;
            return pendingTask(request, reconciled, CapabilityResultV1.Status.UNKNOWN, reconciledStatus);
        }
        if ("CREATED".equals(status) || "CONFIRMED".equals(status)) {
            CapabilityRequestV1.Confirmation confirmation = request.confirmation();
            if (confirmation == null || !confirmation.confirmed()) {
                return taskResult(request, CapabilityResultV1.Status.NEED_CONFIRMATION,
                        "请通过宿主确认界面核对并授权原任务。", Map.of("task", observed.data()), observed,
                        request.taskRef(), status, true, request.continuation(), List.of("CONFIRM_TASK"));
            }
            // Both confirm and submit are mutation boundaries. Unreadable responses require observation.
            try {
                if ("CREATED".equals(status)) {
                    observed = client.post("/api/v1/tasks/" + request.taskRef() + "/confirm",
                            Map.of("confirmationText", confirmation.confirmationText()), request.context());
                    status = text(observed.data().get("status"), "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
                    terminal = terminalTask(request, observed, status, transfer);
                    if (terminal != null) return terminal;
                }
                if ("CONFIRMED".equals(status)) {
                    if (transfer) {
                        CapabilityResultV1 authentication = ensureTransferAuthentication(request);
                        if (authentication != null) return authentication;
                    }
                    observed = client.post("/api/v1/tasks/" + request.taskRef() + "/submit",
                            Map.of(), request.context());
                    status = text(observed.data().get("status"), "UNKNOWN").toUpperCase(java.util.Locale.ROOT);
                    terminal = terminalTask(request, observed, status, transfer);
                    if (terminal != null) return terminal;
                }
            } catch (ProviderFailure failure) {
                return unknownTransferAfterSubmit(request, failure);
            }
        }
        CapabilityResultV1.Status resultStatus = Set.of("PROCESSING", "SUBMITTED", "PENDING", "RUNNING")
                .contains(status) ? CapabilityResultV1.Status.PROCESSING : CapabilityResultV1.Status.UNKNOWN;
        return pendingTask(request, observed, resultStatus, status);
    }

    private static CapabilityResultV1 terminalTask(
            CapabilityRequestV1 request, Response response, String status, boolean transfer) {
        String returnedRef = text(response.data().get("taskId"), "");
        String returnedCapability = text(response.data().get("capabilityId"), "");
        if (!request.taskRef().equals(returnedRef) || !request.capabilityId().equals(returnedCapability)) {
            throw new ProviderFailure("TEMPORARY_FAILURE", "TASK_IDENTITY_MISMATCH", false,
                    "bank response does not identify the original task", List.of(), List.of("CHECK_TASK_STATUS"),
                    request.context().requestId(), request.context().traceId(), response.sourceSystem(), response.asOf());
        }
        if ("COMPLETED".equals(status)) {
            return transfer ? completedTransferTask(request, response) : completedTask(request, response);
        }
        if (Set.of("FAILED_FINAL", "FAILED", "CANCELLED").contains(status)) {
            return failedTransferTask(request, response, status);
        }
        return null;
    }

    private static CapabilityResultV1 pendingTask(
            CapabilityRequestV1 request, Response response, CapabilityResultV1.Status status, String bankStatus) {
        boolean unknown = status == CapabilityResultV1.Status.UNKNOWN;
        return taskResult(request, status,
                unknown ? "办理结果仍待查证，请勿重复提交。" : "原任务正在处理，请稍后查询。",
                Map.of("task", response.data()), response, request.taskRef(), bankStatus, false,
                unknown ? unknownContinuation(request.continuation()) : request.continuation(),
                unknown ? List.of("RECONCILE_TASK", "CONTACT_HUMAN") : List.of("CHECK_TASK_STATUS"));
    }

    private CapabilityResultV1 ensureTransferAuthentication(CapabilityRequestV1 request) {
        if (client.strongAuthenticationAvailable()) return null;
        if (client.hasStepUpCode()) {
            client.stepUp(request.context());
            return null;
        }
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.NEED_USER,
                "该转账需要先完成强认证。",
                Map.of("waitReason", "STEP_UP_AUTHENTICATION"),
                List.of("strongAuthentication"),
                List.of("STEP_UP_AUTHENTICATION"),
                "non-ai-banking",
                Instant.now(),
                null,
                null);
    }

    private static CapabilityResultV1 completedTransferTask(
            CapabilityRequestV1 request,
            Response response) {
        String taskRef = text(response.data().get("taskId"), request.taskRef());
        return taskResult(
                request,
                CapabilityResultV1.Status.COMPLETED,
                "行内转账已完成。",
                Map.of("task", response.data()),
                response,
                taskRef,
                "COMPLETED",
                false,
                request.continuation(),
                List.of());
    }

    private static CapabilityResultV1 unknownTransferAfterSubmit(
            CapabilityRequestV1 request,
            ProviderFailure failure) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.UNKNOWN,
                "办理提交结果未知，请先查证，勿重复提交。",
                Map.of("taskRef", request.taskRef()),
                List.of(),
                List.of("RECONCILE_TASK", "CONTACT_HUMAN"),
                failure.sourceSystem(),
                failure.asOf(),
                new CapabilityResultV1.TaskState(
                        request.taskRef(), "UNKNOWN", false,
                        unknownContinuation(request.continuation())),
                new CapabilityResultV1.Failure(
                        "RESULT_UNKNOWN", failure.sourceCode(), false,
                        "task mutation outcome is unknown", failure.fieldErrors()));
    }

    private static Map<String, Object> unknownContinuation(Map<String, Object> continuation) {
        Map<String, Object> values = new LinkedHashMap<>(continuation);
        values.put("submitOutcomeUnknown", true);
        return Map.copyOf(values);
    }

    private static CapabilityResultV1 failedTransferTask(
            CapabilityRequestV1 request,
            Response response,
            String status) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.FAILED,
                "银行任务未完成。",
                Map.of("task", response.data()),
                List.of(),
                List.of("CONTACT_HUMAN"),
                response.sourceSystem(),
                response.asOf(),
                new CapabilityResultV1.TaskState(
                        request.taskRef(), status, false, request.continuation()),
                new CapabilityResultV1.Failure(
                        "PERMANENT_FAILURE", "TASK_" + status, false,
                        "bank task is " + status.toLowerCase(), List.of()));
    }

    private CapabilityResultV1 startChannelPreferenceUpdate(CapabilityRequestV1 request) {
        if (request.confirmation() != null && request.confirmation().confirmed()) {
            return localFailure(request, "INVALID_INPUT", "TASK_REF_REQUIRED_FOR_CONFIRMATION", false,
                    "confirmation can only resume an existing task", List.of("START_TASK_FIRST"));
        }
        if (request.idempotencyKey() == null) {
            return localFailure(request, "INVALID_INPUT", "IDEMPOTENCY_KEY_REQUIRED", false,
                    "channel preference update requires an idempotency key",
                    List.of("PROVIDE_IDEMPOTENCY_KEY"));
        }

        Response current = client.get("/api/v1/preferences/channel", Map.of(), request.context());
        Map<String, Object> parameters = new LinkedHashMap<>();
        copyAllowedParameters(current.data(), parameters);
        copyAllowedParameters(request.parameters(), parameters);

        Response preflight = client.post(
                "/api/v1/capabilities/" + CHANNEL_PREFERENCES_UPDATE + "/preflight",
                parameters,
                request.context());
        if (!Boolean.TRUE.equals(preflight.data().get("canSubmit"))) {
            return new CapabilityResultV1(
                    CapabilityResultV1.SCHEMA_VERSION,
                    request.context().requestId(),
                    request.context().traceId(),
                    CapabilityResultV1.Status.NEED_USER,
                    text(preflight.data().get("summary"), "办理条件未满足，请补充信息后重试。"),
                    Map.of("preflight", preflight.data()),
                    List.of(),
                    List.of("UPDATE_TASK_INPUT"),
                    preflight.sourceSystem(),
                    preflight.asOf(),
                    null,
                    null);
        }

        String preflightId = requiredDataText(preflight.data(), "preflightId");
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("capabilityId", CHANNEL_PREFERENCES_UPDATE);
        createBody.put("operation", "SUBMIT");
        createBody.put("preflightId", preflightId);
        createBody.put("idempotencyKey", request.idempotencyKey());
        createBody.put("parameters", parameters);
        Response created = client.post("/api/v1/tasks", createBody, request.context());
        String taskRef = requiredDataText(created.data(), "taskId");

        Map<String, Object> continuation = new LinkedHashMap<>();
        continuation.put("preflightId", preflightId);
        continuation.put("idempotencyKey", request.idempotencyKey());
        copyIfPresent(preflight.data(), continuation, "capabilityVersion");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", text(preflight.data().get("summary"), "将更新渠道展示偏好。"));
        data.put("requiredConfirmations", list(preflight.data().get("requiredConfirmations")));
        data.put("proposedParameters", Map.copyOf(parameters));

        return taskResult(
                request,
                CapabilityResultV1.Status.NEED_CONFIRMATION,
                "请确认后更新渠道展示偏好。",
                data,
                created,
                taskRef,
                text(created.data().get("status"), "CREATED"),
                true,
                continuation,
                List.of("CONFIRM_TASK"));
    }

    private CapabilityResultV1 resumeChannelPreferenceUpdate(CapabilityRequestV1 request) {
        return resumeBankTask(request, false);
    }

    private static CapabilityResultV1 completedTask(CapabilityRequestV1 request, Response response) {
        String taskRef = text(response.data().get("taskId"), request.taskRef());
        return taskResult(
                request,
                CapabilityResultV1.Status.COMPLETED,
                "渠道展示偏好已更新。",
                Map.of("task", response.data()),
                response,
                taskRef,
                "COMPLETED",
                false,
                request.continuation(),
                List.of());
    }

    private static CapabilityResultV1 taskResult(
            CapabilityRequestV1 request,
            CapabilityResultV1.Status resultStatus,
            String content,
            Map<String, Object> data,
            Response response,
            String taskRef,
            String taskStatus,
            boolean confirmationRequired,
            Map<String, Object> continuation,
            List<String> nextActions) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                resultStatus,
                content,
                data,
                List.of(),
                nextActions,
                response.sourceSystem(),
                response.asOf(),
                new CapabilityResultV1.TaskState(
                        taskRef,
                        taskStatus,
                        confirmationRequired,
                        continuation),
                null);
    }

    private static void copyAllowedParameters(Map<String, Object> source, Map<String, Object> target) {
        for (String field : CHANNEL_PREFERENCE_PARAMETERS) {
            if (source.containsKey(field) && source.get(field) != null) {
                target.put(field, source.get(field));
            }
        }
    }

    private static Map<String, Object> copyParameters(
            Map<String, Object> source,
            Set<String> allowed) {
        Map<String, Object> target = new LinkedHashMap<>();
        for (String field : allowed) {
            if (source.containsKey(field) && source.get(field) != null) {
                target.put(field, source.get(field));
            }
        }
        return target;
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String field) {
        Object value = source.get(field);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(field, value);
        }
    }

    private static String requiredDataText(Map<String, Object> data, String field) {
        String value = text(data.get(field), "");
        if (value.isBlank()) {
            throw new IllegalStateException("upstream response missing " + field);
        }
        return value;
    }

    private static String text(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? List.copyOf(items) : List.of();
    }

    private static void copyQueryParameter(
            Map<String, Object> parameters,
            Map<String, String> query,
            String name) {
        Object value = parameters.get(name);
        if (value != null && !String.valueOf(value).isBlank()) {
            query.put(name, String.valueOf(value));
        }
    }

    private static CapabilityResultV1 upstreamFailure(CapabilityRequestV1 request, ProviderFailure failure) {
        FinanceContextV1 context = request.context();
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                context.requestId(),
                context.traceId(),
                CapabilityResultV1.Status.FAILED,
                fallback(failure.getMessage(), "暂时无法完成请求，请按提示处理后重试。"),
                Map.of(),
                List.of(),
                failure.nextActions(),
                fallback(failure.sourceSystem(), "non-ai-banking"),
                failure.asOf() == null ? Instant.now() : failure.asOf(),
                null,
                new CapabilityResultV1.Failure(
                        failure.failureClass(),
                        failure.sourceCode(),
                        failure.retryable(),
                        failure.getMessage(),
                        failure.fieldErrors()));
    }

    private static CapabilityResultV1 localFailure(
            CapabilityRequestV1 request,
            String failureClass,
            String sourceCode,
            boolean retryable,
            String message,
            List<String> nextActions) {
        return new CapabilityResultV1(
                CapabilityResultV1.SCHEMA_VERSION,
                request.context().requestId(),
                request.context().traceId(),
                CapabilityResultV1.Status.FAILED,
                "当前 Provider 无法处理该请求。",
                Map.of(),
                List.of(),
                nextActions,
                "non-ai-banking",
                Instant.now(),
                null,
                new CapabilityResultV1.Failure(
                        failureClass,
                        sourceCode,
                        retryable,
                        message,
                        List.of()));
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static final class Config {
        private final URI baseUri;
        private final String loginName;
        private final String loginSecret;
        private final String deviceId;
        private final String channel;
        private final String appVersion;
        private final Duration timeout;
        private final String stepUpCode;

        public Config(
                URI baseUri,
                String loginName,
                String loginSecret,
                String deviceId,
                String channel,
                String appVersion,
                Duration timeout) {
            this(baseUri, loginName, loginSecret, deviceId, channel, appVersion, timeout, null);
        }

        public Config(
                URI baseUri,
                String loginName,
                String loginSecret,
                String deviceId,
                String channel,
                String appVersion,
                Duration timeout,
                String stepUpCode) {
            this.baseUri = normalizeBaseUri(Objects.requireNonNull(baseUri, "baseUri"));
            this.loginName = requiredText(loginName, "loginName");
            this.loginSecret = requiredText(loginSecret, "loginSecret");
            this.deviceId = requiredText(deviceId, "deviceId");
            this.channel = requiredText(channel, "channel");
            this.appVersion = requiredText(appVersion, "appVersion");
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            this.stepUpCode = stepUpCode == null || stepUpCode.isBlank() ? null : stepUpCode.trim();
        }

        public static Config simulator(URI baseUri, String loginName, String loginSecret) {
            return new Config(baseUri, loginName, loginSecret,
                    "finance-harness", "M", "finance-harness-v1", Duration.ofSeconds(20));
        }

        private static URI normalizeBaseUri(URI uri) {
            String value = uri.toString();
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return URI.create(value);
        }

        private static String requiredText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }
    }

    private static final class Client {
        private static final ObjectMapper JSON = HarnessJson.mapper();
        private static final String SESSION_HEADER = "X-Session-Id";

        private final Config config;
        private final HttpClient http;
        private final AtomicReference<String> sessionId = new AtomicReference<>();
        private volatile boolean strongAuthentication;

        private Client(Config config) {
            this.config = config;
            this.http = HttpClient.newBuilder().connectTimeout(config.timeout).build();
        }

        private Response get(String path, Map<String, String> query, FinanceContextV1 context) {
            try {
                ensureSession(context);
                HttpResponse<String> response = sendGet(path, query, context, sessionId.get());
                if (response.statusCode() == 401) {
                    sessionId.set(null);
                    strongAuthentication = false;
                    ensureSession(context);
                    response = sendGet(path, query, context, sessionId.get());
                }
                return parse(response, context);
            } catch (ProviderFailure failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw transportFailure(context, "REQUEST_INTERRUPTED", "upstream request interrupted");
            } catch (Exception exception) {
                throw transportFailure(context, "UPSTREAM_TRANSPORT",
                        "upstream request failed: " + safeMessage(exception));
            }
        }

        private Response post(String path, Object body, FinanceContextV1 context) {
            try {
                ensureSession(context);
                HttpResponse<String> response = sendPost(path, body, context, sessionId.get());
                if (response.statusCode() == 401) {
                    sessionId.set(null);
                    strongAuthentication = false;
                    ensureSession(context);
                    response = sendPost(path, body, context, sessionId.get());
                }
                return parse(response, context);
            } catch (ProviderFailure failure) {
                throw failure;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw transportFailure(context, "REQUEST_INTERRUPTED", "upstream request interrupted");
            } catch (Exception exception) {
                throw transportFailure(context, "UPSTREAM_TRANSPORT",
                        "upstream request failed: " + safeMessage(exception));
            }
        }

        private boolean hasStepUpCode() {
            return config.stepUpCode != null;
        }

        private boolean strongAuthenticationAvailable() {
            return strongAuthentication;
        }

        private synchronized void stepUp(FinanceContextV1 context) {
            if (strongAuthentication) return;
            if (!hasStepUpCode()) {
                throw new IllegalStateException("step-up code is not configured");
            }
            post("/api/v1/sessions/step-up", Map.of("verificationCode", config.stepUpCode), context);
            strongAuthentication = true;
        }

        private synchronized void ensureSession(FinanceContextV1 context) throws Exception {
            if (sessionId.get() != null && !sessionId.get().isBlank()) {
                return;
            }
            Map<String, Object> body = Map.of(
                    "loginName", config.loginName,
                    "loginSecret", config.loginSecret,
                    "deviceId", config.deviceId,
                    "channel", config.channel);
            HttpRequest request = requestBuilder("/api/v1/sessions", context)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            Response response = parse(http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), context);
            Object value = response.data().get("sessionId");
            if (value == null || String.valueOf(value).isBlank()) {
                throw new ProviderFailure(
                        "NOT_AUTHENTICATED", "SESSION_MISSING", false,
                        "login response missing sessionId", List.of(), List.of("RELOGIN"),
                        response.requestId(), response.traceId(), response.sourceSystem(), response.asOf());
            }
            sessionId.set(String.valueOf(value));
        }

        private HttpResponse<String> sendGet(
                String path,
                Map<String, String> query,
                FinanceContextV1 context,
                String session) throws Exception {
            return http.send(requestBuilder(path + encodeQuery(query), context)
                            .header(SESSION_HEADER, session)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpResponse<String> sendPost(
                String path,
                Object body,
                FinanceContextV1 context,
                String session) throws Exception {
            return http.send(requestBuilder(path, context)
                            .header(SESSION_HEADER, session)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest.Builder requestBuilder(String path, FinanceContextV1 context) {
            return HttpRequest.newBuilder(config.baseUri.resolve(path))
                    .timeout(config.timeout)
                    .header("Accept", "application/json")
                    .header("X-Request-Id", context.requestId())
                    .header("X-Trace-Id", context.traceId())
                    .header("X-Channel", config.channel)
                    .header("X-App-Version", config.appVersion);
        }

        private static Response parse(HttpResponse<String> response, FinanceContextV1 context) throws Exception {
            String body = response.body() == null ? "" : response.body();
            if (body.isBlank()) {
                throw new ProviderFailure(
                        "TEMPORARY_FAILURE", "EMPTY_BODY", true,
                        "upstream returned an empty body", List.of(), List.of("RETRY"),
                        context.requestId(), context.traceId(), "non-ai-banking", Instant.now());
            }
            JsonNode root = JSON.readTree(body);
            String requestId = text(root, "requestId", context.requestId());
            String traceId = text(root, "traceId", context.traceId());
            String sourceSystem = text(root, "sourceSystem", "non-ai-banking");
            Instant asOf = instant(root, "asOf");
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                throw new ProviderFailure(
                        text(error, "failureClass", defaultFailureClass(response.statusCode())),
                        text(error, "sourceCode", "UPSTREAM_" + response.statusCode()),
                        error.path("retryable").asBoolean(response.statusCode() >= 500),
                        firstMessage(error),
                        stringList(error.get("fieldErrors")),
                        stringList(error.get("nextActions")),
                        requestId,
                        traceId,
                        sourceSystem,
                        asOf);
            }
            String status = text(root, "status", "");
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || "FAILED".equalsIgnoreCase(status)) {
                throw new ProviderFailure(
                        defaultFailureClass(response.statusCode()),
                        "HTTP_" + response.statusCode(),
                        response.statusCode() >= 500,
                        "upstream http status=" + response.statusCode(),
                        List.of(),
                        List.of("RETRY"),
                        requestId,
                        traceId,
                        sourceSystem,
                        asOf);
            }
            JsonNode data = root.get("data");
            if (data == null || !data.isObject()) {
                throw new ProviderFailure(
                        "TEMPORARY_FAILURE", "INVALID_RESPONSE", false,
                        "upstream response data must be an object", List.of(), List.of("CHECK_PROVIDER"),
                        requestId, traceId, sourceSystem, asOf);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> values = JSON.convertValue(data, LinkedHashMap.class);
            return new Response(requestId, traceId, values, sourceSystem, asOf);
        }

        private static ProviderFailure transportFailure(
                FinanceContextV1 context,
                String sourceCode,
                String message) {
            return new ProviderFailure(
                    "TEMPORARY_FAILURE", sourceCode, true, message,
                    List.of(), List.of("RETRY"), context.requestId(), context.traceId(),
                    "non-ai-banking", Instant.now());
        }

        private static String defaultFailureClass(int statusCode) {
            return switch (statusCode) {
                case 400 -> "INVALID_INPUT";
                case 401 -> "NOT_AUTHENTICATED";
                case 403 -> "NOT_AUTHORIZED";
                case 409 -> "CONFLICT";
                case 422 -> "NOT_ELIGIBLE";
                default -> statusCode >= 500 ? "TEMPORARY_FAILURE" : "PERMANENT_FAILURE";
            };
        }

        private static String firstMessage(JsonNode error) {
            List<String> fieldErrors = stringList(error.get("fieldErrors"));
            return fieldErrors.isEmpty()
                    ? text(error, "sourceCode", "upstream request failed")
                    : String.join("; ", fieldErrors);
        }

        private static String safeMessage(Exception exception) {
            return exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
        }

        private static String text(JsonNode node, String field, String fallback) {
            JsonNode value = node == null ? null : node.get(field);
            return value == null || value.isNull() || value.asText().isBlank()
                    ? fallback
                    : value.asText();
        }

        private static Instant instant(JsonNode node, String field) {
            String value = text(node, field, "");
            if (value.isBlank()) {
                return Instant.now();
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException exception) {
                return Instant.now();
            }
        }

        private static List<String> stringList(JsonNode node) {
            if (node == null || !node.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && !item.isNull() && !item.asText().isBlank()) {
                    values.add(item.asText());
                }
            }
            return List.copyOf(values);
        }

        private static String encodeQuery(Map<String, String> query) {
            if (query == null || query.isEmpty()) {
                return "";
            }
            StringBuilder value = new StringBuilder("?");
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (value.length() > 1) {
                    value.append('&');
                }
                value.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append('=')
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            }
            return value.toString();
        }
    }

    private record Response(
            String requestId,
            String traceId,
            Map<String, Object> data,
            String sourceSystem,
            Instant asOf) {
    }

    private static final class ProviderFailure extends RuntimeException {
        private final String failureClass;
        private final String sourceCode;
        private final boolean retryable;
        private final List<String> fieldErrors;
        private final List<String> nextActions;
        private final String requestId;
        private final String traceId;
        private final String sourceSystem;
        private final Instant asOf;

        private ProviderFailure(
                String failureClass,
                String sourceCode,
                boolean retryable,
                String message,
                List<String> fieldErrors,
                List<String> nextActions,
                String requestId,
                String traceId,
                String sourceSystem,
                Instant asOf) {
            super(message);
            this.failureClass = failureClass;
            this.sourceCode = sourceCode;
            this.retryable = retryable;
            this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
            this.nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
            this.requestId = requestId;
            this.traceId = traceId;
            this.sourceSystem = sourceSystem;
            this.asOf = asOf;
        }

        private String failureClass() {
            return failureClass;
        }

        private String sourceCode() {
            return sourceCode;
        }

        private boolean retryable() {
            return retryable;
        }

        private List<String> fieldErrors() {
            return fieldErrors;
        }

        private List<String> nextActions() {
            return nextActions;
        }

        private String requestId() {
            return requestId;
        }

        private String traceId() {
            return traceId;
        }

        private String sourceSystem() {
            return sourceSystem;
        }

        private Instant asOf() {
            return asOf;
        }
    }
}
