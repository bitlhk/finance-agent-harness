"""OpenCode 1.18.29 adapter for Construction Agent Backend v1.

The vendor protocol stays private to this module. Public callers only exchange
the provider-neutral models defined by FEAT-003.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import secrets
import signal
import socket
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Protocol
from urllib.error import URLError
from urllib.request import Request, urlopen

from finance_builder_base import ErrorDetail
from finance_builder_base.solution_build import CheckStatus

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


OPENCODE_VERSION = "1.18.29"


class _OpenCodeRuntime(Protocol):
    """Small vendor-private seam used by the production client and test double."""

    def start(self) -> None: ...
    def health(self) -> Mapping[str, Any]: ...
    def configuration(self) -> Mapping[str, Any]: ...
    def create_session(self, title: str) -> str: ...
    def submit_prompt(self, session_id: str, prompt: str, model: str | None) -> None: ...
    def stream_events(self, session_id: str) -> Iterable[Mapping[str, Any]]: ...
    def diff(self, session_id: str) -> tuple[Mapping[str, Any], ...]: ...
    def reply_permission(self, session_id: str, permission_id: str, response: str) -> None: ...
    def abort(self, session_id: str) -> None: ...
    def session_exists(self, session_id: str) -> bool: ...
    def alive(self) -> bool: ...
    def stop(self) -> None: ...


@dataclass(frozen=True)
class OpenCodeAdapterConfig:
    """Private deployment configuration for one OpenCode adapter instance."""

    workspace_root: Path
    executable: str = "opencode"
    expected_version: str = OPENCODE_VERSION
    model: str | None = None
    startup_timeout_seconds: float = 15.0

    def __post_init__(self) -> None:
        root = self.workspace_root.resolve()
        if not root.is_dir():
            raise ValueError("construction.workspace.root_unavailable")
        if self.expected_version != OPENCODE_VERSION:
            raise ValueError("construction.backend.version_not_supported")
        if self.model is not None and re.fullmatch(
            r"[A-Za-z0-9._-]+/[A-Za-z0-9._:/-]+", self.model
        ) is None:
            raise ValueError("construction.backend.model_invalid")
        object.__setattr__(self, "workspace_root", root)


@dataclass(frozen=True)
class _CommandOutcome:
    returncode: int
    duration_ms: int


@dataclass
class _TaskRecord:
    request: ConstructionTaskRequest
    workspace: Path
    runtime: _OpenCodeRuntime
    created_at: datetime
    started_at: float
    session_id: str = ""
    state: TaskState = TaskState.RUNNING
    events: list[ConstructionEvent] = field(default_factory=list)
    result: ConstructionResult | None = None
    pending_approval: ApprovalRequest | None = None
    vendor_permission_id: str | None = None
    approval_decisions: dict[str, ApprovalChoice] = field(default_factory=dict)
    changed_paths: tuple[str, ...] = ()
    validations: list[ValidationResult] = field(default_factory=list)
    iterations: int = 0
    tool_calls: int = 0
    cost_microunits: int = 0
    cost_known: bool = False
    message_costs: dict[str, int] = field(default_factory=dict)
    iteration_parts: set[str] = field(default_factory=set)
    tool_parts: set[str] = field(default_factory=set)
    deadline_timer: threading.Timer | None = None
    baseline_files: dict[str, str] = field(default_factory=dict)
    pump_active: bool = False


def _workspace_snapshot(root: Path) -> dict[str, str]:
    """Return content digests for files in one isolated Candidate workspace."""

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


def _changed_workspace_paths(
    before: Mapping[str, str], after: Mapping[str, str]
) -> tuple[str, ...]:
    return tuple(
        sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
    )


def _path_stays_within(root: Path, path: Path) -> bool:
    try:
        path.resolve().relative_to(root)
    except (OSError, RuntimeError, ValueError):
        return False
    return True


def _path_stays_within_allowed(
    root: Path, path: Path, allowed: tuple[str, ...]
) -> bool:
    if not _path_stays_within(root, path):
        return False
    resolved = path.resolve()
    for relative in allowed:
        allowed_root = (root / relative).resolve()
        if not _path_stays_within(root, allowed_root):
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
        if not _path_stays_within(root, base):
            raise ValueError("construction.workspace.symlink_escape")
        if not base.is_dir():
            continue
        for path in base.rglob("*"):
            if path.is_symlink() and not _path_stays_within_allowed(root, path, allowed):
                raise ValueError("construction.workspace.symlink_escape")

def _default_background_runner(target: Callable[[], None]) -> None:
    threading.Thread(target=target, daemon=True, name="opencode-construction").start()


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


class _HttpOpenCodeRuntime:
    """Managed loopback OpenCode server using only Python's standard library."""

    def __init__(self, config: OpenCodeAdapterConfig, workspace: Path, allowed: tuple[str, ...]):
        self._config = config
        self._workspace = workspace
        self._allowed = allowed
        self._password = secrets.token_urlsafe(32)
        self._port = 0
        self._process: subprocess.Popen[bytes] | None = None
        self._runtime_home: tempfile.TemporaryDirectory[str] | None = None

    @staticmethod
    def _free_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", 0))
            return int(sock.getsockname()[1])

    def start(self) -> None:
        self._port = self._free_port()
        version = subprocess.run(
            (self._config.executable, "--version"),
            capture_output=True,
            check=False,
            text=True,
        )
        if version.returncode != 0 or version.stdout.strip() != self._config.expected_version:
            raise RuntimeError("construction.backend.version_mismatch")

        edit_rules: dict[str, str] = {"*": "deny"}
        for relative in self._allowed:
            edit_rules[relative] = "allow"
            edit_rules[f"{relative}/**"] = "allow"
        vendor_config = {
            "share": "disabled",
            "autoupdate": False,
            "permission": {
                "*": "ask",
                "read": {
                    "*": "allow",
                    "*.env": "deny",
                    "*.env.*": "deny",
                    "*.env.example": "allow",
                },
                "edit": edit_rules,
                "bash": "ask",
                "external_directory": "deny",
                "webfetch": "deny",
                "websearch": "deny",
                "task": "deny",
                "skill": "deny",
                "question": "deny",
                "doom_loop": "ask",
            },
        }
        if self._config.model:
            vendor_config["model"] = self._config.model
        environment = os.environ.copy()
        self._runtime_home = tempfile.TemporaryDirectory(prefix="finance-builder-opencode-")
        runtime_home = self._runtime_home.name
        environment.update(
            {
                "OPENCODE_SERVER_PASSWORD": self._password,
                "OPENCODE_DISABLE_AUTOUPDATE": "true",
                "OPENCODE_AUTO_SHARE": "false",
                "OPENCODE_CONFIG_CONTENT": json.dumps(vendor_config, separators=(",", ":")),
                "OPENCODE_CONFIG_DIR": runtime_home,
                "XDG_CACHE_HOME": runtime_home,
                "XDG_DATA_HOME": runtime_home,
                "XDG_STATE_HOME": runtime_home,
            }
        )
        self._process = subprocess.Popen(
            (
                self._config.executable,
                "--pure",
                "serve",
                "--hostname",
                "127.0.0.1",
                "--port",
                str(self._port),
            ),
            cwd=self._workspace,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=os.name != "nt",
            creationflags=(
                subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            ),
        )
        deadline = time.monotonic() + self._config.startup_timeout_seconds
        while time.monotonic() < deadline:
            if not self.alive():
                break
            try:
                self.health()
                return
            except (OSError, RuntimeError, URLError):
                time.sleep(0.1)
        self.stop()
        raise RuntimeError("construction.backend.startup_failed")

    def _request(self, method: str, path: str, payload: Mapping[str, Any] | None = None) -> Any:
        token = base64.b64encode(f"opencode:{self._password}".encode()).decode("ascii")
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(
            f"http://127.0.0.1:{self._port}{path}",
            data=body,
            method=method,
            headers={"Authorization": f"Basic {token}", "Content-Type": "application/json"},
        )
        with urlopen(request, timeout=self._config.startup_timeout_seconds) as response:
            raw = response.read()
        return None if not raw else json.loads(raw)

    def health(self) -> Mapping[str, Any]:
        value = self._request("GET", "/global/health")
        if not isinstance(value, Mapping):
            raise RuntimeError("construction.backend.invalid_health")
        return value

    def configuration(self) -> Mapping[str, Any]:
        value = self._request("GET", "/config")
        if not isinstance(value, Mapping):
            raise RuntimeError("construction.backend.invalid_configuration")
        return value

    def create_session(self, title: str) -> str:
        value = self._request("POST", "/session", {"title": title})
        session_id = value.get("id") if isinstance(value, Mapping) else None
        if not isinstance(session_id, str) or not session_id:
            raise RuntimeError("construction.backend.invalid_session")
        return session_id

    def submit_prompt(self, session_id: str, prompt: str, model: str | None) -> None:
        payload: dict[str, Any] = {"parts": [{"type": "text", "text": prompt}]}
        self._request("POST", f"/session/{session_id}/prompt_async", payload)

    def stream_events(self, session_id: str) -> Iterable[Mapping[str, Any]]:
        token = base64.b64encode(f"opencode:{self._password}".encode()).decode("ascii")
        request = Request(
            f"http://127.0.0.1:{self._port}/event",
            headers={"Authorization": f"Basic {token}", "Accept": "text/event-stream"},
        )
        with urlopen(request) as response:
            for raw in response:
                line = raw.decode("utf-8", errors="replace").strip()
                if line.startswith("data:"):
                    value = json.loads(line[5:].strip())
                    if isinstance(value, Mapping) and _event_session_id(value) == session_id:
                        yield value

    def diff(self, session_id: str) -> tuple[Mapping[str, Any], ...]:
        value = self._request("GET", f"/session/{session_id}/diff")
        if not isinstance(value, list) or not all(isinstance(item, Mapping) for item in value):
            raise RuntimeError("construction.backend.invalid_diff")
        return tuple(value)

    def reply_permission(self, session_id: str, permission_id: str, response: str) -> None:
        self._request(
            "POST",
            f"/session/{session_id}/permissions/{permission_id}",
            {"response": response, "remember": False},
        )

    def abort(self, session_id: str) -> None:
        self._request("POST", f"/session/{session_id}/abort", {})

    def session_exists(self, session_id: str) -> bool:
        try:
            value = self._request("GET", f"/session/{session_id}")
        except (OSError, RuntimeError, URLError):
            return False
        return isinstance(value, Mapping) and value.get("id") == session_id

    def alive(self) -> bool:
        return self._process is not None and self._process.poll() is None

    def stop(self) -> None:
        process = self._process
        if process is not None and process.poll() is None:
            if os.name == "nt":
                subprocess.run(
                    ("taskkill", "/PID", str(process.pid), "/T", "/F"),
                    capture_output=True,
                    check=False,
                )
            else:
                os.killpg(os.getpgid(process.pid), signal.SIGTERM)
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        if self._runtime_home is not None:
            self._runtime_home.cleanup()
            self._runtime_home = None


