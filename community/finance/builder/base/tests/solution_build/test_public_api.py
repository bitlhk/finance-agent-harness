from __future__ import annotations

import finance_builder_base
from finance_builder_base.solution_build import (
    BuildDefinition,
    InMemoryCheckpointStore,
    SolutionBuildCoordinator,
    SolutionBuildStatus,
    StageExecutionContext,
    StageExecutionResult,
    StageStatus,
    standard_build_plan,
)


class PassingRunner:
    supports_replay = True

    def run(self, context: StageExecutionContext) -> StageExecutionResult:
        return StageExecutionResult(
            status=StageStatus.PASSED,
            summary=f"{context.stage.stage_id} passed",
        )


def test_root_package_exposes_minimal_solution_build_api() -> None:
    assert finance_builder_base.BuildDefinition is BuildDefinition
    assert finance_builder_base.SolutionBuildCoordinator is SolutionBuildCoordinator


def test_lightweight_runner_completes_protocol_without_runtime_dependency() -> None:
    plan = standard_build_plan()
    runners = {stage.stage_id: PassingRunner() for stage in plan.stages}
    result = SolutionBuildCoordinator(
        runners, InMemoryCheckpointStore()
    ).run(BuildDefinition(task_id="build-public", input_digest="f" * 64), plan)

    assert result.status is SolutionBuildStatus.COMPLETED
    assert len(result.report.stages) == 8
    assert result.asset_lock.assets == ()
