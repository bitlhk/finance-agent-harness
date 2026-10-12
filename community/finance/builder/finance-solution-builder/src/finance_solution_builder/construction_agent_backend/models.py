"""Provider-neutral Construction Agent Backend v1 contract models."""

from __future__ import annotations

import hashlib
import json
import re
from datetime import datetime, timezone
from enum import Enum
from pathlib import PurePosixPath
from typing import Any, Literal
from urllib.parse import parse_qsl, urlsplit

from pydantic import ConfigDict, Field, field_validator, model_validator

from finance_builder_base import ContractModel, ErrorDetail, ResourceKind, ResourceRef
from finance_builder_base.contracts import sanitize_public_text
from finance_builder_base.solution_build import CheckStatus


SCHEMA_VERSION = "1.0"
_IDENTIFIER = r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$"
_NAMESPACED = r"^[a-z][a-z0-9.-]*$"
_SHA256 = r"^[0-9a-f]{64}$"
_SENSITIVE_KEY = re.compile(
    r"(?i)^(?:authorization|api[_-]?key|access[_-]?token|password|client[_-]?secret|"
    r"private[_-]?key|token|secret|prompt|system[_-]?prompt|chain[_-]?of[_-]?thought|reasoning)$"
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in tail)


def _contains_url_credentials(value: str) -> bool:
    parsed = urlsplit(value)
    return parsed.username is not None or parsed.password is not None or any(
        _SENSITIVE_KEY.fullmatch(key) for key, _ in parse_qsl(parsed.query, keep_blank_values=True)
    )


def _validate_public_payload(value: Any) -> Any:
    if isinstance(value, dict):
        for key, item in value.items():
            if _SENSITIVE_KEY.fullmatch(str(key)):
                raise ValueError("public contract may not contain private or credential fields")
            _validate_public_payload(item)
    elif isinstance(value, (list, tuple)):
        for item in value:
            _validate_public_payload(item)
    elif isinstance(value, str) and sanitize_public_text(value) != value:
        raise ValueError("public contract may not contain sensitive text or host paths")
    return value


def _public_text(value: str) -> str:
    if sanitize_public_text(value) != value:
        raise ValueError("public text may not contain sensitive text or host paths")
    return value


def _relative_path(value: str, *, allow_dot: bool = False) -> str:
    normalized = value.replace("\\", "/")
    if allow_dot and value == ".":
        return value
    path = PurePosixPath(normalized)
    if (
        not normalized
        or normalized.startswith("/")
        or path.is_absolute()
        or ".." in path.parts
        or ":" in path.parts[0]
    ):
        raise ValueError("path must be a safe workspace-relative path")
    return path.as_posix().rstrip("/")


def _unique(values: tuple[str, ...], field_name: str) -> tuple[str, ...]:
    if len(values) != len(set(values)):
        raise ValueError(f"{field_name} must contain unique values")
    return values


