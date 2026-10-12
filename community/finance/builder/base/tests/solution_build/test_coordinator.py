from __future__ import annotations

from dataclasses import dataclass

import pytest

from finance_builder_base.contracts import ErrorDetail
from finance_builder_base.solution_build import (
    BuildDefinition,
    BuildFailure,
    BuildPlan,
    BuildStageSpec,
    CancellationToken,
    FailureCategory,
    InMemoryCheckpointStore,
    ReplayPolicy,
    ResponsibilityBoundary,
    SolutionBuildCoordinator,
    SolutionBuildStatus,
    StageExecutionContext,
    StageExecutionResult,
    StageStatus,
    StandardBuildStage,
    standard_build_plan,
)


@dataclass
class Runner:
    result: StageExecutionResult
    supports_replay: bool = True
    calls: int = 0

    def run(self, context: StageExecutionContext) -> StageExecutionResult:
        self.calls += 1
        assert context.definition.task_id == "build-1"
        return self.result


def _definition(digest: str = "a") -> BuildDefinition:
    return BuildDefinition(task_id="build-1", input_digest=digest * 64)


def _passed() -> StageExecutionResult:
    return StageExecutionResult(status=StageStatus.PASSED, summary="stage passed")


def _runners(plan: BuildPlan) -> dict[str, Runner]:
    return {stage.stage_id: Runner(_passed()) for stage in plan.stages}


def test_success_has_ordered_events_and_one_terminal_status() -> None:
    plan = standard_build_plan()
    result = SolutionBuildCoordinator(
        _runners(plan), InMemoryCheckpointStore()
    ).run(_definition(), plan)

    assert result.status is SolutionBuildStatus.COMPLETED
    assert [event.sequence for event in result.events] == list(
        range(1, len(result.events) + 1)
    )
    terminal = [event for event in result.events if event.task_status is not None]
    assert [event.task_status for event in terminal] == [
        SolutionBuildStatus.RUNNING,
        SolutionBuildStatus.COMPLETED,
    ]


def test_failure_stops_before_later_stages_and_preserves_owner() -> None:
    plan = standard_build_plan()
    runners = _runners(plan)
    failed_stage = StandardBuildStage.CANDIDATE_BUILD.value
    runners[failed_stage].result = StageExecutionResult(
        status=StageStatus.FAILED,
        summary="candidate compile failed",
        failure=BuildFailure(
            category=FailureCategory.BACKEND,
            stage_id=failed_stage,
            responsibility=ResponsibilityBoundary.BACKEND,
            error=ErrorDetail(code="solution.build.compile_failed", message="compile failed"),
            next_action="inspect compile evidence",
        ),
    )

    result = SolutionBuildCoordinator(runners, InMemoryCheckpointStore()).run(
        _definition(), plan
    )

    assert result.status is SolutionBuildStatus.FAILED
    assert result.report.failures[0].responsibility is ResponsibilityBoundary.BACKEND
    assert runners[StandardBuildStage.CANDIDATE_START.value].calls == 0


def test_cancel_before_run_starts_no_stage() -> None:
    plan = standard_build_plan()
    token = CancellationToken()
    token.cancel()
    runners = _runners(plan)

    result = SolutionBuildCoordinator(runners, InMemoryCheckpointStore()).run(
        _definition(), plan, cancellation=token
    )

    assert result.status is SolutionBuildStatus.CANCELLED
    assert sum(runner.calls for runner in runners.values()) == 0
    assert any(event.event_type.value == "cancel-requested" for event in result.events)


def test_cancel_during_stage_finishes_current_action_but_starts_no_next_stage() -> None:
    plan = standard_build_plan()
    token = CancellationToken()
    runners = _runners(plan)

    class CancellingRunner:
        supports_replay = True

        def run(self, context: StageExecutionContext) -> StageExecutionResult:
            token.cancel()
            return _passed()

    first_stage = StandardBuildStage.INPUT_RECEIVED.value
    runners[first_stage] = CancellingRunner()  # type: ignore[assignment]
    result = SolutionBuildCoordinator(runners, InMemoryCheckpointStore()).run(
        _definition(), plan, cancellation=token
    )

    assert result.status is SolutionBuildStatus.CANCELLED
    assert result.report.stages[0].status is StageStatus.PASSED
    assert runners[StandardBuildStage.INPUT_VALIDATION.value].calls == 0


