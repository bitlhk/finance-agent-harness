"""Explicit live smoke for the environment-provided Codex App Server."""

from __future__ import annotations

import os
import time

import pytest

from finance_builder_base import ResourceKind
from finance_solution_builder.construction_agent_backend import (
    ApprovalChoice,
    ApprovalDecision,
    ApprovalKind,
    ConstructionBudget,
    ConstructionResourceRef,
    ConstructionTaskRequest,
    EventBatchRequest,
    ResultRequest,
    TaskState,
    ValidationCommand,
    WorkspaceBoundary,
)
from finance_solution_builder.construction_agent_backend.codex import (
    CodexAdapterConfig,
    CodexConstructionAgentBackend,
)

from tests.construction_agent_backend.support import DIGEST


pytestmark = pytest.mark.skipif(
    os.environ.get("FINANCE_BUILDER_RUN_CODEX_LIVE") != "1",
    reason="Set FINANCE_BUILDER_RUN_CODEX_LIVE=1 for the approved live smoke.",
)


def test_codex_live_modifies_validates_and_stops_sanitized_candidate(tmp_path) -> None:
    executable = os.environ.get("FINANCE_BUILDER_CODEX_EXECUTABLE")
    input_rate = os.environ.get("FINANCE_BUILDER_CODEX_INPUT_RATE")
    output_rate = os.environ.get("FINANCE_BUILDER_CODEX_OUTPUT_RATE")
    if not executable or input_rate is None or output_rate is None:
        pytest.skip("Codex executable and both deployment accounting rates are required.")
    workspace = tmp_path / "runs/live"
    (workspace / "candidate/src").mkdir(parents=True)
    (workspace / "findings").mkdir()
    (workspace / "candidate/src/generated.py").write_text(
        "def answer():\n    raise NotImplementedError\n",
        encoding="utf-8",
    )
    (workspace / "findings/sanitized.json").write_text(
        '{"finding":"answer is not implemented","expected":"answer returns 42"}\n',
        encoding="utf-8",
    )
    request = ConstructionTaskRequest(
        task_id="codex-live",
        idempotency_key="codex-live-1",
        candidate_id="sanitized-candidate",
        parent_lock_digest=DIGEST,
        goal=(
            "Read findings/sanitized.json, then replace the body of answer() in "
            "candidate/src/generated.py so it returns integer 42. No other "
            "investigation is required."
        ),
        finding_refs=(
            ConstructionResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator="findings/sanitized.json",
                sha256=DIGEST,
            ),
        ),
        workspace_boundary=WorkspaceBoundary(
            workspace=ConstructionResourceRef(
                kind=ResourceKind.WORKSPACE_PATH, locator="runs/live"
            ),
            allowed_write_paths=("candidate/src",),
        ),
        validation_commands=(
            ValidationCommand(
                command_id="compile",
                argv=("python", "-m", "py_compile", "candidate/src/generated.py"),
                cwd=".",
                timeout_ms=30_000,
            ),
        ),
        budget=ConstructionBudget(
            max_duration_ms=180_000,
            max_iterations=3,
            max_tool_calls=20,
            max_cost_microunits=2_000_000,
        ),
        approval_kinds=(
            ApprovalKind.EXTERNAL_SIDE_EFFECT,
            ApprovalKind.SCOPE_EXPANSION,
        ),
    )
    backend = CodexConstructionAgentBackend(
        CodexAdapterConfig(
            workspace_root=tmp_path,
            executable=executable,
            model=os.environ.get("FINANCE_BUILDER_CODEX_MODEL") or None,
            request_timeout_seconds=180,
            input_cost_microunits_per_million_tokens=int(input_rate),
            output_cost_microunits_per_million_tokens=int(output_rate),
        )
    )
    backend.start(request)
    cursor = 0
    deadline = time.monotonic() + 190
    while time.monotonic() < deadline:
        batch = backend.events(
            EventBatchRequest(task_id=request.task_id, after_sequence=cursor)
        )
        cursor = batch.next_sequence
        for event in batch.events:
            if event.approval_request is not None:
                backend.approve(
                    ApprovalDecision(
                        task_id=request.task_id,
                        approval_id=event.approval_request.approval_id,
                        decision=ApprovalChoice.APPROVE,
                    )
                )
        if batch.terminal:
            break
        time.sleep(0.05)
    result = backend.result(ResultRequest(task_id=request.task_id))
    assert result.status is TaskState.SUCCEEDED
    assert result.changed_paths == ("candidate/src/generated.py",)
    assert (workspace / "candidate/src/generated.py").is_file()
