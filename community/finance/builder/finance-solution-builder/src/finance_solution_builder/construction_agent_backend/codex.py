"""Codex 0.153.4 adapter for Construction Agent Backend v1."""

from __future__ import annotations

import hashlib
import os
import re
import subprocess
import threading
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from finance_builder_base import ErrorDetail
from finance_builder_base.solution_build import CheckStatus

from finance_solution_builder.construction_agent_backend.codex_runtime import (
    CODEX_VERSION,
    CodexProcessConfig,
    CodexRuntime,
    StdioCodexRuntime,
)
from finance_solution_builder.construction_agent_backend.models import (
    ApprovalChoice,
    ApprovalDecision,
    ApprovalKind,
    ApprovalRequest,
    BudgetUsage,
    CancelRequest,
    ConstructionBackendCapability,
    ConstructionBackendIdentity,
    ConstructionEvent,
    ConstructionEventBatch,
    ConstructionEventType,
    ConstructionResult,
    ConstructionTaskHandle,
    ConstructionTaskRequest,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
    ValidationResult,
    verify_changed_paths,
    verify_construction_result,
)


@dataclass(frozen=True)
class CodexAdapterConfig:
    """Private deployment configuration for one Codex adapter instance."""

    workspace_root: Path
    executable: str = "codex"
    expected_version: str = CODEX_VERSION
    model: str | None = None
    startup_timeout_seconds: float = 20.0
    request_timeout_seconds: float = 120.0
    input_cost_microunits_per_million_tokens: int | None = None
    output_cost_microunits_per_million_tokens: int | None = None

    def __post_init__(self) -> None:
        root = self.workspace_root.resolve()
        if not root.is_dir():
            raise ValueError("construction.workspace.root_unavailable")
        if self.expected_version != CODEX_VERSION:
            raise ValueError("construction.backend.version_not_supported")
        if self.model is not None and re.fullmatch(r"[A-Za-z0-9._:/-]+", self.model) is None:
            raise ValueError("construction.backend.model_invalid")
        prices = (
            self.input_cost_microunits_per_million_tokens,
            self.output_cost_microunits_per_million_tokens,
        )
        if any(value is not None and value < 0 for value in prices):
            raise ValueError("construction.backend.token_price_invalid")
        if (prices[0] is None) != (prices[1] is None):
            raise ValueError("construction.backend.token_prices_incomplete")
        if self.startup_timeout_seconds <= 0 or self.request_timeout_seconds <= 0:
            raise ValueError("construction.backend.timeout_invalid")
        object.__setattr__(self, "workspace_root", root)


@dataclass(frozen=True)
class _CommandOutcome:
    returncode: int
    duration_ms: int


@dataclass
class _TaskRecord:
    request: ConstructionTaskRequest
    workspace: Path
    runtime: CodexRuntime
    created_at: datetime
    started_at: float
    thread_id: str = ""
    turn_id: str = ""
    state: TaskState = TaskState.RUNNING
    events: list[ConstructionEvent] = field(default_factory=list)
    result: ConstructionResult | None = None
    pending_approval: ApprovalRequest | None = None
    vendor_approval: Mapping[str, Any] | None = None
    approval_decisions: dict[str, ApprovalChoice] = field(default_factory=dict)
    changed_paths: tuple[str, ...] = ()
    validations: list[ValidationResult] = field(default_factory=list)
    iterations: int = 0
    tool_calls: int = 0
    cost_microunits: int = 0
    usage_known: bool = False
    tool_items: set[str] = field(default_factory=set)
    baseline_files: dict[str, str] = field(default_factory=dict)
    vendor_diff_paths: tuple[str, ...] = ()
    deadline_timer: threading.Timer | None = None
    pump_active: bool = False
    restart_pump: bool = False


