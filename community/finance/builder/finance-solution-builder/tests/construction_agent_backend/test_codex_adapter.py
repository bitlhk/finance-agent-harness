from __future__ import annotations

from types import SimpleNamespace
import threading
import time

import pytest

from finance_builder_base import ErrorDetail
from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    ApprovalKind,
    CancelRequest,
    ConstructionBudget,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
)
from finance_solution_builder.construction_agent_backend.codex import (
    CodexAdapterConfig,
    CodexConstructionAgentBackend,
)

from tests.construction_agent_backend.codex_protocol_double import CodexProtocolDouble
from tests.construction_agent_backend.support import task_request


def _workspace(tmp_path):
    root = tmp_path / "workspaces"
    workspace = root / "runs/task-1"
    (workspace / "candidate/src").mkdir(parents=True)
    (workspace / "candidate/tests").mkdir(parents=True)
    (workspace / "candidate/src/main.py").write_text("value = 1\n", encoding="utf-8")
    return root, workspace


def _backend(tmp_path, **runtime_values):
    root, workspace = _workspace(tmp_path)
    created = []

    def factory(config):
        runtime = CodexProtocolDouble(config, **runtime_values)
        created.append(runtime)
        return runtime

    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=root,
            model="gpt-test",
            input_cost_microunits_per_million_tokens=1_000_000,
            output_cost_microunits_per_million_tokens=1_000_000,
        ),
        runtime_factory=factory,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=2),
        background_runner=lambda target: target(),
    )
    return backend, created, workspace


def _approve(backend, handle):
    return backend.approve(
        ApprovalDecision(
            task_id=handle.task_id,
            approval_id=handle.pending_approval_id or "missing",
            decision=ApprovalChoice.APPROVE,
        )
    )


def test_codex_completes_public_lifecycle_with_one_shot_approval(tmp_path) -> None:
    backend, runtimes, _ = _backend(tmp_path)
    request = task_request()
    waiting = backend.start(request)
    assert waiting.state is TaskState.WAITING_APPROVAL
    completed = _approve(backend, waiting)
    assert completed.state is TaskState.SUCCEEDED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.changed_paths == ("candidate/src/generated.py",)
    assert result.budget_usage.cost_microunits == 15
    assert runtimes[0].replies == [(41, True)]
    assert runtimes[0].stopped == 1
    public_text = backend.events(
        EventBatchRequest(task_id=request.task_id, after_sequence=0)
    ).model_dump_json()
    assert "thread-1" not in public_text
    assert "turn-1" not in public_text
    assert "gpt-test" not in public_text


def test_start_is_idempotent_and_conflicting_key_is_rejected(tmp_path) -> None:
    backend, _, _ = _backend(tmp_path)
    request = task_request()
    first = backend.start(request)
    assert backend.start(request) == first
    with pytest.raises(ValueError, match="idempotency.conflict"):
        backend.start(request.model_copy(update={"candidate_id": "candidate-2"}))


def test_startup_failure_is_public_safe_and_does_not_mutate_workspace(tmp_path) -> None:
    backend, runtimes, workspace = _backend(tmp_path, fail_start=True)
    before = (workspace / "candidate/src/main.py").read_text(encoding="utf-8")
    handle = backend.start(task_request())
    assert handle.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=handle.task_id))
    assert result.error == ErrorDetail(
        code="construction.backend.startup_failed",
        message="The Codex construction service could not start safely.",
    )
    assert (workspace / "candidate/src/main.py").read_text(encoding="utf-8") == before
    assert runtimes[0].stopped == 1


def test_unapproved_scope_expansion_fails_closed(tmp_path) -> None:
    grant_root = tmp_path / "workspaces/runs/task-1/candidate/src"
    backend, runtimes, _ = _backend(
        tmp_path,
        approval_method="item/fileChange/requestApproval",
        approval_params={"grantRoot": str(grant_root)},
    )
    handle = backend.start(task_request())
    assert handle.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=handle.task_id))
    assert result.error is not None
    assert result.error.code == "construction.approval.kind_not_allowed"
    assert runtimes[0].replies == [(41, False)]


