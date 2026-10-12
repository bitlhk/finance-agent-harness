"""Public-safe Sandbox Evaluation report and evidence persistence."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path

from finance_builder_base import ResourceKind
from finance_builder_base.evaluation import EvaluationConclusion, EvaluationReport
from finance_solution_builder.candidate_backend import CandidateResourceRef

from .findings import project_findings, project_lifecycle_finding
from .models import (
    EvaluationSampleEvidence,
    EvidenceLayerRecord,
    EvidenceLevel,
    EvidenceStatus,
    SampleExecutionStatus,
    SandboxEvaluationReport,
)


def build_evaluation_report(
    snapshot,
    base_report: EvaluationReport,
    samples: tuple[EvaluationSampleEvidence, ...],
    *,
    lifecycle_evidence_refs: tuple[CandidateResourceRef, ...] = (),
    incomplete_reason: str | None = None,
) -> SandboxEvaluationReport:
    """Project the shared engine result into the Builder-owned public report."""

    results_by_id = {result.case_id: result for result in base_report.samples}
    projected_samples: list[EvaluationSampleEvidence] = []
    for sample in samples:
        result = results_by_id.get(sample.sample_id)
        projected_samples.append(
            sample.model_copy(
                update={
                    "matched": None if result is None else result.matched,
                    "status": (
                        SampleExecutionStatus.FAILED
                        if result is None or result.error is not None
                        else SampleExecutionStatus.PASSED
                    ),
                    "error": (
                        sample.error if sample.error is not None or result is None else result.error
                    ),
                }
            )
        )

    incomplete = (
        incomplete_reason is not None
        or base_report.progress.completed != base_report.progress.total
        or any(result.error is not None for result in base_report.samples)
    )
    conclusion = base_report.conclusion
    if incomplete or conclusion is EvaluationConclusion.CANCELLED:
        conclusion = EvaluationConclusion.INDETERMINATE
    sandbox_status = {
        EvaluationConclusion.PASSED: EvidenceStatus.PASSED,
        EvaluationConclusion.FAILED: EvidenceStatus.FAILED,
        EvaluationConclusion.INDETERMINATE: EvidenceStatus.INDETERMINATE,
        EvaluationConclusion.CANCELLED: EvidenceStatus.INDETERMINATE,
    }[conclusion]
    findings = list(project_findings(base_report.samples, tuple(projected_samples)))
    if incomplete_reason is not None:
        findings.append(project_lifecycle_finding(incomplete_reason))

    evidence_levels = tuple(
        EvidenceLayerRecord(
            level=level,
            executed=level is EvidenceLevel.SANDBOX,
            status=(
                sandbox_status if level is EvidenceLevel.SANDBOX else EvidenceStatus.NOT_EXECUTED
            ),
            evidence_refs=(lifecycle_evidence_refs if level is EvidenceLevel.SANDBOX else ()),
        )
        for level in EvidenceLevel
    )
    return SandboxEvaluationReport(
        snapshot=snapshot,
        conclusion=conclusion,
        progress=base_report.progress,
        metrics=base_report.metrics,
        samples=tuple(projected_samples),
        findings=tuple(findings),
        evidence_levels=evidence_levels,
    )


@dataclass(frozen=True)
class EvaluationArtifacts:
    report_path: Path
    report_ref: CandidateResourceRef
    snapshot_ref: CandidateResourceRef
    finding_ref: CandidateResourceRef
    holdout_access_ref: CandidateResourceRef | None

    @property
    def evidence_refs(self) -> tuple[CandidateResourceRef, ...]:
        optional = () if self.holdout_access_ref is None else (self.holdout_access_ref,)
        return (self.snapshot_ref, self.finding_ref, self.report_ref, *optional)


def write_evaluation_evidence(
    output_dir: Path,
    report: SandboxEvaluationReport,
) -> EvaluationArtifacts:
    """Write only public-safe evaluation facts below the current output root."""

    evaluation_dir = output_dir / "evaluation"
    evaluation_dir.mkdir(parents=True, exist_ok=True)
    snapshot_ref = _write_json(
        output_dir,
        evaluation_dir / "evaluation-snapshot.json",
        report.snapshot.to_wire(),
    )
    finding_ref = _write_json(
        output_dir,
        evaluation_dir / "findings.json",
        [finding.to_wire() for finding in report.findings],
    )
    holdout_access_ref = None
    if report.snapshot.holdout_access is not None:
        holdout_access_ref = _write_json(
            output_dir,
            evaluation_dir / "holdout-access.json",
            report.snapshot.holdout_access.to_wire(),
        )
    report_path = evaluation_dir / "evaluation-report.json"
    report_ref = _write_json(output_dir, report_path, report.to_wire())
    return EvaluationArtifacts(
        report_path=report_path,
        report_ref=report_ref,
        snapshot_ref=snapshot_ref,
        finding_ref=finding_ref,
        holdout_access_ref=holdout_access_ref,
    )


def _write_json(output_dir: Path, path: Path, value) -> CandidateResourceRef:
    body = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    path.write_bytes(body)
    return CandidateResourceRef(
        kind=ResourceKind.WORKSPACE_PATH,
        locator=path.relative_to(output_dir).as_posix(),
        media_type="application/json",
        sha256=hashlib.sha256(body).hexdigest(),
    )
