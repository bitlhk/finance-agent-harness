"""Contract and boundary tests for the private OpenCode adapter."""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

from finance_builder_base.solution_build import CheckStatus
from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    CancelRequest,
    ConstructionBudget,
    ConstructionAgentBackend,
    ConstructionEventType,
    EventBatchRequest,
    ResultRequest,
    ResumeRequest,
    TaskState,
    ValidationCommand,
    WorkspaceBoundary,
    verify_construction_result,
    verify_event_batch,
    verify_task_handle,
)
from finance_solution_builder.construction_agent_backend.opencode import (
    OPENCODE_VERSION,
    OpenCodeAdapterConfig,
    OpenCodeConstructionAgentBackend,
    _HttpOpenCodeRuntime,
    _path_stays_within_allowed,
)

from tests.construction_agent_backend.opencode_protocol_double import (
    ScriptedOpenCodeRuntime,
    successful_batches,
    vendor_event,
)
from tests.construction_agent_backend.support import resource, task_request


def _inline(target):  # type: ignore[no-untyped-untyped-def]
    target()


def _request(root: Path, **overrides: object):
    workspace = root / "runs" / "task-1"
    workspace.mkdir(parents=True)
    values: dict[str, object] = {
        "workspace_boundary": WorkspaceBoundary(
            workspace=resource("runs/task-1"),
            allowed_write_paths=("candidate/src", "candidate/tests"),
        ),
        "validation_commands": (
            ValidationCommand(
                command_id="unit-tests",
                argv=("python", "-c", "print('ok')"),
                cwd=".",
                timeout_ms=10_000,
            ),
        ),
    }
    values.update(overrides)
    return task_request(**values)


def _backend(
    root: Path,
    runtime: ScriptedOpenCodeRuntime,
    *,
    returncode: int = 0,
    clock=None,  # type: ignore[no-untyped-def]
) -> OpenCodeConstructionAgentBackend:
    return OpenCodeConstructionAgentBackend(
        OpenCodeAdapterConfig(workspace_root=root),
        runtime_factory=lambda config, workspace, allowed: runtime,
        command_runner=lambda argv, cwd, timeout: SimpleNamespace(
            returncode=returncode, duration_ms=4
        ),
        background_runner=_inline,
        **({"clock": clock} if clock else {}),
    )


def test_version_configuration_is_fixed(tmp_path: Path) -> None:
    config = OpenCodeAdapterConfig(workspace_root=tmp_path)
    assert config.expected_version == OPENCODE_VERSION == "1.18.29"
    with pytest.raises(ValueError, match="version_not_supported"):
        OpenCodeAdapterConfig(workspace_root=tmp_path, expected_version="1.18.30")
    with pytest.raises(ValueError, match="model_invalid"):
        OpenCodeAdapterConfig(workspace_root=tmp_path, model="model-without-provider")


