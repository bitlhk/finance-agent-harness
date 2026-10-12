"""Bounded orchestration of correction, rebuild, evaluation and promotion."""

from __future__ import annotations

import time
from typing import Protocol

from finance_builder_base import ResourceRef
from finance_builder_base.evaluation import EvaluationConclusion
from finance_solution_builder.evaluation import EvaluationFinding, SandboxEvaluationReport

from .models import (
    CorrectionExecution,
    CorrectionTarget,
    GovernedTarget,
    OptimizationRequest,
    OptimizationResult,
    OptimizationRoundRecord,
    OptimizationRoundStatus,
    OptimizationSnapshot,
    OptimizationStopReason,
    OptimizationUsage,
    RebuildOutcome,
)
from .promotion import compare_for_promotion, dataset_snapshot_digest
from .records import OptimizationRecordStore, budget_stop_reason
from .routing import external_proposal_context, route_correction


class CorrectionExecutor(Protocol):
    def execute(self, target: CorrectionTarget, round_index: int) -> CorrectionExecution: ...


class RoutedCorrectionExecutor:
    """Dispatch one target to Candidate Construction or optimization DataFactory."""

    def __init__(self, *, construction: CorrectionExecutor, data_factory: CorrectionExecutor) -> None:
        self._construction = construction
        self._data_factory = data_factory

    def execute(self, target: CorrectionTarget, round_index: int) -> CorrectionExecution:
        from .models import CorrectionTargetKind

        if target.target_kind is CorrectionTargetKind.OPTIMIZATION_DATA:
            return self._data_factory.execute(target, round_index)
        if target.target_kind in {
            CorrectionTargetKind.BUILDER_CONFIG,
            CorrectionTargetKind.CANDIDATE_OVERLAY,
        }:
            return self._construction.execute(target, round_index)
        raise ValueError("optimization.correction.external_target")


class RebuildGateway(Protocol):
    def rebuild(
        self,
        parent: OptimizationSnapshot,
        correction: CorrectionExecution,
        round_index: int,
    ) -> RebuildOutcome: ...


class EvaluationGateway(Protocol):
    def evaluate(
        self,
        candidate: OptimizationSnapshot,
        correction: CorrectionExecution | None,
        *,
        final_gate: bool,
    ) -> SandboxEvaluationReport: ...


class ExternalProposalGateway(Protocol):
    def write(
        self,
        request: OptimizationRequest,
        finding: EvaluationFinding,
        target: GovernedTarget,
    ) -> ResourceRef: ...


