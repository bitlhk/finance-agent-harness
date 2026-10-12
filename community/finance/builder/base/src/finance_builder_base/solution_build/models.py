"""Versioned, domain-neutral contracts for Solution builds."""

from __future__ import annotations

from datetime import datetime, timezone
from enum import Enum
from typing import Literal

from pydantic import Field, SerializeAsAny, field_validator, model_validator

from finance_builder_base.contracts import (
    ContractModel,
    ErrorDetail,
    ExtensionModel,
    ResourceRef,
)


PROTOCOL_VERSION = "1.0"


class StandardBuildStage(str, Enum):
    INPUT_RECEIVED = "input-received"
    INPUT_VALIDATION = "input-validation"
    ASSET_RESOLUTION = "asset-resolution"
    CANDIDATE_ASSEMBLY = "candidate-assembly"
    CANDIDATE_BUILD = "candidate-build"
    CANDIDATE_START = "candidate-start"
    CANDIDATE_VALIDATION = "candidate-validation"
    DELIVERY_PACKAGING = "delivery-packaging"


STANDARD_STAGE_ORDER = tuple(StandardBuildStage)


class StageStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    PASSED = "passed"
    FAILED = "failed"
    SKIPPED = "skipped"
    BLOCKED = "blocked"
    CANCELLED = "cancelled"


class SolutionBuildStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    BLOCKED = "blocked"
    CANCELLED = "cancelled"