def test_managed_server_uses_loopback_auth_and_restricted_inline_config(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    captured: dict[str, object] = {}

    class Process:
        pid = 100

        @staticmethod
        def poll():
            return None

    monkeypatch.setattr(
        "finance_solution_builder.construction_agent_backend.opencode.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout="1.18.29\n"),
    )
    monkeypatch.setattr(
        "finance_solution_builder.construction_agent_backend.opencode.subprocess.Popen",
        lambda argv, **kwargs: captured.update(argv=argv, kwargs=kwargs) or Process(),
    )
    runtime = _HttpOpenCodeRuntime(
        OpenCodeAdapterConfig(workspace_root=tmp_path, model="provider/model"),
        tmp_path,
        ("candidate/src",),
    )
    monkeypatch.setattr(runtime, "health", lambda: {"healthy": True, "version": "1.18.29"})
    runtime.start()

    argv = captured["argv"]
    kwargs = captured["kwargs"]
    assert isinstance(argv, tuple) and argv[:3] == ("opencode", "--pure", "serve")
    assert argv[argv.index("--hostname") + 1] == "127.0.0.1"
    assert isinstance(kwargs, dict)
    environment = kwargs["env"]
    assert isinstance(environment, dict) and environment["OPENCODE_SERVER_PASSWORD"]
    inline = json.loads(environment["OPENCODE_CONFIG_CONTENT"])
    assert inline["share"] == "disabled" and inline["autoupdate"] is False
    assert inline["permission"]["external_directory"] == "deny"
    assert inline["permission"]["edit"] == {
        "*": "deny",
        "candidate/src": "allow",
        "candidate/src/**": "allow",
    }
    assert inline["model"] == "provider/model"


def test_managed_server_rejects_wrong_executable_version(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "finance_solution_builder.construction_agent_backend.opencode.subprocess.run",
        lambda *args, **kwargs: SimpleNamespace(returncode=0, stdout="1.18.28\n"),
    )
    monkeypatch.setattr(
        "finance_solution_builder.construction_agent_backend.opencode.subprocess.Popen",
        lambda *args, **kwargs: pytest.fail("server must not start for a wrong version"),
    )
    runtime = _HttpOpenCodeRuntime(OpenCodeAdapterConfig(workspace_root=tmp_path), tmp_path, ("src",))
    with pytest.raises(RuntimeError, match="version_mismatch"):
        runtime.start()


def test_successful_revision_maps_public_events_and_result(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)

    handle = backend.start(request)
    verify_task_handle(request, handle)
    assert handle.state is TaskState.SUCCEEDED
    batch = backend.events(EventBatchRequest(task_id=request.task_id, after_sequence=0))
    verify_event_batch(request, batch)
    assert batch.terminal is True
    assert batch.events[0].event_type is ConstructionEventType.STARTED
    assert batch.events[-1].event_type is ConstructionEventType.COMPLETED
    assert {event.event_type for event in batch.events} >= {
        ConstructionEventType.PROGRESS,
        ConstructionEventType.FILE_CHANGE,
        ConstructionEventType.TOOL_RESULT,
    }
    result = backend.result(ResultRequest(task_id=request.task_id))
    verify_construction_result(request, result)
    assert result.changed_paths == ("candidate/src/generated.py",)
    assert result.validation_results[0].status is CheckStatus.PASSED
    assert runtime.stopped is True
    assert (runtime.workspace / "candidate/src/generated.py").is_file()


def test_actual_workspace_diff_is_used_when_vendor_diff_is_empty(tmp_path: Path) -> None:
    request = _request(tmp_path)

    class EmptyReportedDiff(ScriptedOpenCodeRuntime):
        def diff(self, session_id: str):  # type: ignore[no-untyped-def]
            assert session_id == self.session_id
            return ()

    runtime = EmptyReportedDiff(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)

    assert backend.start(request).state is TaskState.SUCCEEDED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.changed_paths == ("candidate/src/generated.py",)


def test_vendor_diff_must_match_actual_workspace_diff(tmp_path: Path) -> None:
    request = _request(tmp_path)

    class MismatchedReportedDiff(ScriptedOpenCodeRuntime):
        def diff(self, session_id: str):  # type: ignore[no-untyped-def]
            assert session_id == self.session_id
            return ({"file": "candidate/src/reported.py"},)

    runtime = MismatchedReportedDiff(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)

    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.diff.mismatch"

def test_start_is_idempotent_for_the_same_request(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    first = backend.start(request)
    second = backend.start(request)
    assert second == first
    assert len(runtime.prompts) == 1


def test_adapter_satisfies_public_backend_protocol(tmp_path: Path) -> None:
    runtime = ScriptedOpenCodeRuntime(tmp_path)
    backend = _backend(tmp_path, runtime)
    assert isinstance(backend, ConstructionAgentBackend)


def test_same_idempotency_key_rejects_a_different_request(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    backend.start(request)
    with pytest.raises(ValueError, match="idempotency.conflict"):
        backend.start(request.model_copy(update={"goal": "Apply a different accepted finding."}))


@pytest.mark.parametrize(
    ("runtime_options", "error_code"),
    [
        ({"fail_start": True}, "construction.backend.startup_failed"),
        (
            {"health": {"healthy": True, "version": "1.18.28"}},
            "construction.backend.startup_failed",
        ),
    ],
)
def test_startup_failure_is_public_and_cleans_up(
    tmp_path: Path, runtime_options: dict[str, object], error_code: str
) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", **runtime_options  # type: ignore[arg-type]
    )
    backend = _backend(tmp_path, runtime)
    handle = backend.start(request)
    assert handle.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == error_code
    assert runtime.stopped is True


def test_effective_configuration_must_keep_the_restricted_policy(tmp_path: Path) -> None:
    request = _request(tmp_path)

    class UnsafeRuntime(ScriptedOpenCodeRuntime):
        def configuration(self):  # type: ignore[no-untyped-def]
            value = dict(super().configuration())
            value["share"] = "auto"
            return value

    runtime = UnsafeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    assert runtime.prompts == []


def test_resolved_path_must_remain_in_an_allowed_root(tmp_path: Path) -> None:
    workspace = tmp_path / "runs" / "task-1"
    (workspace / "candidate" / "src").mkdir(parents=True)
    outside_allowed = workspace / "candidate" / "private" / "value.py"
    outside_allowed.parent.mkdir(parents=True)

    class RedirectedPath:
        def resolve(self) -> Path:
            return outside_allowed

    assert not _path_stays_within_allowed(
        workspace,
        RedirectedPath(),  # type: ignore[arg-type]
        ("candidate/src", "candidate/tests"),
    )


def test_preexisting_nested_symlink_must_stay_inside_workspace(tmp_path: Path) -> None:
    request = _request(tmp_path)
    workspace = tmp_path / "runs" / "task-1"
    allowed = workspace / "candidate" / "src"
    allowed.mkdir(parents=True)
    external = workspace / "candidate" / "private" / "outside.py"
    external.parent.mkdir(parents=True)
    external.write_text("unchanged\n", encoding="utf-8")
    link = allowed / "linked.py"
    try:
        link.symlink_to(external)
    except OSError:
        pytest.skip("symlink creation is not available on this host")

    runtime = ScriptedOpenCodeRuntime(workspace)
    backend = _backend(tmp_path, runtime)
    with pytest.raises(ValueError, match="workspace.symlink_escape"):
        backend.start(request)
    assert runtime.started is False


def test_created_symlink_outside_workspace_fails_closed(tmp_path: Path) -> None:
    probe = tmp_path / "probe-link"
    probe_target = tmp_path / "probe-target"
    probe_target.write_text("probe\n", encoding="utf-8")
    try:
        probe.symlink_to(probe_target)
        probe.unlink()
    except OSError:
        pytest.skip("symlink creation is not available on this host")

    request = _request(tmp_path)
    workspace = tmp_path / "runs" / "task-1"
    external = tmp_path / "outside.py"
    external.write_text("unchanged\n", encoding="utf-8")

    class SymlinkRuntime(ScriptedOpenCodeRuntime):
        def submit_prompt(
            self, session_id: str, prompt: str, model: str | None
        ) -> None:
            assert session_id == self.session_id
            self.prompts.append(prompt)
            target = self.workspace / "candidate" / "src" / "linked.py"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.symlink_to(external)

    runtime = SymlinkRuntime(
        workspace,
        diff_paths=("candidate/src/linked.py",),
        write_changes=False,
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.path.symlink_escape"
    assert external.read_text(encoding="utf-8") == "unchanged\n"

def test_outside_diff_fails_closed(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", diff_paths=("outside/generated.py",)
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.path.outside_allowed_scope"


def test_validation_failure_is_terminal(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime, returncode=1)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == "construction.validation.failed"
    assert result.validation_results[0].status is CheckStatus.FAILED


def test_unknown_cost_usage_fails_closed(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1",
        batches=[[vendor_event("session.idle")]],
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == "construction.budget.usage_unknown"


def test_missing_iteration_and_tool_usage_fails_closed(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1",
        batches=[[
            vendor_event("message.updated", info={"sessionID": "session-1", "cost": 0.0001}),
            vendor_event("session.idle"),
        ]],
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == "construction.budget.usage_unknown"


def test_approval_maps_to_once_and_then_completes(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", batches=successful_batches(approval=True)
    )
    backend = _backend(tmp_path, runtime)
    waiting = backend.start(request)
    assert waiting.state is TaskState.WAITING_APPROVAL
    approval_id = waiting.pending_approval_id
    assert approval_id is not None
    completed = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=approval_id,
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert completed.state is TaskState.SUCCEEDED
    assert runtime.permission_replies == [("vendor-permission-1", "once")]
    repeated = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=approval_id,
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert repeated.state is TaskState.SUCCEEDED
    assert len(runtime.permission_replies) == 1


def test_approval_keeps_one_continuous_event_stream(tmp_path: Path) -> None:
    request = _request(tmp_path)
    permission_seen = threading.Event()
    approved = threading.Event()

    class ContinuousApprovalRuntime(ScriptedOpenCodeRuntime):
        stream_calls = 0

        def stream_events(self, session_id: str):
            assert session_id == self.session_id
            self.stream_calls += 1
            yield vendor_event(
                "message.updated",
                info={"sessionID": self.session_id, "cost": 0.0001},
            )
            yield vendor_event("message.part.updated", part={"type": "step-start"})
            yield vendor_event(
                "message.part.updated",
                part={"type": "tool", "state": {"status": "completed"}},
            )
            yield vendor_event(
                "permission.asked",
                id="vendor-permission-1",
                permission="bash",
            )
            permission_seen.set()
            if not approved.wait(2):
                raise RuntimeError("approval was not delivered")
            yield vendor_event("session.idle")

        def reply_permission(
            self, session_id: str, permission_id: str, response: str
        ) -> None:
            super().reply_permission(session_id, permission_id, response)
            approved.set()

    runtime = ContinuousApprovalRuntime(tmp_path / "runs" / "task-1")
    backend = OpenCodeConstructionAgentBackend(
        OpenCodeAdapterConfig(workspace_root=tmp_path),
        runtime_factory=lambda config, workspace, allowed: runtime,
        command_runner=lambda argv, cwd, timeout: SimpleNamespace(
            returncode=0, duration_ms=4
        ),
    )
    assert backend.start(request).state is TaskState.RUNNING
    assert permission_seen.wait(2)
    batch = backend.events(
        EventBatchRequest(task_id=request.task_id, after_sequence=0)
    )
    approval = next(
        event.approval_request
        for event in batch.events
        if event.approval_request is not None
    )
    continued = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=approval.approval_id,
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert continued.state is TaskState.RUNNING

    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        try:
            result = backend.result(ResultRequest(task_id=request.task_id))
        except RuntimeError:
            time.sleep(0.01)
            continue
        break
    else:
        pytest.fail("construction task did not reach a terminal result")

    assert result.status is TaskState.SUCCEEDED
    assert runtime.stream_calls == 1
    assert runtime.permission_replies == [("vendor-permission-1", "once")]


def test_event_stream_end_without_terminal_fails_closed(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1",
        batches=[[]],
        write_changes=False,
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.backend.event_stream_failed"

def test_approval_transport_failure_is_public_and_terminal(tmp_path: Path) -> None:
    request = _request(tmp_path)

    class FailingApprovalRuntime(ScriptedOpenCodeRuntime):
        def reply_permission(
            self, session_id: str, permission_id: str, response: str
        ) -> None:
            raise RuntimeError("private vendor token")

    runtime = FailingApprovalRuntime(
        tmp_path / "runs" / "task-1",
        batches=successful_batches(approval=True),
    )
    backend = _backend(tmp_path, runtime)
    waiting = backend.start(request)
    failed = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=waiting.pending_approval_id or "missing",
            decision=ApprovalChoice.APPROVE,
        )
    )
    assert failed.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None
    assert result.error.code == "construction.approval.delivery_failed"
    assert "private vendor token" not in json.dumps(result.to_wire())

def test_rejected_approval_fails_without_continuing(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", batches=successful_batches(approval=True)
    )
    backend = _backend(tmp_path, runtime)
    waiting = backend.start(request)
    rejected = backend.approve(
        ApprovalDecision(
            task_id=request.task_id,
            approval_id=waiting.pending_approval_id or "missing",
            decision=ApprovalChoice.REJECT,
        )
    )
    assert rejected.state is TaskState.FAILED
    assert runtime.permission_replies == [("vendor-permission-1", "reject")]


def test_cancel_is_idempotent_and_aborts_session(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", batches=successful_batches(approval=True)
    )
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.WAITING_APPROVAL
    cancel = CancelRequest(task_id=request.task_id, reason="Stop this revision.")
    first = backend.cancel(cancel)
    second = backend.cancel(cancel)
    assert first == second
    assert first.status is TaskState.CANCELLED
    assert runtime.aborted is True and runtime.stopped is True


def test_cancel_returns_terminal_result_when_abort_transport_fails(
    tmp_path: Path,
) -> None:
    request = _request(tmp_path)

    class FailingAbortRuntime(ScriptedOpenCodeRuntime):
        def abort(self, session_id: str) -> None:
            raise RuntimeError("private abort failure")

    runtime = FailingAbortRuntime(tmp_path / "runs" / "task-1")
    backend = OpenCodeConstructionAgentBackend(
        OpenCodeAdapterConfig(workspace_root=tmp_path),
        runtime_factory=lambda config, workspace, allowed: runtime,
        command_runner=lambda argv, cwd, timeout: SimpleNamespace(
            returncode=0, duration_ms=4
        ),
        background_runner=lambda target: None,
    )
    assert backend.start(request).state is TaskState.RUNNING
    cancel = CancelRequest(task_id=request.task_id, reason="Stop this revision.")
    first = backend.cancel(cancel)
    second = backend.cancel(cancel)
    assert first is second
    assert first.status is TaskState.CANCELLED
    assert runtime.stopped is True

def test_budget_exhaustion_aborts_and_times_out(tmp_path: Path) -> None:
    request = _request(
        tmp_path,
        budget=ConstructionBudget(
            max_duration_ms=120_000,
            max_iterations=0,
            max_tool_calls=20,
            max_cost_microunits=500_000,
        ),
    )
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    assert backend.start(request).state is TaskState.TIMED_OUT
    assert runtime.started is False
    assert runtime.stopped is True


def test_active_resume_fails_closed_without_current_identity(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", batches=successful_batches(approval=True)
    )
    backend = _backend(tmp_path, runtime)
    assert backend.capability.supports_resume is False
    waiting = backend.start(request)
    prompts_before = tuple(runtime.prompts)
    unavailable = backend.resume(
        ResumeRequest(task_id=request.task_id, acknowledged_sequence=waiting.last_sequence)
    )
    assert unavailable.state is TaskState.FAILED
    assert tuple(runtime.prompts) == prompts_before
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == "construction.resume.unavailable"


def test_active_resume_fails_closed_after_workspace_change(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(
        tmp_path / "runs" / "task-1", batches=successful_batches(approval=True)
    )
    backend = _backend(tmp_path, runtime)
    waiting = backend.start(request)
    changed = tmp_path / "runs" / "task-1" / "candidate" / "src" / "external.py"
    changed.parent.mkdir(parents=True, exist_ok=True)
    changed.write_text("# changed outside the construction session\n", encoding="utf-8")
    unavailable = backend.resume(
        ResumeRequest(task_id=request.task_id, acknowledged_sequence=waiting.last_sequence)
    )
    assert unavailable.state is TaskState.FAILED
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.error is not None and result.error.code == "construction.resume.unavailable"


def test_resume_returns_existing_terminal_fact_without_restart(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    completed = backend.start(request)
    assert completed.state is TaskState.SUCCEEDED
    prompts_before = tuple(runtime.prompts)
    resumed = backend.resume(
        ResumeRequest(task_id=request.task_id, acknowledged_sequence=completed.last_sequence)
    )
    assert resumed.state is TaskState.SUCCEEDED
    assert tuple(runtime.prompts) == prompts_before


def test_public_data_does_not_contain_vendor_session_or_prompt(tmp_path: Path) -> None:
    request = _request(tmp_path)
    runtime = ScriptedOpenCodeRuntime(tmp_path / "runs" / "task-1")
    backend = _backend(tmp_path, runtime)
    backend.start(request)
    payload = backend.result(ResultRequest(task_id=request.task_id)).model_dump_json()
    assert "session-1" not in payload
    assert request.goal not in payload
    assert "OPENCODE_SERVER_PASSWORD" not in payload
