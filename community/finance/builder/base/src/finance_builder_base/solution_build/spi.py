"""Host-provided interfaces for the Solution Build Protocol."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from finance_builder_base.solution_build.models import (
    BuildCheckpoint,
    BuildDefinition,
    BuildStageSpec,
    StageExecutionResult,
)


@dataclass(frozen=True)
class StageExecutionContext:
    """Immutable runner input without backend-specific fields."""

    definition: BuildDefinition
    stage: BuildStageSpec
    resumed: bool


@runtime_checkable
class StageRunner(Protocol):
    """Execute one named stage and return public structured facts."""

    supports_replay: bool

    def run(self, context: StageExecutionContext) -> StageExecutionResult: ...


@runtime_checkable
class CheckpointStore(Protocol):
    """Persist task-local checkpoints; Base owns no database."""

    def load(self, task_id: str) -> BuildCheckpoint | None: ...

    def save(self, checkpoint: BuildCheckpoint) -> None: ...
