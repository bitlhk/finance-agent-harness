"""Comparable, regression-safe Candidate promotion policy."""

from __future__ import annotations

import hashlib
import json

from finance_builder_base.evaluation import EvaluationConclusion
from finance_solution_builder.evaluation import DatasetSnapshot, SandboxEvaluationReport

from .models import (
    MetricDelta,
    MetricDirection,
    MetricObjective,
    OptimizationSnapshot,
    PromotionDecision,
)


def dataset_snapshot_digest(partitions: tuple[DatasetSnapshot, ...]) -> str:
    """Bind an Optimization snapshot to the exact ordered Evaluation partitions."""

    payload = [
        item.model_dump(mode="json", by_alias=True, exclude_none=True)
        for item in partitions
    ]
    return hashlib.sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def compare_for_promotion(
    *,
    baseline: OptimizationSnapshot,
    candidate: OptimizationSnapshot,
    baseline_report: SandboxEvaluationReport,
    candidate_report: SandboxEvaluationReport,
    objectives: tuple[MetricObjective, ...],
    final_gate: bool,
    holdout_authorized: bool,
    holdout_already_consumed: bool,
) -> PromotionDecision:
    """Promote only under the same frozen evidence and strict improvement."""

    comparable = all(
        (
            _report_matches(baseline, baseline_report),
            _report_matches(candidate, candidate_report),
            baseline.dataset_digest == candidate.dataset_digest,
            baseline.evaluation_pack_digest == candidate.evaluation_pack_digest,
            baseline.rules_digest == candidate.rules_digest,
            baseline.runtime_digest == candidate.runtime_digest,
            baseline.evidence_level == candidate.evidence_level,
            baseline_report.snapshot.rules_digest == candidate_report.snapshot.rules_digest,
            tuple(item.content_digest for item in baseline_report.snapshot.partitions)
            == tuple(item.content_digest for item in candidate_report.snapshot.partitions),
        )
    )
    if not comparable:
        return PromotionDecision(
            comparable=False,
            promoted=False,
            reason="Candidate and baseline were evaluated under different frozen conditions.",
        )
    if final_gate and (not holdout_authorized or holdout_already_consumed):
        return PromotionDecision(
            comparable=True,
            promoted=False,
            reason="The final Holdout gate is unavailable or has already been consumed.",
        )
    baseline_metrics = {item.name: item.value for item in baseline_report.metrics}
    candidate_metrics = {item.name: item.value for item in candidate_report.metrics}
    deltas: list[MetricDelta] = []
    improved = False
    for objective in objectives:
        before = baseline_metrics.get(objective.name)
        after = candidate_metrics.get(objective.name)
        if before is None or after is None:
            return PromotionDecision(
                comparable=True,
                promoted=False,
                reason="A required metric is not computable.",
                holdout_consumed=final_gate,
            )
        oriented = after - before
        if objective.direction is MetricDirection.LOWER:
            oriented = -oriented
        deltas.append(
            MetricDelta(
                name=objective.name,
                baseline=before,
                candidate=after,
                improvement=oriented,
            )
        )
        if objective.required and oriented < 0:
            return PromotionDecision(
                comparable=True,
                promoted=False,
                reason="A required metric regressed.",
                deltas=tuple(deltas),
                holdout_consumed=final_gate,
            )
        improved = improved or oriented > 0
    if candidate_report.conclusion is not EvaluationConclusion.PASSED:
        return PromotionDecision(
            comparable=True,
            promoted=False,
            reason="Candidate did not pass the required Evaluation gates.",
            deltas=tuple(deltas),
            holdout_consumed=final_gate,
        )
    return PromotionDecision(
        comparable=True,
        promoted=improved,
        reason=(
            "Candidate strictly improved without a required-gate regression."
            if improved
            else "Candidate produced no strict metric improvement."
        ),
        deltas=tuple(deltas),
        holdout_consumed=final_gate,
    )


def _report_matches(
    snapshot: OptimizationSnapshot, report: SandboxEvaluationReport
) -> bool:
    actual = report.snapshot
    return all(
        (
            actual.candidate_id == snapshot.candidate_id,
            actual.descriptor_digest == snapshot.descriptor_digest,
            actual.lock_digest == snapshot.lock_digest,
            actual.artifact_digest == snapshot.artifact_digest,
            actual.pack_digest == snapshot.evaluation_pack_digest,
            actual.rules_digest == snapshot.rules_digest,
            dataset_snapshot_digest(actual.partitions) == snapshot.dataset_digest,
            snapshot.evidence_level in {item.level.value for item in report.evidence_levels},
        )
    )
