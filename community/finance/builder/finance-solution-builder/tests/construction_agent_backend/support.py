"""Deterministic test Backend for the public Construction Agent contract."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from datetime import datetime, timezone

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.solution_build import CheckStatus

from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    ApprovalRequest,
    BudgetUsage,
    CancelRequest,
    ConstructionBackendCapability,
    ConstructionBackendIdentity,
    ConstructionBudget,
    ConstructionEvent,
    ConstructionEventBatch,
    ConstructionEventType,
    ConstructionResourceRef,
    ConstructionResult,
    ConstructionTaskHandle,
    ConstructionTaskRequest,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
    ValidationCommand,
    ValidationResult,
    WorkspaceBoundary,
    verify_changed_paths,
)


DIGEST = hashlib.sha256(b"lock-v1").hexdigest()


def resource(locator: str, *, sha256: str | None = None) -> ConstructionResourceRef:
    return ConstructionResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=locator,
        sha256=sha256,
    )


def validation_result(
    *, status: CheckStatus = CheckStatus.PASSED
) -> ValidationResult:
    return ValidationResult(
        command_id="unit-tests",
        status=status,
        duration_ms=8,
        summary=f"Unit tests {status.value}.",
        evidence_refs=(resource("evidence/unit-tests.json", sha256=DIGEST),),
    )


def task_request(**overrides: object) -> ConstructionTaskRequest:
    values: dict[str, object] = {
        "task_id": "task-1",
        "idempotency_key": "iteration-1",
        "candidate_id": "candidate-1",
        "parent_lock_digest": DIGEST,
        "goal": "Apply the accepted finding and keep the Candidate contract stable.",
        "finding_refs": (resource("findings/wrong-route.json", sha256=DIGEST),),
        "workspace_boundary": WorkspaceBoundary(
            workspace=resource("runs/task-1"),
            allowed_write_paths=("candidate/src", "candidate/tests"),
        ),
        "validation_commands": (
            ValidationCommand(
                command_id="unit-tests",
                argv=("python", "-m", "pytest", "tests"),
                cwd="candidate",
                timeout_ms=60_000,
            ),
        ),
        "budget": ConstructionBudget(
            max_duration_ms=120_000,
            max_iterations=3,
            max_tool_calls=20,
            max_cost_microunits=500_000,
        ),
    }
    values.update(overrides)
    return ConstructionTaskRequest(**values)


@dataclass
class _TaskRecord:
    request: ConstructionTaskRequest
    created_at: datetime
    state: TaskState = TaskState.RUNNING
    events: list[ConstructionEvent] = field(default_factory=list)
    pending_approval: ApprovalRequest | None = None
    approval_decisions: dict[str, ApprovalChoice] = field(default_factory=dict)
    result: ConstructionResult | None = None


class FakeConstructionAgentBackend:
    """In-memory reference implementation used only by conformance tests."""

    def __init__(self) -> None:
        self._identity = ConstructionBackendIdentity(backend_id="test.fake", version="1.0")
        self._records: dict[str, _TaskRecord] = {}
        self._idempotency: dict[str, tuple[str, str]] = {}

    @property
    def identity(self) -> ConstructionBackendIdentity:
        return self._identity

    @property
    def capability(self) -> ConstructionBackendCapability:
        return ConstructionBackendCapability(backend=self.identity)

    def _record(self, task_id: str) -> _TaskRecord:
        try:
            return self._records[task_id]
        except KeyError as exc:
            raise LookupError("construction.task.unknown") from exc

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
    ) -> ConstructionEvent:
        if record.result is not None:
            raise RuntimeError("construction.task.already_terminal")
        event = ConstructionEvent(
            event_id=f"{record.request.task_id}:{len(record.events) + 1}",
            task_id=record.request.task_id,
            sequence=len(record.events) + 1,
            event_type=event_type,
            state=state,
            summary=summary,
            **values,
        )
        record.events.append(event)
        record.state = state
        return event

    def _finish(
        self,
        record: _TaskRecord,
        status: TaskState,
        summary: str,
        *,
        error: ErrorDetail | None,
    ) -> ConstructionResult:
        event_type = {
            TaskState.SUCCEEDED: ConstructionEventType.COMPLETED,
            TaskState.FAILED: ConstructionEventType.FAILED,
            TaskState.CANCELLED: ConstructionEventType.CANCELLED,
            TaskState.TIMED_OUT: ConstructionEventType.TIMED_OUT,
        }[status]
        self._append(record, event_type, status, summary)
        validations = tuple(
            event.validation for event in record.events if event.validation is not None
        )
        changed_paths = tuple(
            path for event in record.events for path in event.changed_paths
        )
        record.pending_approval = None
        record.result = ConstructionResult(
            task_id=record.request.task_id,
            candidate_id=record.request.candidate_id,
            parent_lock_digest=record.request.parent_lock_digest,
            status=status,
            summary=summary,
            changed_paths=changed_paths,
            validation_results=validations,
            budget_usage=BudgetUsage(
                duration_ms=20,
                iterations=1,
                tool_calls=1,
                cost_microunits=100,
            ),
            evidence_refs=(resource("evidence/construction-result.json", sha256=DIGEST),),
            error=error,
        )
        return record.result

    def start(self, request: ConstructionTaskRequest) -> ConstructionTaskHandle:
        digest = request.sha256()
        previous = self._idempotency.get(request.idempotency_key)
        if previous is not None:
            task_id, previous_digest = previous
            if previous_digest != digest:
                raise ValueError("construction.idempotency.conflict")
            return self._handle(self._record(task_id))
        if request.task_id in self._records:
            raise ValueError("construction.task.already_exists")

        record = _TaskRecord(request=request, created_at=datetime.now(timezone.utc))
        self._records[request.task_id] = record
        self._idempotency[request.idempotency_key] = (request.task_id, digest)
        self._append(record, ConstructionEventType.STARTED, TaskState.RUNNING, "Task started.")

        if (
            request.budget.max_duration_ms < 20
            or request.budget.max_iterations < 1
            or request.budget.max_tool_calls < 1
            or request.budget.max_cost_microunits < 100
        ):
            self._finish(
                record,
                TaskState.TIMED_OUT,
                "Task budget was exhausted before the first action.",
                error=ErrorDetail(
                    code="construction.budget.exhausted",
                    message="The configured construction budget is exhausted.",
                ),
            )
            return self._handle(record)

        self._append(
            record,
            ConstructionEventType.PROGRESS,
            TaskState.RUNNING,
            "Accepted finding is being applied.",
        )
        changed = (f"{request.workspace_boundary.allowed_write_paths[0]}/generated.py",)
        verify_changed_paths(request, changed)
        self._append(
            record,
            ConstructionEventType.FILE_CHANGE,
            TaskState.RUNNING,
            "Candidate source was updated.",
            changed_paths=changed,
        )
        self._append(
            record,
            ConstructionEventType.TOOL_RESULT,
            TaskState.RUNNING,
            "Configured validation passed.",
            validation=validation_result(),
        )
        approval = ApprovalRequest(
            approval_id=f"approval:{request.task_id}",
            kind=request.approval_kinds[0],
            summary="Allow the controlled action requested by the construction task.",
        )
        record.pending_approval = approval
        self._append(
            record,
            ConstructionEventType.APPROVAL_REQUIRED,
            TaskState.WAITING_APPROVAL,
            "Human approval is required before the external side effect.",
            approval_request=approval,
        )
        return self._handle(record)

    def resume(self, request: ResumeRequest) -> ConstructionTaskHandle:
        record = self._record(request.task_id)
        if request.acknowledged_sequence > len(record.events):
            raise ValueError("construction.cursor.ahead")
        return self._handle(record)

    def events(self, request: EventBatchRequest) -> ConstructionEventBatch:
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
        record = self._record(request.task_id)
        previous = record.approval_decisions.get(request.approval_id)
        if previous is not None:
            if previous is not request.decision:
                raise ValueError("construction.approval.decision_conflict")
            return self._handle(record)
        if record.pending_approval is None or (
            record.pending_approval.approval_id != request.approval_id
        ):
            raise ValueError("construction.approval.not_pending")

        record.approval_decisions[request.approval_id] = request.decision
        record.pending_approval = None
        if request.decision is ApprovalChoice.REJECT:
            self._finish(
                record,
                TaskState.FAILED,
                "The requested external side effect was rejected.",
                error=ErrorDetail(
                    code="construction.approval.rejected",
                    message="The pending construction action was rejected.",
                ),
            )
        else:
            self._append(
                record,
                ConstructionEventType.PROGRESS,
                TaskState.RUNNING,
                "Approved external verification completed.",
            )
            self._finish(record, TaskState.SUCCEEDED, "Candidate revision completed.", error=None)
        return self._handle(record)

    def cancel(self, request: CancelRequest) -> ConstructionResult:
        record = self._record(request.task_id)
        if record.result is not None:
            return record.result
        return self._finish(
            record,
            TaskState.CANCELLED,
            "Construction task was cancelled.",
            error=ErrorDetail(
                code="construction.task.cancelled",
                message="The construction task was cancelled by its caller.",
            ),
        )

    def result(self, request: ResultRequest) -> ConstructionResult:
        result = self._record(request.task_id).result
        if result is None:
            raise RuntimeError("construction.result.not_terminal")
        return result

    def report_file_change(self, task_id: str, path: str) -> ConstructionResult | None:
        """Inject one adapter-reported change to verify the write boundary."""

        record = self._record(task_id)
        try:
            verify_changed_paths(record.request, (path,))
        except ValueError:
            return self._finish(
                record,
                TaskState.FAILED,
                "The adapter reported a change outside the allowed scope.",
                error=ErrorDetail(
                    code="construction.path.outside_allowed_scope",
                    message="A reported change is outside the allowed workspace scope.",
                ),
            )
        self._append(
            record,
            ConstructionEventType.FILE_CHANGE,
            TaskState.RUNNING,
            "The adapter reported an allowed file change.",
            changed_paths=(path,),
        )
        return None