class CheckStatus(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    SKIPPED = "skipped"
    BLOCKED = "blocked"


class ReplayPolicy(str, Enum):
    SAFE = "safe"
    IDEMPOTENT_EXTERNAL = "idempotent-external"
    MANUAL = "manual"


class EventType(str, Enum):
    TASK_STATUS = "task-status"
    STAGE_STATUS = "stage-status"
    CANCEL_REQUESTED = "cancel-requested"


class FailureCategory(str, Enum):
    INPUT = "input"
    ASSET = "asset"
    BACKEND = "backend"
    VERIFICATION = "verification"
    PROTOCOL = "protocol"
    INTERNAL = "internal"


class ResponsibilityBoundary(str, Enum):
    CALLER = "caller"
    ASSET_PROVIDER = "asset-provider"
    BACKEND = "backend"
    CANDIDATE = "candidate"
    VERIFICATION = "verification"
    BUILDER = "builder"


def _validate_public_text(value: str) -> str:
    ErrorDetail(code="solution.build.public_text", message=value)
    return value


class BuildDefinition(ContractModel):
    protocol_version: Literal["1.0"] = PROTOCOL_VERSION
    task_id: str = Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")
    input_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    extension: SerializeAsAny[ExtensionModel] | None = None


class BuildStageSpec(ContractModel):
    stage_id: str = Field(min_length=1, max_length=200, pattern=r"^[a-z][a-z0-9.-]*$")
    standard_stage: StandardBuildStage | None = None
    after_standard_stage: StandardBuildStage | None = None
    applicable: bool = True
    replay_policy: ReplayPolicy = ReplayPolicy.SAFE
    idempotency_key: str | None = Field(
        default=None, min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$"
    )

    @model_validator(mode="after")
    def validate_identity_and_replay(self) -> BuildStageSpec:
        if self.standard_stage is not None:
            if self.stage_id != self.standard_stage.value:
                raise ValueError("standard stage_id must equal standard_stage value")
            if self.after_standard_stage is not None:
                raise ValueError("standard stage cannot declare after_standard_stage")
        else:
            if "." not in self.stage_id or self.after_standard_stage is None:
                raise ValueError(
                    "extension stage requires a namespaced stage_id and an anchor"
                )
        if (
            self.replay_policy is ReplayPolicy.IDEMPOTENT_EXTERNAL
            and self.idempotency_key is None
        ):
            raise ValueError("idempotent external stage requires idempotency_key")
        return self


class BuildPlan(ContractModel):
    stages: tuple[BuildStageSpec, ...]

    @model_validator(mode="after")
    def validate_stage_order(self) -> BuildPlan:
        if not self.stages:
            raise ValueError("build plan requires stages")
        ids = [stage.stage_id for stage in self.stages]
        if len(ids) != len(set(ids)):
            raise ValueError("stage_id must be unique within a build plan")

        standard_positions = {
            stage.standard_stage: index
            for index, stage in enumerate(self.stages)
            if stage.standard_stage is not None
        }
        if set(standard_positions) != set(STANDARD_STAGE_ORDER):
            raise ValueError("build plan must contain every standard stage exactly once")
        positions = [standard_positions[stage] for stage in STANDARD_STAGE_ORDER]
        if positions != sorted(positions):
            raise ValueError("standard build stages must preserve semantic order")

        for index, stage in enumerate(self.stages):
            if stage.standard_stage is not None:
                continue
            anchor = stage.after_standard_stage
            assert anchor is not None
            anchor_index = standard_positions[anchor]
            next_index = next(
                (
                    standard_positions[item]
                    for item in STANDARD_STAGE_ORDER
                    if STANDARD_STAGE_ORDER.index(item) > STANDARD_STAGE_ORDER.index(anchor)
                ),
                len(self.stages),
            )
            if not anchor_index < index < next_index:
                raise ValueError("extension stage must follow its declared anchor")
        return self


def standard_build_plan(
    *, skipped: set[StandardBuildStage] | frozenset[StandardBuildStage] = frozenset()
) -> BuildPlan:
    """Create the canonical plan while retaining explicit skipped stages."""

    return BuildPlan(
        stages=tuple(
            BuildStageSpec(
                stage_id=stage.value,
                standard_stage=stage,
                applicable=stage not in skipped,
            )
            for stage in STANDARD_STAGE_ORDER
        )
    )


class BuildCheckResult(ContractModel):
    check_id: str = Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")
    status: CheckStatus
    summary: str = Field(min_length=1, max_length=1000)
    evidence_refs: tuple[ResourceRef, ...] = ()

    _public_summary = field_validator("summary")(_validate_public_text)


class BuildFailure(ContractModel):
    category: FailureCategory
    stage_id: str = Field(min_length=1, max_length=200)
    responsibility: ResponsibilityBoundary
    error: ErrorDetail
    next_action: str = Field(min_length=1, max_length=1000)
    diagnostic_refs: tuple[ResourceRef, ...] = ()

    _public_next_action = field_validator("next_action")(_validate_public_text)


class StageExecutionResult(ContractModel):
    status: StageStatus
    summary: str = Field(min_length=1, max_length=1000)
    checks: tuple[BuildCheckResult, ...] = ()
    artifact_refs: tuple[ResourceRef, ...] = ()
    failure: BuildFailure | None = None

    _public_summary = field_validator("summary")(_validate_public_text)

    @model_validator(mode="after")
    def require_terminal_result(self) -> StageExecutionResult:
        if self.status in {StageStatus.PENDING, StageStatus.RUNNING}:
            raise ValueError("runner result must be a terminal stage status")
        needs_failure = self.status in {StageStatus.FAILED, StageStatus.BLOCKED}
        if needs_failure != (self.failure is not None):
            raise ValueError("failed or blocked result requires exactly one failure")
        check_ids = [check.check_id for check in self.checks]
        if len(check_ids) != len(set(check_ids)):
            raise ValueError("check_id must be unique within a stage result")
        if self.status is StageStatus.PASSED and any(
            check.status in {CheckStatus.FAILED, CheckStatus.BLOCKED}
            for check in self.checks
        ):
            raise ValueError("passed stage cannot contain failed or blocked checks")
        if self.status is StageStatus.SKIPPED and any(
            check.status is not CheckStatus.SKIPPED for check in self.checks
        ):
            raise ValueError("skipped stage can contain only skipped checks")
        return self


class StageExecutionRecord(StageExecutionResult):
    stage_id: str = Field(min_length=1, max_length=200)
    started_at: datetime
    finished_at: datetime
    duration_ms: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_time_order(self) -> StageExecutionRecord:
        if self.finished_at < self.started_at:
            raise ValueError("finished_at cannot precede started_at")
        if self.failure is not None and self.failure.stage_id != self.stage_id:
            raise ValueError("stage failure must belong to the execution record stage")
        return self


class BuildEvent(ContractModel):
    protocol_version: Literal["1.0"] = PROTOCOL_VERSION
    task_id: str = Field(min_length=1, max_length=200)
    sequence: int = Field(ge=1)
    event_id: str = Field(min_length=1, max_length=240, pattern=r"^[A-Za-z0-9_.:-]+$")
    event_type: EventType
    occurred_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    summary: str = Field(min_length=1, max_length=1000)
    stage_id: str | None = Field(default=None, min_length=1, max_length=200)
    stage_status: StageStatus | None = None
    task_status: SolutionBuildStatus | None = None

    _public_summary = field_validator("summary")(_validate_public_text)

    @model_validator(mode="after")
    def validate_event_shape(self) -> BuildEvent:
        if self.event_type is EventType.STAGE_STATUS:
            if self.stage_id is None or self.stage_status is None or self.task_status is not None:
                raise ValueError("stage event requires only stage_id and stage_status")
        elif self.event_type is EventType.TASK_STATUS:
            if self.task_status is None or self.stage_status is not None:
                raise ValueError("task event requires only task_status")
        elif self.event_type is EventType.CANCEL_REQUESTED:
            if self.stage_status is not None or self.task_status is not None:
                raise ValueError("cancel-requested event cannot declare a status")
        return self


class ResolvedBuildAsset(ContractModel):
    asset_id: str = Field(min_length=1, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")
    asset_type: str = Field(min_length=1, max_length=100, pattern=r"^[a-z][a-z0-9.-]*$")
    version: str = Field(min_length=1, max_length=100)
    revision: str | None = Field(default=None, max_length=200)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    dependencies: tuple[str, ...] = ()
    resource: ResourceRef | None = None

    _public_version = field_validator("version")(_validate_public_text)

    @field_validator("revision")
    @classmethod
    def validate_public_revision(cls, value: str | None) -> str | None:
        return None if value is None else _validate_public_text(value)

    @field_validator("resource")
    @classmethod
    def validate_public_resource(cls, value: ResourceRef | None) -> ResourceRef | None:
        if value is None:
            return None
        _validate_public_text(value.locator)
        if value.name is not None:
            _validate_public_text(value.name)
        if value.media_type is not None:
            _validate_public_text(value.media_type)
        return value

    @field_validator("dependencies")
    @classmethod
    def unique_dependencies(cls, value: tuple[str, ...]) -> tuple[str, ...]:
        if len(value) != len(set(value)):
            raise ValueError("asset dependencies must be unique")
        return value


class SolutionAssetLock(ContractModel):
    protocol_version: Literal["1.0"] = PROTOCOL_VERSION
    task_id: str = Field(min_length=1)
    input_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    assets: tuple[ResolvedBuildAsset, ...]
    lock_digest: str = Field(pattern=r"^[0-9a-f]{64}$")


class SolutionBuildReport(ContractModel):
    protocol_version: Literal["1.0"] = PROTOCOL_VERSION
    task_id: str = Field(min_length=1)
    input_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    lock_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    status: SolutionBuildStatus
    stages: tuple[StageExecutionRecord, ...]
    failures: tuple[BuildFailure, ...] = ()

    @model_validator(mode="after")
    def validate_report_truth(self) -> SolutionBuildReport:
        if self.status not in {
            SolutionBuildStatus.COMPLETED,
            SolutionBuildStatus.FAILED,
            SolutionBuildStatus.BLOCKED,
            SolutionBuildStatus.CANCELLED,
        }:
            raise ValueError("build report requires a terminal task status")
        stage_ids = [stage.stage_id for stage in self.stages]
        if len(stage_ids) != len(set(stage_ids)):
            raise ValueError("stage_id must be unique within a build report")
        stage_failures = tuple(
            stage.failure for stage in self.stages if stage.failure is not None
        )
        if self.failures != stage_failures:
            raise ValueError("report failures must match stage failures")
        if self.status in {SolutionBuildStatus.FAILED, SolutionBuildStatus.BLOCKED} and not (
            self.failures
        ):
            raise ValueError("failed or blocked report requires a stage failure")
        stop_statuses = {StageStatus.FAILED, StageStatus.BLOCKED, StageStatus.CANCELLED}
        stops = [stage.status for stage in self.stages if stage.status in stop_statuses]
        expected_stop = {
            SolutionBuildStatus.FAILED: StageStatus.FAILED,
            SolutionBuildStatus.BLOCKED: StageStatus.BLOCKED,
            SolutionBuildStatus.CANCELLED: StageStatus.CANCELLED,
        }.get(self.status)
        if self.status is SolutionBuildStatus.COMPLETED and stops:
            raise ValueError("completed report can contain only passed or skipped stages")
        if self.status in {SolutionBuildStatus.FAILED, SolutionBuildStatus.BLOCKED} and (
            stops != [expected_stop] or self.stages[-1].status is not expected_stop
        ):
            raise ValueError("failed or blocked report must end at its matching stage status")
        if self.status is SolutionBuildStatus.CANCELLED and (
            stops not in ([], [expected_stop])
            or (stops and self.stages[-1].status is not expected_stop)
        ):
            raise ValueError("cancelled report can end only at a cancelled stage")
        return self


class BuildCheckpoint(ContractModel):
    protocol_version: Literal["1.0"] = PROTOCOL_VERSION
    task_id: str = Field(min_length=1)
    input_digest: str = Field(pattern=r"^[0-9a-f]{64}$")
    completed_stages: tuple[StageExecutionRecord, ...] = ()
    events: tuple[BuildEvent, ...] = ()

    @model_validator(mode="after")
    def validate_checkpoint_identity(self) -> BuildCheckpoint:
        if any(event.task_id != self.task_id for event in self.events):
            raise ValueError("checkpoint events must belong to the checkpoint task")
        sequences = [event.sequence for event in self.events]
        if sequences != list(range(1, len(sequences) + 1)):
            raise ValueError("checkpoint event sequences must be contiguous and ordered")
        stage_ids = [stage.stage_id for stage in self.completed_stages]
        if len(stage_ids) != len(set(stage_ids)):
            raise ValueError("checkpoint stages must be unique")
        if any(
            stage.status not in {StageStatus.PASSED, StageStatus.SKIPPED}
            for stage in self.completed_stages
        ):
            raise ValueError("checkpoint can contain only safely completed stages")
        if any(
            event.task_status
            in {
                SolutionBuildStatus.COMPLETED,
                SolutionBuildStatus.FAILED,
                SolutionBuildStatus.BLOCKED,
                SolutionBuildStatus.CANCELLED,
            }
            for event in self.events
        ):
            raise ValueError("checkpoint cannot contain a terminal task event")
        return self


class SolutionBuildResult(ContractModel):
    definition: BuildDefinition
    status: SolutionBuildStatus
    events: tuple[BuildEvent, ...]
    asset_lock: SolutionAssetLock
    report: SolutionBuildReport

    @model_validator(mode="after")
    def validate_result_consistency(self) -> SolutionBuildResult:
        if self.status is not self.report.status:
            raise ValueError("result status must match report status")
        if not (
            self.definition.task_id
            == self.asset_lock.task_id
            == self.report.task_id
        ):
            raise ValueError("result artifacts must belong to the definition task")
        if not (
            self.definition.input_digest
            == self.asset_lock.input_digest
            == self.report.input_digest
        ):
            raise ValueError("result artifacts must belong to the definition input")
        if self.asset_lock.lock_digest != self.report.lock_digest:
            raise ValueError("result report must reference the attached asset lock")
        sequences = [event.sequence for event in self.events]
        if sequences != list(range(1, len(sequences) + 1)):
            raise ValueError("result events must have contiguous task-local sequences")
        if len({event.event_id for event in self.events}) != len(self.events):
            raise ValueError("event_id must be unique within a build result")
        if any(event.task_id != self.definition.task_id for event in self.events):
            raise ValueError("result events must belong to the definition task")
        terminal_events = [
            event
            for event in self.events
            if event.task_status
            in {
                SolutionBuildStatus.COMPLETED,
                SolutionBuildStatus.FAILED,
                SolutionBuildStatus.BLOCKED,
                SolutionBuildStatus.CANCELLED,
            }
        ]
        if len(terminal_events) != 1 or terminal_events[0] is not self.events[-1]:
            raise ValueError("build result requires one final terminal event")
        if terminal_events[0].task_status is not self.status:
            raise ValueError("final terminal event must match the build result status")
        return self