class OptimizationController:
    """Run one deterministic optimization loop within declared budgets."""

    def __init__(
        self,
        *,
        correction_executor: CorrectionExecutor,
        rebuild_gateway: RebuildGateway,
        evaluation_gateway: EvaluationGateway,
        record_store: OptimizationRecordStore,
        external_proposal_gateway: ExternalProposalGateway | None = None,
        clock=time.monotonic,
    ) -> None:
        self._correction_executor = correction_executor
        self._rebuild_gateway = rebuild_gateway
        self._evaluation_gateway = evaluation_gateway
        self._record_store = record_store
        self._external_proposal_gateway = external_proposal_gateway
        self._clock = clock

    def run(
        self, request: OptimizationRequest, baseline_report: SandboxEvaluationReport
    ) -> OptimizationResult:
        self._verify_report_identity(request.baseline, baseline_report)
        if request.findings != baseline_report.findings:
            raise ValueError("optimization.evaluation.findings_mismatch")
        self._record_store.bind_request(request)
        existing_result = self._record_store.read_result(request.request_id)
        if existing_result is not None:
            return existing_result
        started = self._clock()
        best = request.baseline
        best_report = baseline_report
        current_report = baseline_report
        usage = OptimizationUsage(
            rounds=0,
            duration_ms=0,
            cost_microunits=0,
            consecutive_no_improvement=0,
        )
        record_refs = []
        holdout_consumed = request.holdout_already_consumed

        while True:
            usage = usage.model_copy(
                update={"duration_ms": max(0, int((self._clock() - started) * 1000))}
            )
            stop = budget_stop_reason(request.budget, usage)
            if stop is not None:
                return self._finish(request, best, usage, stop, record_refs, holdout_consumed)

            if not current_report.findings:
                if current_report.conclusion is not EvaluationConclusion.PASSED:
                    return self._finish(
                        request,
                        best,
                        usage,
                        OptimizationStopReason.NO_EXECUTABLE_TARGET,
                        record_refs,
                        holdout_consumed,
                    )
                return self._complete_candidate(
                    request, best, usage, record_refs, holdout_consumed
                )

            active_request = request.model_copy(update={"findings": current_report.findings})
            routing = route_correction(active_request)
            if routing.target is None:
                if routing.stop_reason is OptimizationStopReason.ACCEPTANCE_MET:
                    return self._finish(
                        request, best, usage, routing.stop_reason, record_refs, holdout_consumed
                    )
                round_index = usage.rounds + 1
                usage = usage.model_copy(update={"rounds": round_index})
                terminal_reason = (
                    routing.stop_reason or OptimizationStopReason.NO_EXECUTABLE_TARGET
                )
                status = OptimizationRoundStatus.BLOCKED
                approval_fact = "No write task was created."
                validation_refs: tuple[ResourceRef, ...] = ()
                proposal_context = external_proposal_context(active_request)
                if proposal_context is not None and self._external_proposal_gateway is not None:
                    finding, governed = proposal_context
                    try:
                        validation_refs = (
                            self._external_proposal_gateway.write(
                                active_request, finding, governed
                            ),
                        )
                        approval_fact = (
                            "An external change proposal was recorded; no write task was created."
                        )
                    except Exception:
                        terminal_reason = OptimizationStopReason.FAILED
                        status = OptimizationRoundStatus.FAILED
                        approval_fact = "External proposal generation failed closed."
                record = OptimizationRoundRecord(
                    request_id=request.request_id,
                    round_index=round_index,
                    parent_snapshot=best,
                    finding_ids=routing.considered_finding_ids,
                    status=status,
                    approval_fact=approval_fact,
                    validation_refs=validation_refs,
                    usage=usage,
                    best_candidate_id=best.candidate_id,
                    best_lock_digest=best.lock_digest,
                    stop_reason=terminal_reason,
                )
                record_refs.append(self._record_store.append_round(record))
                return self._finish(
                    request,
                    best,
                    usage,
                    terminal_reason,
                    record_refs,
                    holdout_consumed,
                )

            target = routing.target
            round_index = usage.rounds + 1
            parent = best
            round_duration = 0
            round_cost = 0
            try:
                correction = self._correction_executor.execute(target, round_index)
                round_duration = correction.duration_ms
                round_cost = correction.cost_microunits
                elapsed = max(0, int((self._clock() - started) * 1000))
                consumed_duration = max(
                    elapsed, usage.duration_ms + correction.duration_ms
                )
                consumed_cost = usage.cost_microunits + correction.cost_microunits
                exhausted = None
                if consumed_duration > request.budget.max_duration_ms:
                    exhausted = OptimizationStopReason.MAX_DURATION
                elif consumed_cost > request.budget.max_cost_microunits:
                    exhausted = OptimizationStopReason.MAX_COST
                if exhausted is not None:
                    usage = OptimizationUsage(
                        rounds=round_index,
                        duration_ms=consumed_duration,
                        cost_microunits=consumed_cost,
                        consecutive_no_improvement=usage.consecutive_no_improvement,
                    )
                    record = OptimizationRoundRecord(
                        request_id=request.request_id,
                        round_index=round_index,
                        parent_snapshot=parent,
                        finding_ids=routing.considered_finding_ids,
                        target=target,
                        status=OptimizationRoundStatus.BLOCKED,
                        approval_fact="The correction consumed the remaining optimization budget.",
                        change_digest=correction.change_digest,
                        validation_refs=correction.evidence_refs,
                        usage=usage,
                        best_candidate_id=best.candidate_id,
                        best_lock_digest=best.lock_digest,
                        stop_reason=exhausted,
                    )
                    record_refs.append(self._record_store.append_round(record))
                    return self._finish(
                        request,
                        best,
                        usage,
                        exhausted,
                        record_refs,
                        holdout_consumed,
                    )
                rebuilt = self._rebuild_gateway.rebuild(best, correction, round_index)
                if rebuilt.snapshot.lock_digest == best.lock_digest:
                    raise ValueError("optimization.rebuild.lock_not_changed")
                candidate_report = self._evaluation_gateway.evaluate(
                    rebuilt.snapshot, correction, final_gate=False
                )
                self._verify_report_identity(rebuilt.snapshot, candidate_report)
                promotion = compare_for_promotion(
                    baseline=best,
                    candidate=rebuilt.snapshot,
                    baseline_report=best_report,
                    candidate_report=candidate_report,
                    objectives=request.objectives,
                    final_gate=False,
                    holdout_authorized=False,
                    holdout_already_consumed=False,
                )
                holdout_consumed = holdout_consumed or promotion.holdout_consumed
                improved = promotion.promoted
                usage = OptimizationUsage(
                    rounds=round_index,
                    duration_ms=consumed_duration,
                    cost_microunits=consumed_cost,
                    consecutive_no_improvement=(
                        0 if improved else usage.consecutive_no_improvement + 1
                    ),
                )
                if improved:
                    best = rebuilt.snapshot
                    best_report = candidate_report
                current_report = candidate_report
                status = (
                    OptimizationRoundStatus.SUCCEEDED
                    if improved
                    else OptimizationRoundStatus.REJECTED
                )
                record = OptimizationRoundRecord(
                    request_id=request.request_id,
                    round_index=round_index,
                    parent_snapshot=parent,
                    candidate_snapshot=rebuilt.snapshot,
                    new_lock_ref=rebuilt.lock_ref,
                    build_report_ref=rebuilt.report_ref,
                    finding_ids=routing.considered_finding_ids,
                    target=target,
                    status=status,
                    approval_fact="Automatic action was authorized by explicit governance.",
                    change_digest=correction.change_digest,
                    validation_refs=correction.evidence_refs,
                    evaluation_report_digest=candidate_report.sha256(),
                    promotion=promotion,
                    usage=usage,
                    best_candidate_id=best.candidate_id,
                    best_lock_digest=best.lock_digest,
                )
                record_refs.append(self._record_store.append_round(record))
                if improved and not candidate_report.findings:
                    return self._complete_candidate(
                        request, best, usage, record_refs, holdout_consumed
                    )
                if not improved and not candidate_report.findings:
                    return self._finish(
                        request,
                        best,
                        usage,
                        OptimizationStopReason.NO_IMPROVEMENT,
                        record_refs,
                        holdout_consumed,
                    )
            except Exception:  # public record contains only the stable phase outcome
                usage = OptimizationUsage(
                    rounds=round_index,
                    duration_ms=max(
                        max(0, int((self._clock() - started) * 1000)),
                        usage.duration_ms + round_duration,
                    ),
                    cost_microunits=usage.cost_microunits + round_cost,
                    consecutive_no_improvement=usage.consecutive_no_improvement + 1,
                )
                record = OptimizationRoundRecord(
                    request_id=request.request_id,
                    round_index=round_index,
                    parent_snapshot=best,
                    finding_ids=routing.considered_finding_ids,
                    target=target,
                    status=OptimizationRoundStatus.FAILED,
                    approval_fact="The correction failed closed before promotion.",
                    usage=usage,
                    best_candidate_id=best.candidate_id,
                    best_lock_digest=best.lock_digest,
                    stop_reason=OptimizationStopReason.FAILED,
                )
                record_refs.append(self._record_store.append_round(record))
                return self._finish(
                    request,
                    best,
                    usage,
                    OptimizationStopReason.FAILED,
                    record_refs,
                    holdout_consumed,
                )

    def _complete_candidate(
        self,
        request: OptimizationRequest,
        best: OptimizationSnapshot,
        usage: OptimizationUsage,
        record_refs: list,
        holdout_consumed: bool,
    ) -> OptimizationResult:
        if not request.final_gate:
            return self._finish(
                request,
                best,
                usage,
                OptimizationStopReason.ACCEPTANCE_MET,
                record_refs,
                holdout_consumed,
            )
        if not request.holdout_authorized:
            return self._finish(
                request,
                best,
                usage,
                OptimizationStopReason.APPROVAL_REQUIRED,
                record_refs,
                holdout_consumed,
            )
        if holdout_consumed:
            return self._finish(
                request,
                best,
                usage,
                OptimizationStopReason.NO_EXECUTABLE_TARGET,
                record_refs,
                holdout_consumed,
            )
        if not self._record_store.claim_holdout(request, best):
            existing_result = self._record_store.read_result(request.request_id)
            if existing_result is not None:
                return existing_result
            raise RuntimeError("optimization.holdout.already_claimed")
        holdout_consumed = True
        try:
            final_report = self._evaluation_gateway.evaluate(best, None, final_gate=True)
            self._verify_report_identity(best, final_report)
        except Exception:
            return self._finish(
                request,
                best,
                usage,
                OptimizationStopReason.FAILED,
                record_refs,
                holdout_consumed,
            )
        reason = (
            OptimizationStopReason.ACCEPTANCE_MET
            if final_report.conclusion is EvaluationConclusion.PASSED
            else OptimizationStopReason.FINAL_GATE_NOT_MET
        )
        return self._finish(
            request,
            best,
            usage,
            reason,
            record_refs,
            holdout_consumed,
            final_report_digest=final_report.sha256(),
        )

    def _finish(
        self,
        request: OptimizationRequest,
        best: OptimizationSnapshot,
        usage: OptimizationUsage,
        reason: OptimizationStopReason,
        record_refs: list,
        holdout_consumed: bool,
        *,
        final_report_digest: str | None = None,
    ) -> OptimizationResult:
        result = OptimizationResult(
            request_id=request.request_id,
            best_snapshot=best,
            rounds=usage.rounds,
            stop_reason=reason,
            final_usage=usage,
            record_refs=tuple(record_refs),
            holdout_consumed=holdout_consumed,
            final_report_digest=final_report_digest,
        )
        self._record_store.write_result(result)
        return result

    @staticmethod
    def _verify_report_identity(
        snapshot: OptimizationSnapshot, report: SandboxEvaluationReport
    ) -> None:
        report_snapshot = report.snapshot
        if (
            report_snapshot.candidate_id != snapshot.candidate_id
            or report_snapshot.descriptor_digest != snapshot.descriptor_digest
            or report_snapshot.lock_digest != snapshot.lock_digest
            or report_snapshot.artifact_digest != snapshot.artifact_digest
            or report_snapshot.pack_digest != snapshot.evaluation_pack_digest
            or report_snapshot.rules_digest != snapshot.rules_digest
            or dataset_snapshot_digest(report_snapshot.partitions) != snapshot.dataset_digest
            or snapshot.evidence_level
            not in {item.level.value for item in report.evidence_levels}
        ):
            raise ValueError("optimization.evaluation.identity_mismatch")