class WireModel(ContractModel):
    """Stable camelCase wire model with additive-field compatibility."""

    model_config = ConfigDict(
        extra="ignore",
        validate_assignment=True,
        populate_by_name=True,
        alias_generator=_to_camel,
    )

    @model_validator(mode="before")
    @classmethod
    def reject_private_input(cls, value: Any) -> Any:
        return _validate_public_payload(value)

    def to_wire(self, *, exclude_none: bool = True) -> dict[str, Any]:
        return self.model_dump(mode="json", by_alias=True, exclude_none=exclude_none)

    def canonical_bytes(self) -> bytes:
        return json.dumps(
            self.to_wire(), ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")

    def sha256(self) -> str:
        return hashlib.sha256(self.canonical_bytes()).hexdigest()


class ConstructionResourceRef(ResourceRef):
    """Portable evidence or workspace reference using v1 wire naming."""

    model_config = WireModel.model_config

    @model_validator(mode="before")
    @classmethod
    def reject_private_input(cls, value: Any) -> Any:
        return _validate_public_payload(value)

    @model_validator(mode="after")
    def validate_public_locator(self) -> "ConstructionResourceRef":
        if self.kind is ResourceKind.HTTP and (
            _contains_url_credentials(self.locator)
            or sanitize_public_text(self.locator) != self.locator
        ):
            raise ValueError("http resource may not contain credential material")
        return self


class TaskState(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    WAITING_APPROVAL = "waiting-approval"
    SUCCEEDED = "succeeded"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMED_OUT = "timed-out"


TERMINAL_STATES = frozenset(
    {TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED, TaskState.TIMED_OUT}
)


class ConstructionEventType(str, Enum):
    STARTED = "started"
    PROGRESS = "progress"
    FILE_CHANGE = "file-change"
    TOOL_RESULT = "tool-result"
    APPROVAL_REQUIRED = "approval-required"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMED_OUT = "timed-out"


class ApprovalKind(str, Enum):
    EXTERNAL_SIDE_EFFECT = "external-side-effect"
    SCOPE_EXPANSION = "scope-expansion"


class ApprovalChoice(str, Enum):
    APPROVE = "approve"
    REJECT = "reject"


class ConstructionBackendIdentity(WireModel):
    backend_id: str = Field(min_length=1, max_length=200, pattern=_NAMESPACED)
    version: str = Field(min_length=1, max_length=100)

    _safe_version = field_validator("version")(_public_text)


class ConstructionBudget(WireModel):
    max_duration_ms: int = Field(ge=0)
    max_iterations: int = Field(ge=0)
    max_tool_calls: int = Field(ge=0)
    max_cost_microunits: int = Field(ge=0)


class BudgetUsage(WireModel):
    duration_ms: int = Field(ge=0)
    iterations: int = Field(ge=0)
    tool_calls: int = Field(ge=0)
    cost_microunits: int = Field(ge=0)


class WorkspaceBoundary(WireModel):
    workspace: ConstructionResourceRef
    allowed_write_paths: tuple[str, ...] = Field(min_length=1)

    @field_validator("allowed_write_paths")
    @classmethod
    def validate_allowed_paths(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_path(item) for item in value)
        return _unique(normalized, "allowed_write_paths")

    @model_validator(mode="after")
    def require_workspace_reference(self) -> "WorkspaceBoundary":
        if self.workspace.kind is not ResourceKind.WORKSPACE_PATH:
            raise ValueError("workspace must use a workspace-path reference")
        return self


class ValidationCommand(WireModel):
    command_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    argv: tuple[str, ...] = Field(min_length=1)
    cwd: str = "."
    timeout_ms: int = Field(gt=0)

    @field_validator("argv")
    @classmethod
    def validate_argv(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if any(not item.strip() for item in value):
            raise ValueError("argv may not contain empty values")
        return tuple(_public_text(item) for item in value)

    @field_validator("cwd")
    @classmethod
    def validate_cwd(cls, value: str) -> str:
        return _relative_path(value, allow_dot=True)


class ConstructionTaskRequest(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    idempotency_key: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    parent_lock_digest: str = Field(pattern=_SHA256)
    goal: str = Field(min_length=1, max_length=2000)
    finding_refs: tuple[ConstructionResourceRef, ...] = Field(min_length=1)
    workspace_boundary: WorkspaceBoundary
    validation_commands: tuple[ValidationCommand, ...] = Field(min_length=1)
    budget: ConstructionBudget
    approval_kinds: tuple[ApprovalKind, ...] = Field(
        default=(ApprovalKind.EXTERNAL_SIDE_EFFECT,), min_length=1
    )

    _safe_goal = field_validator("goal")(_public_text)

    @field_validator("validation_commands")
    @classmethod
    def unique_commands(
        cls, value: tuple[ValidationCommand, ...]
    ) -> tuple[ValidationCommand, ...]:
        _unique(tuple(item.command_id for item in value), "validation command ids")
        return value

    @field_validator("approval_kinds")
    @classmethod
    def unique_approval_kinds(cls, value: tuple[ApprovalKind, ...]) -> tuple[ApprovalKind, ...]:
        if len(value) != len(set(value)):
            raise ValueError("approval_kinds must contain unique values")
        return value


class ApprovalRequest(WireModel):
    approval_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    kind: ApprovalKind
    summary: str = Field(min_length=1, max_length=1000)
    requested_paths: tuple[str, ...] = ()

    _safe_summary = field_validator("summary")(_public_text)

    @field_validator("requested_paths")
    @classmethod
    def validate_requested_paths(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_path(item) for item in value)
        return _unique(normalized, "requested_paths")


class ValidationResult(WireModel):
    command_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    status: CheckStatus
    duration_ms: int = Field(ge=0)
    summary: str = Field(min_length=1, max_length=1000)
    evidence_refs: tuple[ConstructionResourceRef, ...] = ()

    _safe_summary = field_validator("summary")(_public_text)


class ConstructionEvent(WireModel):
    event_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    sequence: int = Field(gt=0)
    event_type: ConstructionEventType
    state: TaskState
    summary: str = Field(min_length=1, max_length=1000)
    changed_paths: tuple[str, ...] = ()
    validation: ValidationResult | None = None
    approval_request: ApprovalRequest | None = None
    evidence_refs: tuple[ConstructionResourceRef, ...] = ()
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    _safe_summary = field_validator("summary")(_public_text)

    @field_validator("changed_paths")
    @classmethod
    def validate_changed_paths(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_path(item) for item in value)
        return _unique(normalized, "changed_paths")

    @model_validator(mode="after")
    def validate_event_shape(self) -> "ConstructionEvent":
        if (self.event_type is ConstructionEventType.FILE_CHANGE) != bool(self.changed_paths):
            raise ValueError("file-change event requires changed_paths exclusively")
        if (self.event_type is ConstructionEventType.TOOL_RESULT) != (self.validation is not None):
            raise ValueError("tool-result event requires validation exclusively")
        if (self.event_type is ConstructionEventType.APPROVAL_REQUIRED) != (
            self.approval_request is not None
        ):
            raise ValueError("approval-required event requires approval_request exclusively")
        expected_state = {
            ConstructionEventType.STARTED: TaskState.RUNNING,
            ConstructionEventType.PROGRESS: TaskState.RUNNING,
            ConstructionEventType.FILE_CHANGE: TaskState.RUNNING,
            ConstructionEventType.TOOL_RESULT: TaskState.RUNNING,
            ConstructionEventType.APPROVAL_REQUIRED: TaskState.WAITING_APPROVAL,
            ConstructionEventType.COMPLETED: TaskState.SUCCEEDED,
            ConstructionEventType.FAILED: TaskState.FAILED,
            ConstructionEventType.CANCELLED: TaskState.CANCELLED,
            ConstructionEventType.TIMED_OUT: TaskState.TIMED_OUT,
        }
        if self.state is not expected_state[self.event_type]:
            raise ValueError("event type and state do not match")
        return self


class ConstructionTaskHandle(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    parent_lock_digest: str = Field(pattern=_SHA256)
    backend: ConstructionBackendIdentity
    state: TaskState
    last_sequence: int = Field(ge=0)
    pending_approval_id: str | None = Field(
        default=None, min_length=1, max_length=200, pattern=_IDENTIFIER
    )
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @model_validator(mode="after")
    def validate_pending_approval(self) -> "ConstructionTaskHandle":
        waiting = self.state is TaskState.WAITING_APPROVAL
        if waiting != (self.pending_approval_id is not None):
            raise ValueError("waiting-approval state requires exactly one pending approval")
        return self


class ResumeRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    acknowledged_sequence: int = Field(ge=0)


class EventBatchRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    after_sequence: int = Field(ge=0)
    max_events: int = Field(default=100, ge=1, le=500)


class ConstructionEventBatch(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    after_sequence: int = Field(ge=0)
    events: tuple[ConstructionEvent, ...]
    next_sequence: int = Field(ge=0)
    terminal: bool

    @model_validator(mode="after")
    def validate_batch(self) -> "ConstructionEventBatch":
        if any(item.task_id != self.task_id for item in self.events):
            raise ValueError("event batch contains another task")
        sequences = tuple(item.sequence for item in self.events)
        if sequences and (
            sequences[0] <= self.after_sequence
            or any(previous >= current for previous, current in zip(sequences, sequences[1:]))
        ):
            raise ValueError("event batch sequence must be strictly increasing after the cursor")
        event_ids = tuple(item.event_id for item in self.events)
        if len(event_ids) != len(set(event_ids)):
            raise ValueError("event batch event_id must be unique")
        expected_next = sequences[-1] if sequences else self.after_sequence
        if self.next_sequence != expected_next:
            raise ValueError("next_sequence must equal the latest returned sequence")
        terminal_count = sum(item.state in TERMINAL_STATES for item in self.events)
        terminal_in_batch = terminal_count == 1
        if terminal_count > 1:
            raise ValueError("event batch may contain at most one terminal event")
        if terminal_in_batch and not self.terminal:
            raise ValueError("batch containing a terminal event must be marked terminal")
        if terminal_in_batch and self.events[-1].state not in TERMINAL_STATES:
            raise ValueError("terminal event must be the final event in a batch")
        return self


class ApprovalDecision(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    approval_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    decision: ApprovalChoice


class CancelRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    reason: str = Field(min_length=1, max_length=500)

    _safe_reason = field_validator("reason")(_public_text)


class ResultRequest(WireModel):
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)


class ConstructionResult(WireModel):
    schema_version: Literal["1.0"] = SCHEMA_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    candidate_id: str = Field(min_length=1, max_length=200, pattern=_IDENTIFIER)
    parent_lock_digest: str = Field(pattern=_SHA256)
    status: TaskState
    summary: str = Field(min_length=1, max_length=1000)
    changed_paths: tuple[str, ...] = ()
    validation_results: tuple[ValidationResult, ...] = ()
    budget_usage: BudgetUsage
    evidence_refs: tuple[ConstructionResourceRef, ...] = ()
    error: ErrorDetail | None = None

    _safe_summary = field_validator("summary")(_public_text)

    @field_validator("changed_paths")
    @classmethod
    def validate_changed_paths(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        normalized = tuple(_relative_path(item) for item in value)
        return _unique(normalized, "changed_paths")

    @model_validator(mode="after")
    def validate_terminal_result(self) -> "ConstructionResult":
        if self.status not in TERMINAL_STATES:
            raise ValueError("result requires a terminal status")
        succeeded = self.status is TaskState.SUCCEEDED
        if succeeded:
            if self.error is not None or not self.validation_results:
                raise ValueError("successful result requires validation results and no error")
            if any(item.status is not CheckStatus.PASSED for item in self.validation_results):
                raise ValueError("successful result requires every validation to pass")
        elif self.error is None:
            raise ValueError("non-success result requires a public error")
        return self


class ConstructionBackendCapability(WireModel):
    backend: ConstructionBackendIdentity
    schema_versions: tuple[str, ...] = (SCHEMA_VERSION,)
    event_types: tuple[ConstructionEventType, ...] = tuple(ConstructionEventType)
    supports_resume: bool = True
    supports_approval: bool = True


def path_is_allowed(path: str, allowed_write_paths: tuple[str, ...]) -> bool:
    """Return whether one normalized path is inside an allowed relative prefix."""

    candidate = PurePosixPath(_relative_path(path))
    for allowed in allowed_write_paths:
        prefix = PurePosixPath(_relative_path(allowed))
        if candidate == prefix or prefix in candidate.parents:
            return True
    return False


def verify_changed_paths(request: ConstructionTaskRequest, paths: tuple[str, ...]) -> None:
    """Reject any Backend-reported path outside the task's immutable boundary."""

    if any(
        not path_is_allowed(path, request.workspace_boundary.allowed_write_paths)
        for path in paths
    ):
        raise ValueError("construction.path.outside_allowed_scope")


def verify_task_handle(
    request: ConstructionTaskRequest, handle: ConstructionTaskHandle
) -> None:
    """Bind a Backend handle to the immutable task identity."""

    if handle.task_id != request.task_id:
        raise ValueError("construction.handle.task_mismatch")
    if handle.candidate_id != request.candidate_id:
        raise ValueError("construction.handle.candidate_mismatch")
    if handle.parent_lock_digest != request.parent_lock_digest:
        raise ValueError("construction.handle.parent_lock_mismatch")


def verify_event_batch(
    request: ConstructionTaskRequest, batch: ConstructionEventBatch
) -> None:
    """Verify an event batch against the original task and write boundary."""

    if batch.task_id != request.task_id:
        raise ValueError("construction.events.task_mismatch")
    for event in batch.events:
        verify_changed_paths(request, event.changed_paths)


def verify_construction_result(
    request: ConstructionTaskRequest, result: ConstructionResult
) -> None:
    """Verify Backend terminal evidence before accepting a revised Candidate."""

    if result.task_id != request.task_id:
        raise ValueError("construction.result.task_mismatch")
    if result.candidate_id != request.candidate_id:
        raise ValueError("construction.result.candidate_mismatch")
    if result.parent_lock_digest != request.parent_lock_digest:
        raise ValueError("construction.result.parent_lock_mismatch")
    verify_changed_paths(request, result.changed_paths)
    if result.status is not TaskState.SUCCEEDED:
        return

    expected_commands = tuple(item.command_id for item in request.validation_commands)
    actual_commands = tuple(item.command_id for item in result.validation_results)
    if len(actual_commands) != len(set(actual_commands)) or set(actual_commands) != set(
        expected_commands
    ):
        raise ValueError("construction.result.validation_coverage_mismatch")
    limits = request.budget
    usage = result.budget_usage
    if (
        usage.duration_ms > limits.max_duration_ms
        or usage.iterations > limits.max_iterations
        or usage.tool_calls > limits.max_tool_calls
        or usage.cost_microunits > limits.max_cost_microunits
    ):
        raise ValueError("construction.result.budget_exceeded")


WIRE_MODELS = (
    ConstructionTaskRequest,
    ConstructionTaskHandle,
    ResumeRequest,
    EventBatchRequest,
    ConstructionEventBatch,
    ApprovalDecision,
    CancelRequest,
    ResultRequest,
    ConstructionResult,
    ConstructionBackendCapability,
)


def construction_agent_backend_schema() -> dict[str, Any]:
    """Build the canonical JSON Schema bundle for the public v1 wire models."""

    definitions: dict[str, Any] = {}
    roots: list[dict[str, str]] = []
    for model in WIRE_MODELS:
        schema = model.model_json_schema(by_alias=True, ref_template="#/$defs/{model}")
        nested = schema.pop("$defs", {})
        for name, definition in nested.items():
            previous = definitions.setdefault(name, definition)
            if previous != definition:
                raise ValueError(f"schema definition conflict: {name}")
        definitions[model.__name__] = schema
        roots.append({"$ref": f"#/$defs/{model.__name__}"})
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "$id": "https://openjiuwen.dev/finance/construction-agent-backend.v1.schema.json",
        "title": "Finance Solution Builder Construction Agent Backend v1",
        "anyOf": roots,
        "$defs": dict(sorted(definitions.items())),
    }