def _event_session_id(event: Mapping[str, Any]) -> str | None:
    properties = event.get("properties")
    if not isinstance(properties, Mapping):
        return None
    direct = properties.get("sessionID") or properties.get("sessionId")
    if isinstance(direct, str):
        return direct
    info = properties.get("info")
    if isinstance(info, Mapping):
        nested = info.get("sessionID") or info.get("sessionId")
        return nested if isinstance(nested, str) else None
    return None


def _configuration_is_safe(value: Mapping[str, Any], allowed: tuple[str, ...]) -> bool:
    permission = value.get("permission")
    if not isinstance(permission, Mapping):
        return False
    edit = permission.get("edit")
    read = permission.get("read")
    if not isinstance(edit, Mapping) or edit.get("*") != "deny":
        return False
    if not isinstance(read, Mapping) or any(
        read.get(pattern) != decision
        for pattern, decision in {
            "*": "allow",
            "*.env": "deny",
            "*.env.*": "deny",
            "*.env.example": "allow",
        }.items()
    ):
        return False
    if any(
        edit.get(relative) != "allow" or edit.get(f"{relative}/**") != "allow"
        for relative in allowed
    ):
        return False
    return (
        value.get("share") == "disabled"
        and value.get("autoupdate") is False
        and permission.get("*") == "ask"
        and permission.get("bash") == "ask"
        and permission.get("external_directory") == "deny"
        and permission.get("webfetch") == "deny"
        and permission.get("websearch") == "deny"
        and permission.get("task") == "deny"
        and permission.get("skill") == "deny"
        and permission.get("question") == "deny"
    )