def _workspace_snapshot(root: Path) -> dict[str, str]:
    snapshot: dict[str, str] = {}
    for path in root.rglob("*"):
        relative = path.relative_to(root)
        if ".git" in relative.parts:
            continue
        if path.is_symlink():
            target = os.readlink(path).encode("utf-8", errors="surrogatepass")
            snapshot[relative.as_posix()] = "symlink:" + hashlib.sha256(target).hexdigest()
        elif path.is_file():
            snapshot[relative.as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    return snapshot


def _changed_paths(before: Mapping[str, str], after: Mapping[str, str]) -> tuple[str, ...]:
    return tuple(
        sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
    )


def _stays_within(root: Path, path: Path) -> bool:
    try:
        path.resolve().relative_to(root)
    except (OSError, RuntimeError, ValueError):
        return False
    return True


def _stays_within_allowed(root: Path, path: Path, allowed: tuple[str, ...]) -> bool:
    if not _stays_within(root, path):
        return False
    resolved = path.resolve()
    for relative in allowed:
        allowed_root = (root / relative).resolve()
        if not _stays_within(root, allowed_root):
            continue
        try:
            resolved.relative_to(allowed_root)
        except ValueError:
            continue
        return True
    return False


def _validate_allowed_links(root: Path, allowed: tuple[str, ...]) -> None:
    for relative in allowed:
        base = root / relative
        if not _stays_within(root, base):
            raise ValueError("construction.workspace.symlink_escape")
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.is_symlink() and not _stays_within_allowed(root, path, allowed):
                raise ValueError("construction.workspace.symlink_escape")


def _default_background_runner(target: Callable[[], None]) -> None:
    threading.Thread(target=target, daemon=True, name="codex-construction").start()


def _default_command_runner(argv: tuple[str, ...], cwd: Path, timeout_ms: int) -> _CommandOutcome:
    started = time.monotonic()
    try:
        completed = subprocess.run(
            argv,
            cwd=cwd,
            capture_output=True,
            check=False,
            timeout=timeout_ms / 1000,
        )
        returncode = completed.returncode
    except subprocess.TimeoutExpired:
        returncode = 124
    return _CommandOutcome(
        returncode=returncode,
        duration_ms=max(0, int((time.monotonic() - started) * 1000)),
    )


def _diff_paths(diff: str) -> tuple[str, ...]:
    paths: set[str] = set()
    for raw in re.findall(r"^\+\+\+\s+(?:b/)?(.+)$", diff, flags=re.MULTILINE):
        value = raw.strip().replace("\\", "/")
        if value != "/dev/null" and not value.startswith("../"):
            paths.add(value)
    return tuple(sorted(paths))


class CodexConstructionAgentBackend:
    """Provider-neutral adapter backed by one pinned Codex process per task."""

    def __init__(
        self,
        config: CodexAdapterConfig,
        *,
        runtime_factory: Callable[[CodexProcessConfig], CodexRuntime] | None = None,
        command_runner: Callable[[tuple[str, ...], Path, int], _CommandOutcome] | None = None,
        background_runner: Callable[[Callable[[], None]], None] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._config = config
        self._runtime_factory = runtime_factory or StdioCodexRuntime
        self._command_runner = command_runner or _default_command_runner
        self._background_runner = background_runner or _default_background_runner
        self._clock = clock
        self._identity = ConstructionBackendIdentity(
            backend_id="codex.local", version=config.expected_version
        )
        self._records: dict[str, _TaskRecord] = {}
        self._idempotency: dict[str, tuple[str, str]] = {}
        self._lock = threading.RLock()

    @property
    def identity(self) -> ConstructionBackendIdentity:
        return self._identity

    @property
    def capability(self) -> ConstructionBackendCapability:
        return ConstructionBackendCapability(backend=self.identity, supports_resume=False)

    def _record(self, task_id: str) -> _TaskRecord:
        try:
            return self._records[task_id]
        except KeyError as exc:
            raise LookupError("construction.task.unknown") from exc

    def _workspace(self, request: ConstructionTaskRequest) -> Path:
        target = (
            self._config.workspace_root / request.workspace_boundary.workspace.locator
        ).resolve()
        try:
            target.relative_to(self._config.workspace_root)
        except ValueError as exc:
            raise ValueError("construction.workspace.outside_root") from exc
        if not target.is_dir():
            raise ValueError("construction.workspace.unavailable")
        _validate_allowed_links(target, request.workspace_boundary.allowed_write_paths)
        return target

    def _handle(self, record: _TaskRecord) -> ConstructionTaskHandle:
        return ConstructionTaskHandle(
            task_id=record.request.task_id,
            candidate_id=record.request.candidate_id,
            parent_lock_digest=record.request.parent_lock_digest,
            backend=self.identity,
            state=record.state,
            last_sequence=len(record.events),
            pending_approval_id=(
                record.pending_approval.approval_id if record.pending_approval else None
            ),
            created_at=record.created_at,
        )

    def _append(
        self,
        record: _TaskRecord,
        event_type: ConstructionEventType,
        state: TaskState,
        summary: str,
        **values: object,
    ) -> None:
        if record.result is not None:
            return
        record.events.append(
            ConstructionEvent(
                event_id=f"{record.request.task_id}:{len(record.events) + 1}",
                task_id=record.request.task_id,
                sequence=len(record.events) + 1,
                event_type=event_type,
                state=state,
                summary=summary,
                **values,
            )
        )
        record.state = state

    def _usage(self, record: _TaskRecord) -> BudgetUsage:
        return BudgetUsage(
            duration_ms=max(0, int((self._clock() - record.started_at) * 1000)),
            iterations=record.iterations,
            tool_calls=record.tool_calls,
            cost_microunits=record.cost_microunits,
        )

    def _finish(
        self, record: _TaskRecord, status: TaskState, summary: str, *, code: str | None = None
    ) -> None:
        if record.result is not None:
            return
        event_type = {
            TaskState.SUCCEEDED: ConstructionEventType.COMPLETED,
            TaskState.FAILED: ConstructionEventType.FAILED,
            TaskState.CANCELLED: ConstructionEventType.CANCELLED,
            TaskState.TIMED_OUT: ConstructionEventType.TIMED_OUT,
        }[status]
        error = None if code is None else ErrorDetail(code=code, message=summary)
        result = ConstructionResult(
            task_id=record.request.task_id,
            candidate_id=record.request.candidate_id,
            parent_lock_digest=record.request.parent_lock_digest,
            status=status,
            summary=summary,
            changed_paths=record.changed_paths,
            validation_results=tuple(record.validations),
            budget_usage=self._usage(record),
            error=error,
        )
        if status is TaskState.SUCCEEDED:
            verify_construction_result(record.request, result)
        self._append(record, event_type, status, summary)
        record.pending_approval = None
        record.vendor_approval = None
        if record.deadline_timer is not None:
            record.deadline_timer.cancel()
        record.result = result
        record.runtime.stop()

    def _fail(self, record: _TaskRecord, code: str, summary: str) -> None:
        self._finish(record, TaskState.FAILED, summary, code=code)

    def _interrupt(self, record: _TaskRecord) -> None:
        if record.thread_id and record.turn_id:
            try:
                record.runtime.interrupt(record.thread_id, record.turn_id)
            except Exception:
                pass

    def _check_budget(self, record: _TaskRecord) -> bool:
        usage, limit = self._usage(record), record.request.budget
        if (
            usage.duration_ms <= limit.max_duration_ms
            and usage.iterations <= limit.max_iterations
            and usage.tool_calls <= limit.max_tool_calls
            and usage.cost_microunits <= limit.max_cost_microunits
        ):
            return True
        self._interrupt(record)
        self._finish(
            record,
            TaskState.TIMED_OUT,
            "The construction task exhausted its configured budget.",
            code="construction.budget.exhausted",
        )
        return False

    def start(self, request: ConstructionTaskRequest) -> ConstructionTaskHandle:
        digest = request.sha256()
        with self._lock:
            previous = self._idempotency.get(request.idempotency_key)
            if previous is not None:
                task_id, previous_digest = previous
                if previous_digest != digest:
                    raise ValueError("construction.idempotency.conflict")
                return self._handle(self._record(task_id))
            if request.task_id in self._records:
                raise ValueError("construction.task.already_exists")
            workspace = self._workspace(request)
            runtime = self._runtime_factory(
                CodexProcessConfig(
                    executable=self._config.executable,
                    expected_version=self._config.expected_version,
                    workspace=workspace,
                    model=self._config.model,
                    startup_timeout_seconds=self._config.startup_timeout_seconds,
                    request_timeout_seconds=self._config.request_timeout_seconds,
                )
            )
            record = _TaskRecord(
                request=request,
                workspace=workspace,
                runtime=runtime,
                created_at=datetime.now(timezone.utc),
                started_at=self._clock(),
                baseline_files=_workspace_snapshot(workspace),
            )
            self._records[request.task_id] = record
            self._idempotency[request.idempotency_key] = (request.task_id, digest)
            self._append(record, ConstructionEventType.STARTED, TaskState.RUNNING, "Task started.")
            limits = request.budget
            if (
                limits.max_duration_ms == 0
                or limits.max_iterations == 0
                or limits.max_tool_calls == 0
                or limits.max_cost_microunits == 0
            ):
                self._finish(
                    record,
                    TaskState.TIMED_OUT,
                    "The construction task has no budget for a first action.",
                    code="construction.budget.exhausted",
                )
                return self._handle(record)
            try:
                runtime.start()
                record.thread_id, record.turn_id = runtime.start_turn(self._prompt(request))
                record.iterations = 1
                timer = threading.Timer(
                    request.budget.max_duration_ms / 1000,
                    lambda: self._timeout_task(request.task_id),
                )
                timer.daemon = True
                record.deadline_timer = timer
                timer.start()
                self._start_pump(record)
            except Exception:
                self._fail(
                    record,
                    "construction.backend.startup_failed",
                    "The Codex construction service could not start safely.",
                )
            return self._handle(record)

    @staticmethod
    def _prompt(request: ConstructionTaskRequest) -> str:
        findings = ", ".join(item.locator for item in request.finding_refs)
        allowed = ", ".join(request.workspace_boundary.allowed_write_paths)
        checks = "; ".join(
            f"{item.command_id}: {' '.join(item.argv)}" for item in request.validation_commands
        )
        return (
            "Apply the accepted Candidate revision in the current workspace.\n"
            f"Goal: {request.goal}\nFinding references: {findings}\n"
            f"Allowed write paths: {allowed}\nValidation commands (Builder executes): {checks}\n"
            "Do not execute the validation commands yourself. Make only the requested edit, "
            "then finish the turn. Request approval before external access or scope expansion."
        )

    def _timeout_task(self, task_id: str) -> None:
        with self._lock:
            record = self._record(task_id)
            if record.result is not None:
                return
            self._interrupt(record)
            self._finish(
                record,
                TaskState.TIMED_OUT,
                "The construction task exhausted its configured wall-clock budget.",
                code="construction.budget.exhausted",
            )

    def _start_pump(self, record: _TaskRecord) -> None:
        if record.result is not None:
            return
        if record.pump_active:
            record.restart_pump = True
            return
        record.pump_active = True
        try:
            self._background_runner(lambda: self._pump(record.request.task_id))
        except Exception:
            record.pump_active = False
            raise

    def _pump(self, task_id: str) -> None:
        with self._lock:
            record = self._record(task_id)
            if record.result is not None:
                return
        stream_failed = False
        try:
            for message in record.runtime.stream_messages(record.thread_id, record.turn_id):
                with self._lock:
                    if record.result is not None:
                        return
                    if self._map_message(record, message):
                        return
                    if not self._check_budget(record):
                        return
        except Exception:
            stream_failed = True
        finally:
            with self._lock:
                record.pump_active = False
                if (
                    record.result is None
                    and record.restart_pump
                    and record.pending_approval is None
                ):
                    record.restart_pump = False
                    self._start_pump(record)
                elif record.result is None and (stream_failed or record.pending_approval is None):
                    self._fail(
                        record,
                        "construction.backend.event_stream_failed",
                        "The Codex event stream ended unexpectedly.",
                    )

    def _map_message(self, record: _TaskRecord, message: Mapping[str, Any]) -> bool:
        method = message.get("method")
        params = message.get("params")
        values = params if isinstance(params, Mapping) else {}
        if method in {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
        }:
            try:
                summary, requested_paths = self._approval_details(record, method, values)
            except ValueError:
                try:
                    record.runtime.reply_approval(message, False)
                except Exception:
                    pass
                self._fail(
                    record,
                    "construction.approval.scope_invalid",
                    "Codex requested access outside the current Candidate workspace.",
                )
                return True
            kind = self._approval_kind(method, values)
            if kind not in record.request.approval_kinds:
                try:
                    record.runtime.reply_approval(message, False)
                except Exception:
                    pass
                self._fail(
                    record,
                    "construction.approval.kind_not_allowed",
                    "Codex requested an action outside the approved action kinds.",
                )
                return True
            approval = ApprovalRequest(
                approval_id=f"approval:{record.request.task_id}:{len(record.approval_decisions) + 1}",
                kind=kind,
                summary=summary,
                requested_paths=requested_paths,
            )
            record.pending_approval = approval
            record.vendor_approval = message
            self._append(
                record,
                ConstructionEventType.APPROVAL_REQUIRED,
                TaskState.WAITING_APPROVAL,
                "Human approval is required before a controlled action.",
                approval_request=approval,
            )
            return False
        if method == "turn/diff/updated":
            diff = values.get("diff")
            if isinstance(diff, str):
                record.vendor_diff_paths = _diff_paths(diff)
        elif method == "item/completed":
            item = values.get("item")
            if isinstance(item, Mapping):
                item_type = item.get("type")
                item_id = item.get("id")
                if (
                    item_type
                    in {
                        "commandExecution",
                        "fileChange",
                        "mcpToolCall",
                        "dynamicToolCall",
                    }
                    and isinstance(item_id, str)
                    and item_id not in record.tool_items
                ):
                    record.tool_items.add(item_id)
                    record.tool_calls += 1
                    self._append(
                        record,
                        ConstructionEventType.PROGRESS,
                        TaskState.RUNNING,
                        "Codex completed a controlled construction step.",
                    )
        elif method == "thread/tokenUsage/updated":
            self._update_usage(record, values)
        elif method == "error" and values.get("willRetry") is not True:
            self._fail(
                record,
                "construction.backend.turn_failed",
                "Codex could not complete the construction turn.",
            )
            return True
        elif method == "turn/completed":
            turn = values.get("turn")
            status = turn.get("status") if isinstance(turn, Mapping) else None
            if status == "completed":
                self._finalize(record)
            else:
                self._fail(
                    record,
                    "construction.backend.turn_failed",
                    "Codex could not complete the construction turn.",
                )
            return True
        return False

    @staticmethod
    def _approval_kind(method: object, params: Mapping[str, Any]) -> ApprovalKind:
        if method == "item/fileChange/requestApproval":
            return ApprovalKind.SCOPE_EXPANSION
        if method == "item/permissions/requestApproval":
            requested = params.get("permissions")
            values = requested if isinstance(requested, Mapping) else {}
            if values.get("network") is not None and values.get("fileSystem") is None:
                return ApprovalKind.EXTERNAL_SIDE_EFFECT
            return ApprovalKind.SCOPE_EXPANSION
        return ApprovalKind.EXTERNAL_SIDE_EFFECT

    @staticmethod
    def _workspace_relative(record: _TaskRecord, raw_path: object) -> str:
        if not isinstance(raw_path, str) or not raw_path:
            raise ValueError("construction.approval.path_invalid")
        path = Path(raw_path)
        candidate = path if path.is_absolute() else record.workspace / path
        resolved = candidate.resolve()
        try:
            relative = resolved.relative_to(record.workspace)
        except (OSError, RuntimeError, ValueError) as exc:
            raise ValueError("construction.approval.path_outside_workspace") from exc
        return relative.as_posix() or "."

    def _approval_details(
        self,
        record: _TaskRecord,
        method: str,
        params: Mapping[str, Any],
    ) -> tuple[str, tuple[str, ...]]:
        cwd = params.get("cwd")
        if cwd is not None:
            self._workspace_relative(record, cwd)
        requested: list[str] = []
        if method == "item/fileChange/requestApproval":
            requested.append(self._workspace_relative(record, params.get("grantRoot")))
            summary = "Codex requests one Candidate file change for the current turn."
        elif method == "item/permissions/requestApproval":
            permissions = params.get("permissions")
            if not isinstance(permissions, Mapping):
                raise ValueError("construction.approval.permissions_invalid")
            file_system = permissions.get("fileSystem")
            if file_system is not None:
                if not isinstance(file_system, Mapping):
                    raise ValueError("construction.approval.permissions_invalid")
                writes = file_system.get("write")
                if writes is not None:
                    if not isinstance(writes, list):
                        raise ValueError("construction.approval.permissions_invalid")
                    requested.extend(
                        self._workspace_relative(record, raw_path) for raw_path in writes
                    )
                entries = file_system.get("entries")
                if entries is not None:
                    if not isinstance(entries, list):
                        raise ValueError("construction.approval.permissions_invalid")
                    for entry in entries:
                        if not isinstance(entry, Mapping) or entry.get("access") != "write":
                            continue
                        path_value = entry.get("path")
                        if (
                            not isinstance(path_value, Mapping)
                            or path_value.get("type") != "path"
                        ):
                            raise ValueError("construction.approval.permissions_invalid")
                        requested.append(
                            self._workspace_relative(record, path_value.get("path"))
                        )
            summary = "Codex requests additional access for the current turn."
        else:
            summary = "Codex requests one command execution in the Candidate workspace."
        unique_paths = tuple(dict.fromkeys(requested))
        if (
            self._approval_kind(method, params) is ApprovalKind.SCOPE_EXPANSION
            and not unique_paths
        ):
            raise ValueError("construction.approval.path_required")
        if any(
            not _stays_within_allowed(
                record.workspace,
                record.workspace / relative,
                record.request.workspace_boundary.allowed_write_paths,
            )
            for relative in unique_paths
        ):
            raise ValueError("construction.approval.path_outside_allowed_scope")
        return summary, unique_paths

    def _update_usage(self, record: _TaskRecord, params: Mapping[str, Any]) -> None:
        token_usage = params.get("tokenUsage")
        total = token_usage.get("total") if isinstance(token_usage, Mapping) else None
        if not isinstance(total, Mapping):
            return
        input_tokens, output_tokens = total.get("inputTokens"), total.get("outputTokens")
        input_rate = self._config.input_cost_microunits_per_million_tokens
        output_rate = self._config.output_cost_microunits_per_million_tokens
        if not isinstance(input_tokens, int) or not isinstance(output_tokens, int):
            return
        if input_rate is None or output_rate is None:
            return
        record.cost_microunits = (
            input_tokens * input_rate + output_tokens * output_rate
        ) // 1_000_000
        record.usage_known = True

    def _finalize(self, record: _TaskRecord) -> None:
        if not record.usage_known:
            self._fail(
                record,
                "construction.budget.usage_unknown",
                "Codex did not provide verifiable construction usage and configured prices.",
            )
            return
        actual_paths = _changed_paths(record.baseline_files, _workspace_snapshot(record.workspace))
        if not actual_paths:
            self._fail(
                record, "construction.diff.missing", "Codex did not make a Candidate file change."
            )
            return
        record.changed_paths = actual_paths
        try:
            verify_changed_paths(record.request, actual_paths)
        except ValueError:
            self._fail(
                record,
                "construction.path.outside_allowed_scope",
                "Codex changed a file outside the allowed workspace scope.",
            )
            return
        if any(
            not _stays_within_allowed(
                record.workspace,
                record.workspace / path,
                record.request.workspace_boundary.allowed_write_paths,
            )
            for path in actual_paths
        ):
            self._fail(
                record,
                "construction.path.symlink_escape",
                "Codex created a path that resolves outside the task workspace.",
            )
            return
        self._append(
            record,
            ConstructionEventType.FILE_CHANGE,
            TaskState.RUNNING,
            "Candidate files were updated within the approved scope.",
            changed_paths=actual_paths,
        )
        for command in record.request.validation_commands:
            cwd = (record.workspace / command.cwd).resolve()
            if not _stays_within(record.workspace, cwd):
                self._fail(
                    record,
                    "construction.validation.cwd_invalid",
                    "A validation working directory was outside the task workspace.",
                )
                return
            outcome = self._command_runner(command.argv, cwd, command.timeout_ms)
            status = CheckStatus.PASSED if outcome.returncode == 0 else CheckStatus.FAILED
            validation = ValidationResult(
                command_id=command.command_id,
                status=status,
                duration_ms=outcome.duration_ms,
                summary=(
                    "Configured validation passed."
                    if status is CheckStatus.PASSED
                    else "Configured validation failed."
                ),
            )
            record.validations.append(validation)
            self._append(
                record,
                ConstructionEventType.TOOL_RESULT,
                TaskState.RUNNING,
                validation.summary,
                validation=validation,
            )
            if status is not CheckStatus.PASSED:
                self._fail(
                    record,
                    "construction.validation.failed",
                    "A configured Candidate validation failed.",
                )
                return
        if self._check_budget(record):
            self._finish(record, TaskState.SUCCEEDED, "Candidate revision completed.")

    def resume(self, request: ResumeRequest) -> ConstructionTaskHandle:
        with self._lock:
            record = self._record(request.task_id)
            if request.acknowledged_sequence > len(record.events):
                raise ValueError("construction.cursor.ahead")
            if record.result is None:
                self._interrupt(record)
                self._fail(
                    record,
                    "construction.resume.unavailable",
                    "The current parent Lock and workspace identity cannot be verified for resume.",
                )
            return self._handle(record)

    def events(self, request: EventBatchRequest) -> ConstructionEventBatch:
        with self._lock:
            record = self._record(request.task_id)
            if request.after_sequence > len(record.events):
                raise ValueError("construction.cursor.ahead")
            selected = tuple(record.events[request.after_sequence :][: request.max_events])
            next_sequence = selected[-1].sequence if selected else request.after_sequence
            return ConstructionEventBatch(
                task_id=request.task_id,
                after_sequence=request.after_sequence,
                events=selected,
                next_sequence=next_sequence,
                terminal=record.result is not None,
            )

    def approve(self, request: ApprovalDecision) -> ConstructionTaskHandle:
        with self._lock:
            record = self._record(request.task_id)
            previous = record.approval_decisions.get(request.approval_id)
            if previous is not None:
                if previous is not request.decision:
                    raise ValueError("construction.approval.decision_conflict")
                return self._handle(record)
            if (
                record.pending_approval is None
                or record.pending_approval.approval_id != request.approval_id
                or record.vendor_approval is None
            ):
                raise ValueError("construction.approval.not_pending")
            try:
                record.runtime.reply_approval(
                    record.vendor_approval, request.decision is ApprovalChoice.APPROVE
                )
            except Exception:
                self._fail(
                    record,
                    "construction.approval.delivery_failed",
                    "The controlled action decision could not be delivered safely.",
                )
                return self._handle(record)
            record.approval_decisions[request.approval_id] = request.decision
            record.pending_approval = None
            record.vendor_approval = None
            if request.decision is ApprovalChoice.REJECT:
                self._interrupt(record)
                self._fail(
                    record,
                    "construction.approval.rejected",
                    "The requested controlled action was rejected.",
                )
            else:
                self._append(
                    record,
                    ConstructionEventType.PROGRESS,
                    TaskState.RUNNING,
                    "The approved construction action is continuing.",
                )
                self._start_pump(record)
            return self._handle(record)

    def cancel(self, request: CancelRequest) -> ConstructionResult:
        with self._lock:
            record = self._record(request.task_id)
            if record.result is not None:
                return record.result
            self._interrupt(record)
            self._finish(
                record,
                TaskState.CANCELLED,
                "The construction task was cancelled by its caller.",
                code="construction.task.cancelled",
            )
            assert record.result is not None
            return record.result

    def result(self, request: ResultRequest) -> ConstructionResult:
        with self._lock:
            result = self._record(request.task_id).result
            if result is None:
                raise RuntimeError("construction.result.not_terminal")
            return result