@pytest.mark.parametrize(
    "approval_params",
    ({}, {"grantRoot": ""}, {"grantRoot": {"type": "path"}}),
    ids=("missing", "empty", "unknown-shape"),
)
def test_scope_expansion_without_valid_grant_root_fails_closed(
    tmp_path, approval_params
) -> None:
    backend, runtimes, _ = _backend(
        tmp_path,
        approval_method="item/fileChange/requestApproval",
        approval_params=approval_params,
    )
    request = task_request(
        approval_kinds=(ApprovalKind.EXTERNAL_SIDE_EFFECT, ApprovalKind.SCOPE_EXPANSION)
    )
    failed = backend.start(request)
    assert failed.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.approval.scope_invalid"
    assert runtimes[0].replies == [(41, False)]


@pytest.mark.parametrize(
    "approval_params",
    (
        {"permissions": {}},
        {"permissions": {"fileSystem": {}}},
        {"permissions": {"fileSystem": {"write": []}}},
    ),
    ids=("missing-file-system", "missing-write-paths", "empty-write-paths"),
)
def test_permission_scope_expansion_without_paths_fails_closed(
    tmp_path, approval_params
) -> None:
    backend, runtimes, _ = _backend(
        tmp_path,
        approval_method="item/permissions/requestApproval",
        approval_params=approval_params,
    )
    request = task_request(
        approval_kinds=(ApprovalKind.EXTERNAL_SIDE_EFFECT, ApprovalKind.SCOPE_EXPANSION)
    )
    failed = backend.start(request)
    assert failed.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.approval.scope_invalid"
    assert runtimes[0].replies == [(41, False)]


def test_network_permission_without_file_paths_remains_approvable(tmp_path) -> None:
    backend, runtimes, _ = _backend(
        tmp_path,
        approval_method="item/permissions/requestApproval",
        approval_params={"permissions": {"network": {"enabled": True}}},
    )
    waiting = backend.start(task_request())
    assert waiting.state is TaskState.WAITING_APPROVAL
    assert _approve(backend, waiting).state is TaskState.SUCCEEDED
    assert runtimes[0].replies == [(41, True)]


def test_file_approval_exposes_only_workspace_relative_requested_path(tmp_path) -> None:
    root, workspace = _workspace(tmp_path)
    created = []

    def factory(config):
        runtime = CodexProtocolDouble(
            config,
            approval_method="item/fileChange/requestApproval",
            approval_params={"grantRoot": str(workspace / "candidate/src")},
        )
        created.append(runtime)
        return runtime

    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=root,
            input_cost_microunits_per_million_tokens=0,
            output_cost_microunits_per_million_tokens=0,
        ),
        runtime_factory=factory,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
        background_runner=lambda target: target(),
    )
    request = task_request(
        approval_kinds=(ApprovalKind.EXTERNAL_SIDE_EFFECT, ApprovalKind.SCOPE_EXPANSION)
    )
    waiting = backend.start(request)
    events = backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0))
    approval = events.events[-1].approval_request
    assert approval is not None
    assert approval.requested_paths == ("candidate/src",)
    assert str(workspace) not in approval.model_dump_json()
    assert _approve(backend, waiting).state is TaskState.SUCCEEDED
    assert created[0].replies == [(41, True)]


def test_file_approval_outside_allowed_scope_is_rejected_before_side_effect(tmp_path) -> None:
    root, workspace = _workspace(tmp_path)
    created = []

    def factory(config):
        runtime = CodexProtocolDouble(
            config,
            approval_method="item/fileChange/requestApproval",
            approval_params={"grantRoot": str(workspace / "outside")},
        )
        created.append(runtime)
        return runtime

    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=root,
            input_cost_microunits_per_million_tokens=0,
            output_cost_microunits_per_million_tokens=0,
        ),
        runtime_factory=factory,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
        background_runner=lambda target: target(),
    )
    request = task_request(
        approval_kinds=(ApprovalKind.EXTERNAL_SIDE_EFFECT, ApprovalKind.SCOPE_EXPANSION)
    )
    failed = backend.start(request)
    assert failed.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.approval.scope_invalid"
    assert created[0].replies == [(41, False)]


def test_actual_workspace_diff_remains_authoritative(tmp_path) -> None:
    backend, _, _ = _backend(tmp_path, diff_path="candidate/src/other.py")
    waiting = backend.start(task_request())
    assert _approve(backend, waiting).state is TaskState.SUCCEEDED
    result = backend.result(ResultRequest(task_id=waiting.task_id))
    assert result.changed_paths == ("candidate/src/generated.py",)


