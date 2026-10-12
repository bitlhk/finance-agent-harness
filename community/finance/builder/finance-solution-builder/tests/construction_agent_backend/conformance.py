"""Reusable successful-flow conformance for Construction Agent Backends."""

from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    ConstructionAgentBackend,
    ConstructionEventType,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
    verify_construction_result,
    verify_event_batch,
    verify_task_handle,
)

from tests.construction_agent_backend.support import task_request


def run_successful_construction(backend: ConstructionAgentBackend) -> None:
    request = task_request()
    handle = backend.start(request)
    verify_task_handle(request, handle)
    assert handle.state is TaskState.WAITING_APPROVAL

    first = backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0))
    verify_event_batch(request, first)
    assert tuple(event.event_type for event in first.events) == (
        ConstructionEventType.STARTED,
        ConstructionEventType.PROGRESS,
        ConstructionEventType.FILE_CHANGE,
        ConstructionEventType.TOOL_RESULT,
        ConstructionEventType.APPROVAL_REQUIRED,
    )
    assert [event.sequence for event in first.events] == list(range(1, 6))

    resumed = backend.resume(
        ResumeRequest(task_id=request.task_id, acknowledged_sequence=first.next_sequence)
    )
    assert resumed.last_sequence == first.next_sequence
    approval_id = resumed.pending_approval_id
    assert approval_id is not None

    completed = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=approval_id,
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert completed.state is TaskState.SUCCEEDED
    tail = backend.events(
        EventBatchRequest(task_id=request.task_id, after_sequence=first.next_sequence)
    )
    assert tail.terminal is True
    assert tail.events[-1].event_type is ConstructionEventType.COMPLETED
    result = backend.result(ResultRequest(task_id=request.task_id))
    verify_construction_result(request, result)
    assert result.status is TaskState.SUCCEEDED
