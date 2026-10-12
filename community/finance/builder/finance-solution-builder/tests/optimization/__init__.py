"""Reusable deterministic fixtures for optimization tests."""

from finance_builder_base import ResourceKind, ResourceRef
from finance_builder_base.evaluation import EvaluationConclusion, EvaluationProgress, MetricValue
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import (
    DatasetPurpose,
    DatasetSnapshot,
    EvaluationFinding,
    EvaluationMode,
    EvaluationSnapshot,
    EvidenceLayerRecord,
    EvidenceLevel,
    EvidenceStatus,
    FindingResponsibility,
    SandboxEvaluationReport,
)
from finance_solution_builder.optimization import (
    ActionDisposition,
    CorrectionTargetKind,
    GovernedTarget,
    MetricDirection,
    MetricObjective,
    OptimizationBudget,
    OptimizationRequest,
    OptimizationSnapshot,
    dataset_snapshot_digest,
)

SHA = "a" * 64


def resource(locator: str = "evidence/report.json") -> ResourceRef:
    return ResourceRef(kind=ResourceKind.WORKSPACE_PATH, locator=locator, sha256=SHA)


def dataset() -> DatasetSnapshot:
    return DatasetSnapshot(
        dataset_id="regression",
        version="1",
        purpose=DatasetPurpose.REGRESSION,
        source_digest=SHA,
        authorization_id="approved-1",
        declared_sample_count=1,
        loaded_sample_count=1,
        content_digest=SHA,
    )


def snapshot(candidate_id: str = "candidate-v1", marker: str = "a") -> OptimizationSnapshot:
    digest = marker * 64
    return OptimizationSnapshot(
        candidate_id=candidate_id,
        descriptor_digest=digest,
        lock_digest=digest,
        artifact_digest=digest,
        evaluation_pack_digest=SHA,
        dataset_digest=dataset_snapshot_digest((dataset(),)),
        rules_digest=SHA,
        runtime_digest=SHA,
        evidence_level="sandbox",
    )


def finding(
    responsibility: FindingResponsibility = FindingResponsibility.CANDIDATE,
) -> EvaluationFinding:
    return EvaluationFinding(
        finding_id="finding-1",
        responsibility=responsibility,
        sample_ids=("sample-1",),
        summary="A public evaluation mismatch was found.",
        evidence_refs=(
            CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator="evidence/report.json",
                sha256=SHA,
            ),
        ),
    )


def report(
    candidate: OptimizationSnapshot,
    *,
    accuracy: float,
    passed: bool,
    findings: tuple[EvaluationFinding, ...] = (),
) -> SandboxEvaluationReport:
    frozen_dataset = dataset()
    return SandboxEvaluationReport(
        snapshot=EvaluationSnapshot(
            snapshot_id=SHA,
            request_id="evaluation-1",
            mode=EvaluationMode.TUNING,
            candidate_id=candidate.candidate_id,
            descriptor_digest=candidate.descriptor_digest,
            lock_digest=candidate.lock_digest,
            artifact_digest=candidate.artifact_digest,
            pack_id="eval-pack",
            pack_version="1",
            pack_digest=SHA,
            rules_digest=candidate.rules_digest,
            partitions=(frozen_dataset,),
            sample_order=("sample-1",),
        ),
        conclusion=(EvaluationConclusion.PASSED if passed else EvaluationConclusion.FAILED),
        progress=EvaluationProgress(completed=1, total=1),
        metrics=(MetricValue(name="accuracy", value=accuracy, numerator=1, denominator=1),),
        samples=(),
        findings=findings,
        evidence_levels=(
            EvidenceLayerRecord(
                level=EvidenceLevel.SANDBOX,
                executed=True,
                status=EvidenceStatus.PASSED if passed else EvidenceStatus.FAILED,
                evidence_refs=(
                    CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="evidence/report.json",
                        sha256=SHA,
                    ),
                ),
            ),
        ),
    )


def request(
    baseline: OptimizationSnapshot,
    *,
    findings: tuple[EvaluationFinding, ...] | None = None,
    governance: tuple[GovernedTarget, ...] | None = None,
    max_no_improvement: int = 2,
    final_gate: bool = False,
    holdout_authorized: bool = False,
    holdout_already_consumed: bool = False,
) -> OptimizationRequest:
    return OptimizationRequest(
        request_id="optimization-1",
        baseline=baseline,
        findings=findings if findings is not None else (finding(),),
        governance=(
            governance
            if governance is not None
            else (
                GovernedTarget(
                    responsibility=FindingResponsibility.CANDIDATE.value,
                    target_kind=CorrectionTargetKind.CANDIDATE_OVERLAY,
                    target_ref="candidate/source",
                    owner_id="builder-owner",
                    allowed_actions=("repair-candidate",),
                    approval_policy=ActionDisposition.AUTO,
                    allowed_write_paths=("src",),
                    validation_refs=(resource("evidence/validation.json"),),
                    priority=1,
                ),
            )
        ),
        objectives=(
            MetricObjective(name="accuracy", direction=MetricDirection.HIGHER),
        ),
        budget=OptimizationBudget(
            max_rounds=3,
            max_duration_ms=60_000,
            max_cost_microunits=100,
            max_no_improvement=max_no_improvement,
        ),
        final_gate=final_gate,
        holdout_authorized=holdout_authorized,
        holdout_already_consumed=holdout_already_consumed,
    )
