from __future__ import annotations

from datetime import datetime, timezone

import pytest

from finance_builder_base.contracts import ErrorDetail, ResourceKind, ResourceRef
from finance_builder_base.solution_build import (
    BuildCheckResult,
    BuildDefinition,
    BuildFailure,
    CheckStatus,
    FailureCategory,
    ResolvedBuildAsset,
    ResponsibilityBoundary,
    SolutionBuildStatus,
    StageExecutionRecord,
    StageStatus,
    build_asset_lock,
    build_report,
)


def _definition() -> BuildDefinition:
    return BuildDefinition(task_id="build-1", input_digest="1" * 64)


def _asset(asset_id: str, *, dependencies: tuple[str, ...] = ()) -> ResolvedBuildAsset:
    return ResolvedBuildAsset(
        asset_id=asset_id,
        asset_type="domain-pack",
        version="1.0.0",
        revision="rev-1",
        sha256=("a" if asset_id == "asset-a" else "b") * 64,
        dependencies=dependencies,
        resource=ResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator=f"assets/{asset_id}",
        ),
    )


def _record(status: StageStatus, check_status: CheckStatus) -> StageExecutionRecord:
    now = datetime.now(timezone.utc)
    failure = None
    if status in {StageStatus.FAILED, StageStatus.BLOCKED}:
        failure = BuildFailure(
            category=FailureCategory.VERIFICATION,
            stage_id="candidate-validation",
            responsibility=ResponsibilityBoundary.VERIFICATION,
            error=ErrorDetail(
                code="solution.build.check_failed",
                message="validation did not pass",
            ),
            next_action="review the validation evidence",
        )
    return StageExecutionRecord(
        stage_id="candidate-validation",
        status=status,
        summary="validation recorded",
        checks=(
            BuildCheckResult(
                check_id="smoke",
                status=check_status,
                summary="check recorded",
            ),
        ),
        failure=failure,
        started_at=now,
        finished_at=now,
        duration_ms=0,
    )


def test_asset_lock_is_deterministic_and_normalizes_dependencies() -> None:
    definition = _definition()
    first = build_asset_lock(
        definition,
        [_asset("asset-b", dependencies=("asset-a",)), _asset("asset-a")],
    )
    second = build_asset_lock(
        definition,
        [_asset("asset-a"), _asset("asset-b", dependencies=("asset-a",))],
    )

    assert first == second
    assert [asset.asset_id for asset in first.assets] == ["asset-a", "asset-b"]


def test_asset_lock_rejects_missing_dependency_or_duplicate_identity() -> None:
    with pytest.raises(ValueError, match="absent"):
        build_asset_lock(_definition(), [_asset("asset-b", dependencies=("asset-a",))])
    with pytest.raises(ValueError, match="unique"):
        build_asset_lock(_definition(), [_asset("asset-a"), _asset("asset-a")])


def test_report_preserves_skipped_check_without_counting_it_as_failure() -> None:
    lock = build_asset_lock(_definition(), [])
    report = build_report(
        _definition(),
        lock,
        SolutionBuildStatus.COMPLETED,
        [_record(StageStatus.SKIPPED, CheckStatus.SKIPPED)],
    )

    assert report.stages[0].checks[0].status is CheckStatus.SKIPPED
    assert report.failures == ()


def test_completed_report_rejects_failed_or_blocked_stage() -> None:
    lock = build_asset_lock(_definition(), [])
    with pytest.raises(ValueError, match="completed report"):
        build_report(
            _definition(),
            lock,
            SolutionBuildStatus.COMPLETED,
            [_record(StageStatus.BLOCKED, CheckStatus.BLOCKED)],
        )
