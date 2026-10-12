from types import SimpleNamespace

import pytest

from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    BudgetUsage,
    ConstructionEventType,
    EventBatchRequest,
    ResultRequest,
    TaskState,
    verify_construction_result,
    verify_task_handle,
)
from finance_solution_builder.construction_agent_backend.codex import (
    CodexAdapterConfig,
    CodexConstructionAgentBackend,
)

from tests.construction_agent_backend.conformance import run_successful_construction
from tests.construction_agent_backend.codex_protocol_double import CodexProtocolDouble
from tests.construction_agent_backend.support import FakeConstructionAgentBackend, task_request


def test_fake_backend_passes_successful_construction_conformance() -> None:
    run_successful_construction(FakeConstructionAgentBackend())


def test_codex_backend_passes_successful_construction_conformance(tmp_path) -> None:
    workspace = tmp_path / "runs/task-1"
    (workspace / "candidate/src").mkdir(parents=True)
    (workspace / "candidate/tests").mkdir(parents=True)
    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=tmp_path,
            input_cost_microunits_per_million_tokens=1_000_000,
            output_cost_microunits_per_million_tokens=1_000_000,
        ),
        runtime_factory=CodexProtocolDouble,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
        background_runner=lambda target: target(),
    )
    request = task_request()
    handle = backend.start(request)
    verify_task_handle(request, handle)
    assert handle.state is TaskState.WAITING_APPROVAL
    first = backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0))
    assert first.events[0].event_type is ConstructionEventType.STARTED
    assert first.events[-1].event_type is ConstructionEventType.APPROVAL_REQUIRED
    completed = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=handle.pending_approval_id or "missing",
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert completed.state is TaskState.SUCCEEDED
    result = backend.result(ResultRequest(task_id=request.task_id))
    verify_construction_result(request, result)


def test_repeated_start_and_approval_are_idempotent() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    first = backend.start(request)
    second = backend.start(request)
    assert first == second
    approval = ApprovalDecision(
        task_id=request.task_id,
        approval_id=first.pending_approval_id or "missing",
        decision=ApprovalChoice.APPROVE,
    )
    completed = backend.approve(approval)
    event_count = len(
        backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0)).events
    )
    assert backend.approve(approval) == completed
    assert (
        len(backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0)).events)
        == event_count
    )


def _completed_result() -> tuple[object, object]:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    handle = backend.start(request)
    backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=handle.pending_approval_id or "missing",
            decision=ApprovalChoice.APPROVE,
        )
    )
    return request, backend.result(ResultRequest(task_id=request.task_id))


def test_task_handle_verifier_rejects_parent_lock_mismatch() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    handle = backend.start(request).model_copy(update={"parent_lock_digest": "0" * 64})
    with pytest.raises(ValueError, match="parent_lock_mismatch"):
        verify_task_handle(request, handle)


def test_result_verifier_rejects_missing_validation() -> None:
    request, result = _completed_result()
    incomplete = result.model_copy(update={"validation_results": ()})
    with pytest.raises(ValueError, match="validation_coverage_mismatch"):
        verify_construction_result(request, incomplete)


def test_result_verifier_rejects_success_over_budget() -> None:
    request, result = _completed_result()
    over_budget = result.model_copy(
        update={
            "budget_usage": BudgetUsage(
                duration_ms=request.budget.max_duration_ms + 1,
                iterations=1,
                tool_calls=1,
                cost_microunits=1,
            )
        }
    )
    with pytest.raises(ValueError, match="budget_exceeded"):
        verify_construction_result(request, over_budget)