class OpenCodeConstructionAgentBackend:
    """Provider-neutral adapter backed by one managed OpenCode server per task."""

    def __init__(
        self,
        config: OpenCodeAdapterConfig,
        *,
        runtime_factory: Callable[[OpenCodeAdapterConfig, Path, tuple[str, ...]], _OpenCodeRuntime]
        | None = None,
        command_runner: Callable[[tuple[str, ...], Path, int], _CommandOutcome] | None = None,
        background_runner: Callable[[Callable[[], None]], None] | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._config = config
        self._runtime_factory = runtime_factory or _HttpOpenCodeRuntime
        self._command_runner = command_runner or _default_command_runner
        self._background_runner = background_runner or _default_background_runner
        self._clock = clock
        self._identity = ConstructionBackendIdentity(
            backend_id="opencode.local", version=config.expected_version
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
        _validate_allowed_links(
            target, request.workspace_boundary.allowed_write_paths
        )
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
        self,
        record: _TaskRecord,
        status: TaskState,
        summary: str,
        *,
        code: str | None = None,
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
        record.vendor_permission_id = None
        if record.deadline_timer is not None:
            record.deadline_timer.cancel()
        record.result = result
        record.runtime.stop()

    def _fail(self, record: _TaskRecord, code: str, summary: str) -> None:
        self._finish(record, TaskState.FAILED, summary, code=code)

    def _check_budget(self, record: _TaskRecord) -> bool:
        usage = self._usage(record)
        limit = record.request.budget
        exhausted = (
            usage.duration_ms > limit.max_duration_ms
            or usage.iterations > limit.max_iterations
            or usage.tool_calls > limit.max_tool_calls
            or usage.cost_microunits > limit.max_cost_microunits
        )
        if not exhausted:
            return True
        try:
            record.runtime.abort(record.session_id)
        except Exception:
            pass
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
                self._config, workspace, request.workspace_boundary.allowed_write_paths
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
                health = runtime.health()
                if health.get("healthy") is not True or health.get("version") != OPENCODE_VERSION:
                    raise RuntimeError("construction.backend.health_mismatch")
                if not _configuration_is_safe(
                    runtime.configuration(), request.workspace_boundary.allowed_write_paths
                ):
                    raise RuntimeError("construction.backend.configuration_mismatch")
                record.session_id = runtime.create_session(f"Builder task {request.task_id}")
                runtime.submit_prompt(record.session_id, self._prompt(request), self._config.model)
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
                    "The OpenCode construction service could not start safely.",
                )
            return self._handle(record)

    def _timeout_task(self, task_id: str) -> None:
        with self._lock:
            record = self._record(task_id)
            if record.result is not None:
                return
            try:
                record.runtime.abort(record.session_id)
            except Exception:
                pass
            self._finish(
                record,
                TaskState.TIMED_OUT,
                "The construction task exhausted its configured wall-clock budget.",
                code="construction.budget.exhausted",
            )

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
            "Stop and request permission before any external side effect."
        )

    def _start_pump(self, record: _TaskRecord) -> None:
        if record.result is not None or record.pump_active:
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
            for event in record.runtime.stream_events(record.session_id):
                with self._lock:
                    if record.result is not None:
                        return
                    if self._map_event(record, event):
                        return
                    if not self._check_budget(record):
                        return
        except Exception:
            stream_failed = True
        finally:
            with self._lock:
                record.pump_active = False
                if record.result is None and (
                    stream_failed or record.pending_approval is None
                ):
                    self._fail(
                        record,
                        "construction.backend.event_stream_failed",
                        "The OpenCode event stream ended unexpectedly.",
                    )
    def _map_event(self, record: _TaskRecord, event: Mapping[str, Any]) -> bool:
        event_type = event.get("type")
        properties = event.get("properties")
        props = properties if isinstance(properties, Mapping) else {}
        if event_type == "permission.asked":
            vendor_id = props.get("id")
            permission = props.get("permission")
            if not isinstance(vendor_id, str) or not vendor_id:
                self._fail(record, "construction.approval.invalid", "Approval data was invalid.")
                return True
            kind = (
                ApprovalKind.SCOPE_EXPANSION
                if permission in {"edit", "external_directory"}
                else ApprovalKind.EXTERNAL_SIDE_EFFECT
            )
            if kind not in record.request.approval_kinds:
                self._fail(
                    record,
                    "construction.approval.kind_not_allowed",
                    "OpenCode requested an action outside the approved action kinds.",
                )
                return True
            approval = ApprovalRequest(
                approval_id=f"approval:{record.request.task_id}:{len(record.approval_decisions) + 1}",
                kind=kind,
                summary="OpenCode requested a controlled action.",
            )
            record.pending_approval = approval
            record.vendor_permission_id = vendor_id
            self._append(
                record,
                ConstructionEventType.APPROVAL_REQUIRED,
                TaskState.WAITING_APPROVAL,
                "Human approval is required before a controlled action.",
                approval_request=approval,
            )
            return False
        if event_type == "message.updated":
            info = props.get("info")
            cost = info.get("cost") if isinstance(info, Mapping) else None
            if isinstance(cost, (int, float)) and cost >= 0:
                message_id = info.get("id")
                key = message_id if isinstance(message_id, str) else "latest"
                record.message_costs[key] = int(float(cost) * 1_000_000)
                record.cost_microunits = sum(record.message_costs.values())
                record.cost_known = True
        if event_type == "message.part.updated":
            part = props.get("part")
            if isinstance(part, Mapping):
                part_type = part.get("type")
                part_id = part.get("id")
                key = part_id if isinstance(part_id, str) else f"event:{len(record.events)}"
                if part_type == "step-start" and key not in record.iteration_parts:
                    record.iteration_parts.add(key)
                    record.iterations += 1
                elif (
                    part_type == "tool"
                    and _tool_completed(part)
                    and key not in record.tool_parts
                ):
                    record.tool_parts.add(key)
                    record.tool_calls += 1
                self._append(
                    record,
                    ConstructionEventType.PROGRESS,
                    TaskState.RUNNING,
                    "OpenCode completed a controlled construction step.",
                )
        status = props.get("status")
        status_type = status.get("type") if isinstance(status, Mapping) else status
        if event_type == "session.idle" or (
            event_type == "session.status" and status_type == "idle"
        ):
            self._finalize(record)
            return True
        if event_type == "session.error":
            self._fail(
                record,
                "construction.backend.session_failed",
                "OpenCode could not complete the construction session.",
            )
            return True
        return False

    def _finalize(self, record: _TaskRecord) -> None:
        if not record.cost_known or not record.iteration_parts or not record.tool_parts:
            self._fail(
                record,
                "construction.budget.usage_unknown",
                "OpenCode did not provide verifiable construction usage.",
            )
            return
        vendor_diff = record.runtime.diff(record.session_id)
        vendor_paths: list[str] = []
        for item in vendor_diff:
            value = item.get("file") or item.get("path")
            if not isinstance(value, str) or not value:
                self._fail(
                    record,
                    "construction.diff.invalid",
                    "OpenCode returned an invalid file difference.",
                )
                return
            vendor_paths.append(value.replace("\\", "/"))
        actual_paths = _changed_workspace_paths(
            record.baseline_files, _workspace_snapshot(record.workspace)
        )
        if not actual_paths:
            self._fail(
                record,
                "construction.diff.missing",
                "OpenCode did not make a Candidate file change.",
            )
            return
        if vendor_paths and set(vendor_paths) != set(actual_paths):
            self._fail(
                record,
                "construction.diff.mismatch",
                "OpenCode reported a difference that did not match the Candidate workspace.",
            )
            return
        record.changed_paths = actual_paths
        try:
            verify_changed_paths(record.request, record.changed_paths)
        except ValueError:
            self._fail(
                record,
                "construction.path.outside_allowed_scope",
                "OpenCode changed a file outside the allowed workspace scope.",
            )
            return
        if any(
            not _path_stays_within_allowed(
                record.workspace,
                record.workspace / path,
                record.request.workspace_boundary.allowed_write_paths,
            )
            for path in actual_paths
        ):
            self._fail(
                record,
                "construction.path.symlink_escape",
                "OpenCode created a path that resolves outside the task workspace.",
            )
            return
        self._append(
            record,
            ConstructionEventType.FILE_CHANGE,
            TaskState.RUNNING,
            "Candidate files were updated within the approved scope.",
            changed_paths=record.changed_paths,
        )
        for command in record.request.validation_commands:
            cwd = (record.workspace / command.cwd).resolve()
            try:
                cwd.relative_to(record.workspace)
            except ValueError:
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
        if not self._check_budget(record):
            return
        self._finish(record, TaskState.SUCCEEDED, "Candidate revision completed.")

    def resume(self, request: ResumeRequest) -> ConstructionTaskHandle:
        with self._lock:
            record = self._record(request.task_id)
            if request.acknowledged_sequence > len(record.events):
                raise ValueError("construction.cursor.ahead")
            if record.result is None:
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
                or record.vendor_permission_id is None
            ):
                raise ValueError("construction.approval.not_pending")
            try:
                record.runtime.reply_permission(
                    record.session_id,
                    record.vendor_permission_id,
                    "once" if request.decision is ApprovalChoice.APPROVE else "reject",
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
            record.vendor_permission_id = None
            if request.decision is ApprovalChoice.REJECT:
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
            try:
                if record.session_id:
                    record.runtime.abort(record.session_id)
            except Exception:
                pass
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


def _tool_completed(part: Mapping[str, Any]) -> bool:
    state = part.get("state")
    return isinstance(state, Mapping) and state.get("status") == "completed"
