from __future__ import annotations

from datetime import datetime, timezone

import pytest
from pydantic import ValidationError

from finance_builder_base.contracts import ResourceKind, ResourceRef
from finance_builder_base.solution_build import (
    BuildCheckResult,
    BuildCheckpoint,
    BuildDefinition,
    BuildFailure,
    BuildPlan,
    BuildStageSpec,
    CheckStatus,
    FailureCategory,
    ReplayPolicy,
    ResponsibilityBoundary,
    ResolvedBuildAsset,
    SolutionAssetLock,
    SolutionBuildReport,
    SolutionBuildResult,
    SolutionBuildStatus,
    BuildEvent,
    EventType,
    StageExecutionRecord,
    StageExecutionResult,
    StageStatus,
    StandardBuildStage,
    standard_build_plan,
)


DIGEST = "a" * 64


def test_standard_plan_contains_every_stage_in_semantic_order() -> None:
    plan = standard_build_plan(skipped={StandardBuildStage.CANDIDATE_START})

    assert [stage.standard_stage for stage in plan.stages] == list(StandardBuildStage)
    assert plan.stages[5].applicable is False


def test_plan_accepts_namespaced_extension_after_declared_anchor() -> None:
    stages = list(standard_build_plan().stages)
    stages.insert(
        3,
        BuildStageSpec(
            stage_id="example.prepare",
            after_standard_stage=StandardBuildStage.ASSET_RESOLUTION,
        ),
    )

    assert BuildPlan(stages=tuple(stages)).stages[3].stage_id == "example.prepare"


def test_plan_rejects_missing_or_reordered_standard_stage() -> None:
    stages = list(standard_build_plan().stages)
    with pytest.raises(ValidationError, match="every standard stage"):
        BuildPlan(stages=tuple(stages[:-1]))

    stages[0], stages[1] = stages[1], stages[0]
    with pytest.raises(ValidationError, match="semantic order"):
        BuildPlan(stages=tuple(stages))


def test_external_replay_requires_stable_idempotency_key() -> None:
    with pytest.raises(ValidationError, match="idempotency_key"):
        BuildStageSpec(
            stage_id=StandardBuildStage.CANDIDATE_START.value,
            standard_stage=StandardBuildStage.CANDIDATE_START,
            replay_policy=ReplayPolicy.IDEMPOTENT_EXTERNAL,
        )


def test_runner_result_rejects_non_terminal_or_unexplained_failure() -> None:
    with pytest.raises(ValidationError, match="terminal"):
        StageExecutionResult(status=StageStatus.RUNNING, summary="still running")
    with pytest.raises(ValidationError, match="requires exactly one failure"):
        StageExecutionResult(status=StageStatus.FAILED, summary="failed")


def test_stage_cannot_hide_failed_check_behind_passed_status() -> None:
    with pytest.raises(ValidationError, match="passed stage"):
        StageExecutionResult(
            status=StageStatus.PASSED,
            summary="stage passed",
            checks=(
                BuildCheckResult(
                    check_id="compile",
                    status=CheckStatus.FAILED,
                    summary="compile failed",
                ),
            ),
        )


def test_stage_record_rejects_failure_owned_by_another_stage() -> None:
    now = datetime.now(timezone.utc)
    failure = BuildFailure(
        category=FailureCategory.BACKEND,
        stage_id="candidate-build",
        responsibility=ResponsibilityBoundary.BACKEND,
        error={"code": "solution.build.failed", "message": "build failed"},
        next_action="inspect build evidence",
    )
    with pytest.raises(ValidationError, match="execution record stage"):
        StageExecutionRecord(
            stage_id="candidate-start",
            status=StageStatus.FAILED,
            summary="start failed",
            failure=failure,
            started_at=now,
            finished_at=now,
            duration_ms=0,
        )


def test_definition_rejects_unknown_extension_shape() -> None:
    with pytest.raises(ValidationError):
        BuildDefinition(task_id="build-1", input_digest=DIGEST, unknown="value")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("version", "api_key=plain-secret"),
        ("revision", "C:\\private\\candidate"),
    ],
)
def test_asset_rejects_unsafe_public_metadata(field: str, value: str) -> None:
    values = {
        "asset_id": "asset-a",
        "asset_type": "domain-pack",
        "version": "1.0.0",
        "revision": "revision-1",
        "sha256": "b" * 64,
    }
    values[field] = value

    with pytest.raises(ValidationError):
        ResolvedBuildAsset(**values)


