from __future__ import annotations

from finance_builder_base import ErrorDetail, ResourceKind
from finance_builder_base.evaluation import SampleResult
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import (
    EvaluationSampleEvidence,
    FindingResponsibility,
    SampleExecutionStatus,
    project_findings,
    responsibility_from_error,
)


def _sample_evidence(*, error: ErrorDetail | None = None) -> EvaluationSampleEvidence:
    return EvaluationSampleEvidence(
        sample_id="sample-1",
        dataset_id="regression-v1",
        status=SampleExecutionStatus.FAILED,
        matched=False if error is None else None,
        duration_ms=10,
        input_digest="a" * 64,
        expected_digest="b" * 64,
        actual_digest="c" * 64 if error is None else None,
        response_ref=(
            CandidateResourceRef(
                kind=ResourceKind.WORKSPACE_PATH,
                locator="evaluation/responses/sample-1.json",
                sha256="c" * 64,
            )
            if error is None
            else None
        ),
        error_stage="candidate-invoke" if error else None,
        error=error,
    )


def test_wrong_route_becomes_candidate_finding_without_input_content() -> None:
    sample = _sample_evidence()

    findings = project_findings(
        (SampleResult(case_id="sample-1", actual={}, matched=False),),
        (sample,),
    )

    assert len(findings) == 1
    assert findings[0].responsibility is FindingResponsibility.CANDIDATE
    assert findings[0].sample_ids == ("sample-1",)
    assert "query" not in findings[0].summary.lower()
    assert findings[0].evidence_refs == (sample.response_ref,)


def test_error_prefixes_map_to_distinct_responsibility_boundaries() -> None:
    assert responsibility_from_error("provider.connection.failed") is (
        FindingResponsibility.PROVIDER_HARNESS_ENVIRONMENT
    )
    assert responsibility_from_error("asset.compatibility.failed") is (
        FindingResponsibility.SOLUTION_ASSETS
    )
    assert responsibility_from_error("evaluation.data.invalid") is (FindingResponsibility.DATA)
    assert responsibility_from_error("evaluation.rule.unknown_metric") is (
        FindingResponsibility.ACCEPTANCE_RULE
    )


def test_findings_do_not_mutate_sample_evidence() -> None:
    error = ErrorDetail(code="provider.connection.failed", message="Provider unavailable.")
    sample = _sample_evidence(error=error)
    before = sample.model_dump(mode="json")

    project_findings(
        (SampleResult(case_id="sample-1", error=error),),
        (sample,),
    )

    assert sample.model_dump(mode="json") == before


def test_specific_sample_error_is_not_hidden_by_base_engine_error() -> None:
    provider_error = ErrorDetail(
        code="provider.connection.failed",
        message="Provider unavailable.",
    )
    sample = _sample_evidence(error=provider_error)

    findings = project_findings(
        (
            SampleResult(
                case_id="sample-1",
                error=ErrorDetail(
                    code="evaluation.sample_failed",
                    message="Sample invocation failed.",
                ),
            ),
        ),
        (sample,),
    )

    assert findings[0].responsibility is (FindingResponsibility.PROVIDER_HARNESS_ENVIRONMENT)
