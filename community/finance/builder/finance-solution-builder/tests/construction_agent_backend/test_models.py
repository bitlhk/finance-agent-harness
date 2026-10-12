import pytest
from pydantic import ValidationError

from finance_builder_base import ErrorDetail
from finance_builder_base.solution_build import CheckStatus
from finance_solution_builder.construction_agent_backend import (
    BudgetUsage,
    ConstructionEvent,
    ConstructionEventType,
    ConstructionResult,
    ConstructionTaskRequest,
    TaskState,
)

from tests.construction_agent_backend.support import DIGEST, task_request, validation_result


def test_task_round_trip_uses_camel_case() -> None:
    request = task_request()
    wire = request.to_wire()
    assert wire["parentLockDigest"] == DIGEST
    assert wire["workspaceBoundary"]["allowedWritePaths"] == [
        "candidate/src",
        "candidate/tests",
    ]
    assert ConstructionTaskRequest.model_validate(wire) == request


@pytest.mark.parametrize(
    ("field", "value"),
    [
        (
            "budget",
            {
                "maxDurationMs": -1,
                "maxIterations": 1,
                "maxToolCalls": 1,
                "maxCostMicrounits": 0,
            },
        ),
        ("validation_commands", ()),
        ("finding_refs", ()),
        ("approval_kinds", ()),
    ],
)
def test_task_rejects_incomplete_or_negative_context(field: str, value: object) -> None:
    with pytest.raises(ValidationError):
        task_request(**{field: value})


def test_terminal_event_type_must_match_state() -> None:
    with pytest.raises(ValidationError, match="event type and state"):
        ConstructionEvent(
            event_id="event-1",
            task_id="task-1",
            sequence=1,
            event_type=ConstructionEventType.COMPLETED,
            state=TaskState.FAILED,
            summary="Invalid terminal event.",
        )


@pytest.mark.parametrize("path", ("/tmp/candidate.py", "C:\\private\\candidate.py", "../outside.py"))
def test_allowed_write_path_must_be_workspace_relative(path: str) -> None:
    wire = task_request().to_wire()
    wire["workspaceBoundary"]["allowedWritePaths"] = [path]
    with pytest.raises(ValidationError):
        ConstructionTaskRequest.model_validate(wire)


def test_success_result_requires_passing_validation() -> None:
    with pytest.raises(ValidationError, match="every validation"):
        ConstructionResult(
            task_id="task-1",
            candidate_id="candidate-1",
            parent_lock_digest=DIGEST,
            status=TaskState.SUCCEEDED,
            summary="Invalid success.",
            validation_results=(validation_result(status=CheckStatus.FAILED),),
            budget_usage=BudgetUsage(
                duration_ms=1, iterations=1, tool_calls=1, cost_microunits=0
            ),
        )


def test_failure_result_requires_public_error() -> None:
    result = ConstructionResult(
        task_id="task-1",
        candidate_id="candidate-1",
        parent_lock_digest=DIGEST,
        status=TaskState.FAILED,
        summary="Construction failed.",
        budget_usage=BudgetUsage(
            duration_ms=1, iterations=1, tool_calls=1, cost_microunits=0
        ),
        error=ErrorDetail(code="construction.failed", message="The task failed."),
    )
    assert result.status is TaskState.FAILED