def test_resume_reuses_completed_checkpoint_and_rejects_changed_input() -> None:
    stages = list(standard_build_plan().stages)
    first_plan = BuildPlan(stages=tuple(stage.model_copy(update={"applicable": False}) for stage in stages))
    store = InMemoryCheckpointStore()
    coordinator = SolutionBuildCoordinator({}, store)
    first = coordinator.run(_definition(), first_plan)
    assert first.status is SolutionBuildStatus.COMPLETED

    resumed = coordinator.run(_definition(), first_plan, resume=True)
    assert resumed.status is SolutionBuildStatus.COMPLETED
    with pytest.raises(ValueError, match="input digest"):
        coordinator.run(_definition("b"), first_plan, resume=True)


def test_resume_blocks_non_replayable_external_stage() -> None:
    stages = list(standard_build_plan().stages)
    for index, stage in enumerate(stages):
        if stage.standard_stage is StandardBuildStage.CANDIDATE_START:
            stages[index] = BuildStageSpec(
                stage_id=stage.stage_id,
                standard_stage=stage.standard_stage,
                replay_policy=ReplayPolicy.IDEMPOTENT_EXTERNAL,
                idempotency_key="candidate-start:1",
            )
        elif StandardBuildStage.__members__[stage.standard_stage.name].value != stage.stage_id:
            raise AssertionError("invalid test stage")
    plan = BuildPlan(stages=tuple(stages))
    store = InMemoryCheckpointStore()
    runners = _runners(plan)
    runners[StandardBuildStage.CANDIDATE_START.value].supports_replay = False

    # Seed a safe checkpoint before the external stage by stopping at build.
    stop_stage = StandardBuildStage.CANDIDATE_BUILD.value
    runners[stop_stage].result = StageExecutionResult(
        status=StageStatus.BLOCKED,
        summary="dependency unavailable",
        failure=BuildFailure(
            category=FailureCategory.BACKEND,
            stage_id=stop_stage,
            responsibility=ResponsibilityBoundary.BACKEND,
            error=ErrorDetail(code="solution.build.dependency", message="dependency unavailable"),
            next_action="restore dependency",
        ),
    )
    SolutionBuildCoordinator(runners, store).run(_definition(), plan)
    runners[stop_stage].result = _passed()

    resumed = SolutionBuildCoordinator(runners, store).run(
        _definition(), plan, resume=True
    )
    assert resumed.status is SolutionBuildStatus.BLOCKED
    assert resumed.report.failures[-1].error.code == "solution.build.replay_not_supported"


def test_runner_exception_is_redacted_at_public_boundary() -> None:
    plan = standard_build_plan()
    runners = _runners(plan)

    class ExplodingRunner:
        supports_replay = True

        def run(self, context: StageExecutionContext) -> StageExecutionResult:
            raise RuntimeError("api_key=plain-secret at C:\\private\\candidate")

    runners[StandardBuildStage.INPUT_RECEIVED.value] = ExplodingRunner()  # type: ignore[assignment]
    result = SolutionBuildCoordinator(runners, InMemoryCheckpointStore()).run(
        _definition(), plan
    )

    rendered = result.model_dump_json()
    assert "plain-secret" not in rendered
    assert "C:\\private" not in rendered
    assert "<redacted-credential>" in rendered


def test_invalid_runner_result_becomes_public_protocol_failure() -> None:
    plan = standard_build_plan()
    runners = _runners(plan)
    wrong_stage = StandardBuildStage.CANDIDATE_BUILD.value

    class InvalidRunner:
        supports_replay = True

        def run(self, context: StageExecutionContext) -> StageExecutionResult:
            return StageExecutionResult(
                status=StageStatus.FAILED,
                summary="invalid stage ownership",
                failure=BuildFailure(
                    category=FailureCategory.BACKEND,
                    stage_id=wrong_stage,
                    responsibility=ResponsibilityBoundary.BACKEND,
                    error=ErrorDetail(
                        code="solution.build.invalid_owner",
                        message="invalid stage ownership",
                    ),
                    next_action="correct the runner",
                ),
            )

    runners[StandardBuildStage.INPUT_RECEIVED.value] = InvalidRunner()  # type: ignore[assignment]
    result = SolutionBuildCoordinator(runners, InMemoryCheckpointStore()).run(
        _definition(), plan
    )

    assert result.status is SolutionBuildStatus.FAILED
    assert result.report.failures[0].stage_id == StandardBuildStage.INPUT_RECEIVED.value
    assert (
        result.report.failures[0].error.code
        == "solution.build.runner_contract_invalid"
    )