def test_asset_rejects_credential_in_public_resource_reference() -> None:
    with pytest.raises(ValidationError):
        ResolvedBuildAsset(
            asset_id="asset-a",
            asset_type="domain-pack",
            version="1.0.0",
            sha256="b" * 64,
            resource=ResourceRef(
                kind=ResourceKind.HTTP,
                locator="https://example.test/asset?api_key=plain-secret",
            ),
        )


@pytest.mark.parametrize("mismatch", ["task", "input", "lock", "terminal"])
def test_result_rejects_unlinked_definition_lock_or_report(mismatch: str) -> None:
    definition = BuildDefinition(task_id="build-1", input_digest=DIGEST)
    lock = SolutionAssetLock(
        task_id="build-1",
        input_digest=DIGEST,
        assets=(),
        lock_digest="b" * 64,
    )
    report = SolutionBuildReport(
        task_id="build-1",
        input_digest=DIGEST,
        lock_digest=lock.lock_digest,
        status=SolutionBuildStatus.COMPLETED,
        stages=(),
    )
    if mismatch == "task":
        lock = lock.model_copy(update={"task_id": "other-build"})
    elif mismatch == "input":
        report = report.model_copy(update={"input_digest": "c" * 64})
    else:
        report = report.model_copy(update={"lock_digest": "d" * 64})

    terminal_status = (
        SolutionBuildStatus.FAILED
        if mismatch == "terminal"
        else SolutionBuildStatus.COMPLETED
    )
    terminal = BuildEvent(
        task_id=definition.task_id,
        sequence=1,
        event_id="build-1:1",
        event_type=EventType.TASK_STATUS,
        summary="solution build completed",
        task_status=terminal_status,
    )
    with pytest.raises(
        ValidationError,
        match="result artifacts|attached asset lock|terminal event",
    ):
        SolutionBuildResult(
            definition=definition,
            status=SolutionBuildStatus.COMPLETED,
            events=(terminal,),
            asset_lock=lock,
            report=report,
        )


def test_completed_report_rejects_failed_stage() -> None:
    now = datetime.now(timezone.utc)
    failure = BuildFailure(
        category=FailureCategory.BACKEND,
        stage_id="candidate-build",
        responsibility=ResponsibilityBoundary.BACKEND,
        error={"code": "solution.build.failed", "message": "build failed"},
        next_action="inspect build evidence",
    )
    failed_stage = StageExecutionRecord(
        stage_id="candidate-build",
        status=StageStatus.FAILED,
        summary="build failed",
        failure=failure,
        started_at=now,
        finished_at=now,
        duration_ms=0,
    )

    with pytest.raises(ValidationError, match="completed report"):
        SolutionBuildReport(
            task_id="build-1",
            input_digest=DIGEST,
            lock_digest="b" * 64,
            status=SolutionBuildStatus.COMPLETED,
            stages=(failed_stage,),
            failures=(failure,),
        )


def test_checkpoint_rejects_failed_stage_or_terminal_event() -> None:
    now = datetime.now(timezone.utc)
    failure = BuildFailure(
        category=FailureCategory.BACKEND,
        stage_id="candidate-build",
        responsibility=ResponsibilityBoundary.BACKEND,
        error={"code": "solution.build.failed", "message": "build failed"},
        next_action="inspect build evidence",
    )
    failed_stage = StageExecutionRecord(
        stage_id="candidate-build",
        status=StageStatus.FAILED,
        summary="build failed",
        failure=failure,
        started_at=now,
        finished_at=now,
        duration_ms=0,
    )
    terminal = BuildEvent(
        task_id="build-1",
        sequence=1,
        event_id="build-1:1",
        event_type=EventType.TASK_STATUS,
        summary="solution build completed",
        task_status=SolutionBuildStatus.COMPLETED,
    )

    with pytest.raises(ValidationError, match="safely completed"):
        BuildCheckpoint(
            task_id="build-1",
            input_digest=DIGEST,
            completed_stages=(failed_stage,),
        )
    with pytest.raises(ValidationError, match="terminal task event"):
        BuildCheckpoint(
            task_id="build-1",
            input_digest=DIGEST,
            events=(terminal,),
        )
