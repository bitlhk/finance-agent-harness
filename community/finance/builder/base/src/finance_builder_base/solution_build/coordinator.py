"""Deterministic coordinator for host-provided Solution build stages."""

from __future__ import annotations

from collections.abc import Callable, Iterable, Mapping
from datetime import datetime, timezone
from time import monotonic

from finance_builder_base.contracts import ErrorDetail, sanitize_public_text
from finance_builder_base.solution_build.checkpoint import CancellationToken
from finance_builder_base.solution_build.models import (
    BuildCheckpoint,
    BuildDefinition,
    BuildEvent,
    BuildFailure,
    BuildPlan,
    EventType,
    FailureCategory,
    ReplayPolicy,
    ResolvedBuildAsset,
    ResponsibilityBoundary,
    SolutionBuildResult,
    SolutionBuildStatus,
    StageExecutionRecord,
    StageExecutionResult,
    StageStatus,
)
from finance_builder_base.solution_build.records import build_asset_lock, build_report
from finance_builder_base.solution_build.spi import (
    CheckpointStore,
    StageExecutionContext,
    StageRunner,
)


EventObserver = Callable[[BuildEvent], None]


class SolutionBuildCoordinator:
    """Apply the public state machine without choosing business work."""

    def __init__(
        self,
        runners: Mapping[str, StageRunner],
        checkpoint_store: CheckpointStore,
        observer: EventObserver | None = None,
    ) -> None:
        self._runners = dict(runners)
        self._checkpoint_store = checkpoint_store
        self._observer = observer

    def run(
        self,
        definition: BuildDefinition,
        plan: BuildPlan,
        assets: Iterable[ResolvedBuildAsset] = (),
        *,
        resume: bool = False,
        cancellation: CancellationToken | None = None,
    ) -> SolutionBuildResult:
        token = cancellation or CancellationToken()
        asset_lock = build_asset_lock(definition, assets)
        checkpoint = self._checkpoint_store.load(definition.task_id) if resume else None
        if checkpoint is not None and checkpoint.input_digest != definition.input_digest:
            raise ValueError("checkpoint input digest does not match the current build")

        records = list(checkpoint.completed_stages if checkpoint else ())
        events = list(checkpoint.events if checkpoint else ())
        completed_ids = {record.stage_id for record in records}
        sequence = max((event.sequence for event in events), default=0)

        def emit(
            event_type: EventType,
            summary: str,
            *,
            stage_id: str | None = None,
            stage_status: StageStatus | None = None,
            task_status: SolutionBuildStatus | None = None,
        ) -> None:
            nonlocal sequence
            sequence += 1
            event = BuildEvent(
                task_id=definition.task_id,
                sequence=sequence,
                event_id=f"{definition.task_id}:{sequence}",
                event_type=event_type,
                summary=summary,
                stage_id=stage_id,
                stage_status=stage_status,
                task_status=task_status,
            )
            events.append(event)
            if self._observer is not None:
                self._observer(event)

        if not events:
            emit(
                EventType.TASK_STATUS,
                "solution build started",
                task_status=SolutionBuildStatus.RUNNING,
            )

        final_status: SolutionBuildStatus | None = None
        for stage in plan.stages:
            if stage.stage_id in completed_ids:
                continue
            if token.requested:
                emit(EventType.CANCEL_REQUESTED, "solution build cancellation requested")
                final_status = SolutionBuildStatus.CANCELLED
                break
            if not stage.applicable:
                now = datetime.now(timezone.utc)
                record = StageExecutionRecord(
                    stage_id=stage.stage_id,
                    status=StageStatus.SKIPPED,
                    summary="stage is not applicable to this build",
                    started_at=now,
                    finished_at=now,
                    duration_ms=0,
                )
                records.append(record)
                emit(
                    EventType.STAGE_STATUS,
                    record.summary,
                    stage_id=stage.stage_id,
                    stage_status=record.status,
                )
                self._save(definition, records, events)
                continue

            runner = self._runners.get(stage.stage_id)
            if runner is None:
                result = self._missing_runner(stage.stage_id)
                record = self._record(stage.stage_id, result)
            elif (
                resume
                and stage.replay_policy is ReplayPolicy.MANUAL
            ):
                result = self._manual_resume_block(stage.stage_id)
                record = self._record(stage.stage_id, result)
            elif (
                resume
                and stage.replay_policy is ReplayPolicy.IDEMPOTENT_EXTERNAL
                and not runner.supports_replay
            ):
                result = self._unsupported_replay(stage.stage_id)
                record = self._record(stage.stage_id, result)
            else:
                emit(
                    EventType.STAGE_STATUS,
                    "stage started",
                    stage_id=stage.stage_id,
                    stage_status=StageStatus.RUNNING,
                )
                started_at = datetime.now(timezone.utc)
                started_clock = monotonic()
                try:
                    result = runner.run(
                        StageExecutionContext(
                            definition=definition,
                            stage=stage,
                            resumed=resume,
                        )
                    )
                except Exception as exc:  # noqa: BLE001 - map backend failure at boundary
                    result = self._runner_exception(stage.stage_id, exc)
                finished_at = datetime.now(timezone.utc)
                try:
                    record = StageExecutionRecord(
                        stage_id=stage.stage_id,
                        **result.model_dump(),
                        started_at=started_at,
                        finished_at=finished_at,
                        duration_ms=max(0, int((monotonic() - started_clock) * 1000)),
                    )
                except Exception:  # noqa: BLE001 - reject malformed backend output
                    result = self._invalid_runner_result(stage.stage_id)
                    record = StageExecutionRecord(
                        stage_id=stage.stage_id,
                        **result.model_dump(),
                        started_at=started_at,
                        finished_at=finished_at,
                        duration_ms=max(0, int((monotonic() - started_clock) * 1000)),
                    )

            records.append(record)
            emit(
                EventType.STAGE_STATUS,
                record.summary,
                stage_id=stage.stage_id,
                stage_status=record.status,
            )
            if record.status in {StageStatus.PASSED, StageStatus.SKIPPED}:
                self._save(definition, records, events)
            if token.requested:
                emit(EventType.CANCEL_REQUESTED, "solution build cancellation requested")
                final_status = SolutionBuildStatus.CANCELLED
                break
            if record.status is StageStatus.FAILED:
                final_status = SolutionBuildStatus.FAILED
                break
            if record.status is StageStatus.BLOCKED:
                final_status = SolutionBuildStatus.BLOCKED
                break
            if record.status is StageStatus.CANCELLED:
                final_status = SolutionBuildStatus.CANCELLED
                break

        if final_status is None:
            final_status = SolutionBuildStatus.COMPLETED
        report = build_report(definition, asset_lock, final_status, records)
        emit(
            EventType.TASK_STATUS,
            f"solution build {final_status.value}",
            task_status=final_status,
        )
        return SolutionBuildResult(
            definition=definition,
            status=final_status,
            events=tuple(events),
            asset_lock=asset_lock,
            report=report,
        )

    def _save(
        self,
        definition: BuildDefinition,
        records: list[StageExecutionRecord],
        events: list[BuildEvent],
    ) -> None:
        self._checkpoint_store.save(
            BuildCheckpoint(
                task_id=definition.task_id,
                input_digest=definition.input_digest,
                completed_stages=tuple(records),
                events=tuple(events),
            )
        )

    @staticmethod
    def _record(stage_id: str, result: StageExecutionResult) -> StageExecutionRecord:
        now = datetime.now(timezone.utc)
        return StageExecutionRecord(
            stage_id=stage_id,
            **result.model_dump(),
            started_at=now,
            finished_at=now,
            duration_ms=0,
        )

    @staticmethod
    def _failure(
        stage_id: str,
        *,
        code: str,
        message: str,
        category: FailureCategory,
        responsibility: ResponsibilityBoundary,
        next_action: str,
    ) -> BuildFailure:
        return BuildFailure(
            category=category,
            stage_id=stage_id,
            responsibility=responsibility,
            error=ErrorDetail(code=code, message=message),
            next_action=next_action,
        )

    @classmethod
    def _missing_runner(cls, stage_id: str) -> StageExecutionResult:
        return StageExecutionResult(
            status=StageStatus.BLOCKED,
            summary="stage runner is not configured",
            failure=cls._failure(
                stage_id,
                code="solution.build.runner_missing",
                message="stage runner is not configured",
                category=FailureCategory.BACKEND,
                responsibility=ResponsibilityBoundary.BUILDER,
                next_action="configure a runner for the required stage",
            ),
        )

    @classmethod
    def _manual_resume_block(cls, stage_id: str) -> StageExecutionResult:
        return StageExecutionResult(
            status=StageStatus.BLOCKED,
            summary="stage requires manual recovery before replay",
            failure=cls._failure(
                stage_id,
                code="solution.build.manual_recovery_required",
                message="stage cannot be replayed automatically",
                category=FailureCategory.PROTOCOL,
                responsibility=ResponsibilityBoundary.CALLER,
                next_action="inspect the external action and resume with an approved checkpoint",
            ),
        )

    @classmethod
    def _unsupported_replay(cls, stage_id: str) -> StageExecutionResult:
        return StageExecutionResult(
            status=StageStatus.BLOCKED,
            summary="stage runner does not support idempotent replay",
            failure=cls._failure(
                stage_id,
                code="solution.build.replay_not_supported",
                message="stage runner does not support idempotent replay",
                category=FailureCategory.PROTOCOL,
                responsibility=ResponsibilityBoundary.BACKEND,
                next_action="provide a replay-capable runner or recover the action manually",
            ),
        )

    @classmethod
    def _runner_exception(cls, stage_id: str, exc: Exception) -> StageExecutionResult:
        summary = sanitize_public_text(str(exc)).strip() or "stage runner failed"
        return StageExecutionResult(
            status=StageStatus.FAILED,
            summary=summary[:1000],
            failure=cls._failure(
                stage_id,
                code="solution.build.runner_failed",
                message=summary[:1000],
                category=FailureCategory.BACKEND,
                responsibility=ResponsibilityBoundary.BACKEND,
                next_action="inspect the referenced backend evidence and retry the stage",
            ),
        )

    @classmethod
    def _invalid_runner_result(cls, stage_id: str) -> StageExecutionResult:
        return StageExecutionResult(
            status=StageStatus.FAILED,
            summary="stage runner returned an invalid result",
            failure=cls._failure(
                stage_id,
                code="solution.build.runner_contract_invalid",
                message="stage runner returned an invalid result",
                category=FailureCategory.PROTOCOL,
                responsibility=ResponsibilityBoundary.BACKEND,
                next_action="correct the stage runner result and retry the build",
            ),
        )