def test_missing_pricing_fails_budget_evidence_closed(tmp_path) -> None:
    root, _ = _workspace(tmp_path)
    created = []

    def factory(config):
        runtime = CodexProtocolDouble(config, approval_method=None)
        created.append(runtime)
        return runtime

    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(workspace_root=root),
        runtime_factory=factory,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
        background_runner=lambda target: target(),
    )
    handle = backend.start(task_request())
    assert handle.state is TaskState.FAILED
    assert backend.result(ResultRequest(task_id=handle.task_id)).error.code == (
        "construction.budget.usage_unknown"
    )


def test_builder_validation_failure_blocks_success(tmp_path) -> None:
    root, _ = _workspace(tmp_path)
    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=root,
            input_cost_microunits_per_million_tokens=0,
            output_cost_microunits_per_million_tokens=0,
        ),
        runtime_factory=lambda config: CodexProtocolDouble(config, approval_method=None),
        command_runner=lambda *_: SimpleNamespace(returncode=1, duration_ms=2),
        background_runner=lambda target: target(),
    )
    handle = backend.start(task_request())
    result = backend.result(ResultRequest(task_id=handle.task_id))
    assert result.status is TaskState.FAILED
    assert result.error.code == "construction.validation.failed"


def test_non_retryable_codex_error_fails_with_stable_public_error(tmp_path) -> None:
    backend, _, _ = _backend(tmp_path, approval_method=None, terminal_error=True)
    handle = backend.start(task_request())
    assert handle.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=handle.task_id))
    assert result.error == ErrorDetail(
        code="construction.backend.turn_failed",
        message="Codex could not complete the construction turn.",
    )
    assert "private provider error" not in result.model_dump_json()


def test_active_resume_fails_closed_and_interrupts_turn(tmp_path) -> None:
    backend, runtimes, _ = _backend(tmp_path)
    handle = backend.start(task_request())
    resumed = backend.resume(
        ResumeRequest(task_id=handle.task_id, acknowledged_sequence=handle.last_sequence)
    )
    assert resumed.state is TaskState.FAILED
    assert runtimes[0].interrupted == [("thread-1", "turn-1")]


def test_cancel_is_idempotent_and_stops_owned_process(tmp_path) -> None:
    backend, runtimes, _ = _backend(tmp_path)
    handle = backend.start(task_request())
    request = CancelRequest(task_id=handle.task_id, reason="Owner stopped the revision.")
    first = backend.cancel(request)
    second = backend.cancel(request)
    assert first == second
    assert first.status is TaskState.CANCELLED
    assert runtimes[0].interrupted == [("thread-1", "turn-1")]
    assert runtimes[0].stopped == 1


def test_approval_before_old_event_pump_exits_starts_next_pump(tmp_path) -> None:
    root, _ = _workspace(tmp_path)
    gate = threading.Event()
    runtimes = []

    def factory(config):
        runtime = CodexProtocolDouble(config, approval_return_gate=gate)
        runtimes.append(runtime)
        return runtime

    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=root,
            input_cost_microunits_per_million_tokens=0,
            output_cost_microunits_per_million_tokens=0,
        ),
        runtime_factory=factory,
        command_runner=lambda *_: SimpleNamespace(returncode=0, duration_ms=1),
    )
    backend.start(task_request())
    deadline = time.monotonic() + 2
    approval_id = None
    while time.monotonic() < deadline and approval_id is None:
        events = backend.events(EventBatchRequest(task_id="task-1", after_sequence=0))
        approval_id = next(
            (
                event.approval_request.approval_id
                for event in events.events
                if event.approval_request is not None
            ),
            None,
        )
        time.sleep(0.01)
    assert approval_id is not None
    backend.approve(
        ApprovalDecision(
            task_id="task-1",
            approval_id=approval_id,
            decision=ApprovalChoice.APPROVE,
        )
    )
    gate.set()
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        try:
            result = backend.result(ResultRequest(task_id="task-1"))
        except RuntimeError:
            time.sleep(0.01)
            continue
        assert result.status is TaskState.SUCCEEDED
        break
    else:
        pytest.fail("Codex follow-up pump did not complete")
    assert runtimes[0].stream_count == 2


def test_zero_budget_never_starts_codex(tmp_path) -> None:
    backend, runtimes, _ = _backend(tmp_path)
    request = task_request(
        budget=ConstructionBudget(
            max_duration_ms=1,
            max_iterations=0,
            max_tool_calls=1,
            max_cost_microunits=1,
        )
    )
    handle = backend.start(request)
    assert handle.state is TaskState.TIMED_OUT
    assert runtimes[0].started is False
