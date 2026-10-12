from __future__ import annotations

import json
from pathlib import Path

from finance_builder_base import ResourceKind
from finance_builder_base.evaluation import (
    AcceptanceRule,
    EvaluationConclusion,
    EvaluationProgress,
    EvaluationReport,
    MetricValue,
    RuleGroup,
    RuleOperator,
    SampleResult,
)
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationMode,
    EvaluationPack,
    EvaluationSample,
    EvaluationSampleEvidence,
    EvidenceLevel,
    EvidenceStatus,
    MetricKind,
    MetricSpec,
    SampleExecutionStatus,
    SandboxEvaluationRequest,
    build_evaluation_report,
    freeze_evaluation_snapshot,
    prepare_evaluation_pack,
    write_evaluation_evidence,
)
from tests.candidate_backend.support import descriptor


def _request() -> SandboxEvaluationRequest:
    query = "private customer question must not enter the report"
    return SandboxEvaluationRequest(
        request_id="evaluation-1",
        mode=EvaluationMode.TUNING,
        actor_id="evaluation-agent",
        purpose="pre-release gate",
        pack=EvaluationPack(
            pack_id="wealth-eval",
            version="1.0",
            partitions=(
                DatasetPartition(
                    dataset_id="regression-v1",
                    version="1.0",
                    purpose=DatasetPurpose.REGRESSION,
                    source=CandidateResourceRef(
                        kind=ResourceKind.WORKSPACE_PATH,
                        locator="datasets/regression.jsonl",
                        sha256="a" * 64,
                    ),
                    authorized=True,
                    authorization_id="fixture",
                    declared_sample_count=1,
                    samples=(
                        EvaluationSample(
                            sample_id="sample-1",
                            payload={"query": query},
                            expected="wealth",
                            expected_path="/result/intent",
                        ),
                    ),
                ),
            ),
            metrics=(MetricSpec(name="accuracy", kind=MetricKind.ACCURACY),),
            acceptance=RuleGroup(
                logic="all",
                rules=(
                    AcceptanceRule(
                        metric="accuracy",
                        operator=RuleOperator.GTE,
                        threshold=0.9,
                    ),
                ),
            ),
        ),
    )


def test_writer_separates_evidence_levels_and_omits_raw_query(tmp_path: Path) -> None:
    request = _request()
    snapshot = freeze_evaluation_snapshot(request, descriptor())
    prepared = prepare_evaluation_pack(request.pack)
    base_report = EvaluationReport(
        snapshot_id="base-snapshot",
        conclusion=EvaluationConclusion.PASSED,
        progress=EvaluationProgress(completed=1, total=1),
        samples=(SampleResult(case_id="sample-1", actual={}, matched=True),),
        metrics=(MetricValue(name="accuracy", value=1.0, numerator=1, denominator=1),),
    )
    sample = EvaluationSampleEvidence(
        sample_id="sample-1",
        dataset_id="regression-v1",
        status=SampleExecutionStatus.PASSED,
        matched=True,
        duration_ms=8,
        input_digest=prepared.samples["sample-1"].content_digest(),
        expected_digest="b" * 64,
        actual_digest="c" * 64,
        response_ref=CandidateResourceRef(
            kind=ResourceKind.WORKSPACE_PATH,
            locator="evaluation/responses/sample-1.json",
            sha256="c" * 64,
        ),
    )
    report = build_evaluation_report(snapshot, base_report, (sample,))

    artifacts = write_evaluation_evidence(tmp_path, report)
    document = json.loads(artifacts.report_path.read_text(encoding="utf-8"))
    serialized = json.dumps(document, ensure_ascii=False)

    assert "private customer question" not in serialized
    levels = {item["level"]: item for item in document["evidenceLevels"]}
    assert levels[EvidenceLevel.SANDBOX.value]["executed"] is True
    assert levels[EvidenceLevel.SANDBOX.value]["status"] == EvidenceStatus.PASSED.value
    assert levels[EvidenceLevel.REPLAY.value]["executed"] is False
    assert artifacts.report_ref.locator == "evaluation/evaluation-report.json"
    assert artifacts.snapshot_ref.locator == "evaluation/evaluation-snapshot.json"


def test_incomplete_execution_is_indeterminate() -> None:
    request = _request()
    snapshot = freeze_evaluation_snapshot(request, descriptor())
    base_report = EvaluationReport(
        snapshot_id="base-snapshot",
        conclusion=EvaluationConclusion.PASSED,
        progress=EvaluationProgress(completed=0, total=1),
        samples=(),
        metrics=(MetricValue(name="accuracy", value=None, numerator=0, denominator=1),),
    )

    report = build_evaluation_report(snapshot, base_report, (), incomplete_reason="health")

    assert report.conclusion is EvaluationConclusion.INDETERMINATE
    sandbox = next(item for item in report.evidence_levels if item.level is EvidenceLevel.SANDBOX)
    assert sandbox.status is EvidenceStatus.INDETERMINATE
