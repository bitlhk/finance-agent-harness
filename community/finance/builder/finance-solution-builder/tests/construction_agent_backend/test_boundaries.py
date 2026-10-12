import json

import pytest

from finance_solution_builder.construction_agent_backend import (
    CancelRequest,
    ConstructionEvent,
    ConstructionEventBatch,
    ConstructionEventType,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
    path_is_allowed,
    verify_event_batch,
)
from tests.construction_agent_backend.support import FakeConstructionAgentBackend, task_request


def test_path_prefix_boundary() -> None:
    assert path_is_allowed("candidate/src/agent.py", ("candidate/src",))
    assert not path_is_allowed("candidate/tests/test_agent.py", ("candidate/src",))


def test_outside_change_fails_closed() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    backend.start(request)
    result = backend.report_file_change(request.task_id, "other/secret.py")
    assert result is not None
    assert result.status is TaskState.FAILED
    assert result.error is not None
    assert result.error.code == "construction.path.outside_allowed_scope"


def test_zero_budget_times_out_before_work() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request(
        budget={
            "maxDurationMs": 0,
            "maxIterations": 0,
            "maxToolCalls": 0,
            "maxCostMicrounits": 0,
        }
    )
    handle = backend.start(request)
    assert handle.state is TaskState.TIMED_OUT
    assert backend.result(ResultRequest(task_id=request.task_id)).status is TaskState.TIMED_OUT


def test_insufficient_cost_budget_times_out_before_work() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request(
        budget={
            "maxDurationMs": 120_000,
            "maxIterations": 3,
            "maxToolCalls": 20,
            "maxCostMicrounits": 99,
        }
    )
    assert backend.start(request).state is TaskState.TIMED_OUT


def test_resume_rejects_cursor_ahead_of_task() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    backend.start(request)
    with pytest.raises(ValueError, match="cursor.ahead"):
        backend.resume(ResumeRequest(task_id=request.task_id, acknowledged_sequence=999))


def test_event_batch_rejects_out_of_order_events() -> None:
    later = ConstructionEvent(
        event_id="event-2",
        task_id="task-1",
        sequence=2,
        event_type=ConstructionEventType.PROGRESS,
        state=TaskState.RUNNING,
        summary="Second event.",
    )
    earlier = later.model_copy(update={"event_id": "event-1", "sequence": 1})
    with pytest.raises(ValueError, match="strictly increasing"):
        ConstructionEventBatch(
            task_id="task-1",
            after_sequence=0,
            events=(later, earlier),
            next_sequence=1,
            terminal=False,
        )


def test_event_batch_rejects_duplicate_sequence() -> None:
    first = ConstructionEvent(
        event_id="event-1",
        task_id="task-1",
        sequence=1,
        event_type=ConstructionEventType.PROGRESS,
        state=TaskState.RUNNING,
        summary="First event.",
    )
    duplicate = first.model_copy(update={"event_id": "event-2"})
    with pytest.raises(ValueError, match="strictly increasing"):
        ConstructionEventBatch(
            task_id="task-1",
            after_sequence=0,
            events=(first, duplicate),
            next_sequence=1,
            terminal=False,
        )


def test_event_batch_rejects_multiple_terminal_events() -> None:
    failed = ConstructionEvent(
        event_id="event-1",
        task_id="task-1",
        sequence=1,
        event_type=ConstructionEventType.FAILED,
        state=TaskState.FAILED,
        summary="Failed.",
    )
    cancelled = ConstructionEvent(
        event_id="event-2",
        task_id="task-1",
        sequence=2,
        event_type=ConstructionEventType.CANCELLED,
        state=TaskState.CANCELLED,
        summary="Cancelled.",
    )
    with pytest.raises(ValueError, match="at most one terminal"):
        ConstructionEventBatch(
            task_id="task-1",
            after_sequence=0,
            events=(failed, cancelled),
            next_sequence=2,
            terminal=True,
        )


def test_event_type_requires_matching_state() -> None:
    with pytest.raises(ValueError, match="event type and state"):
        ConstructionEvent(
            event_id="event-1",
            task_id="task-1",
            sequence=1,
            event_type=ConstructionEventType.PROGRESS,
            state=TaskState.WAITING_APPROVAL,
            summary="Invalid state.",
        )


def test_public_batch_verifier_rejects_outside_change() -> None:
    request = task_request()
    event = ConstructionEvent(
        event_id="event-1",
        task_id=request.task_id,
        sequence=1,
        event_type=ConstructionEventType.FILE_CHANGE,
        state=TaskState.RUNNING,
        summary="Changed outside scope.",
        changed_paths=("other/file.py",),
    )
    batch = ConstructionEventBatch(
        task_id=request.task_id,
        after_sequence=0,
        events=(event,),
        next_sequence=1,
        terminal=False,
    )
    with pytest.raises(ValueError, match="outside_allowed_scope"):
        verify_event_batch(request, batch)


def test_cancel_is_idempotent_and_terminal_is_unique() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    backend.start(request)
    cancel = CancelRequest(task_id=request.task_id, reason="The user stopped this iteration.")
    first = backend.cancel(cancel)
    events = backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0)).events
    second = backend.cancel(cancel)
    assert second == first
    assert len(backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0)).events) == len(events)


def test_public_events_contain_relative_paths_and_no_private_reasoning() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    backend.start(request)
    payload = json.dumps(
        backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0)).to_wire(),
        ensure_ascii=False,
    ).lower()
    assert "chain_of_thought" not in payload
    assert "systemprompt" not in payload
    assert "c:\\" not in payload


def test_result_is_unavailable_before_terminal_state() -> None:
    backend = FakeConstructionAgentBackend()
    request = task_request()
    backend.start(request)
    with pytest.raises(RuntimeError, match="not_terminal"):
        backend.result(ResultRequest(task_id=request.task_id))
