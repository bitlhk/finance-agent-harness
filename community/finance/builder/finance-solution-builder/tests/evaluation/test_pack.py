from __future__ import annotations

from finance_builder_base import ResourceKind
from finance_builder_base.evaluation import (
    AcceptanceRule,
    EvaluationCase,
    RuleGroup,
    RuleOperator,
    SampleResult,
)
from finance_solution_builder.candidate_backend import CandidateResourceRef
from finance_solution_builder.evaluation import (
    DatasetPartition,
    DatasetPurpose,
    EvaluationPack,
    EvaluationSample,
    MetricKind,
    MetricSpec,
    prepare_evaluation_pack,
)


def _pack() -> EvaluationPack:
    sample = EvaluationSample(
        sample_id="sample-1",
        payload={"query": "demo"},
        expected="wealth",
        expected_path="/result/intent",
    )
    return EvaluationPack(
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
                samples=(sample,),
            ),
        ),
        metrics=(
            MetricSpec(name="accuracy", kind=MetricKind.ACCURACY),
            MetricSpec(name="success_rate", kind=MetricKind.SUCCESS_RATE),
            MetricSpec(name="latency_p95_ms", kind=MetricKind.LATENCY_P95_MS),
        ),
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
    )


def test_adapter_builds_base_cases_and_matches_json_pointer() -> None:
    prepared = prepare_evaluation_pack(_pack())
    case = prepared.cases[0]

    assert isinstance(case, EvaluationCase)
    assert case.case_id == "sample-1"
    assert (
        prepared.expected.matches(
            case,
            {"response": {"result": {"intent": "wealth"}}, "durationMs": 12},
        )
        is True
    )


def test_metrics_are_indeterminate_when_any_sample_did_not_execute() -> None:
    prepared = prepare_evaluation_pack(_pack())
    results = (
        SampleResult(case_id="sample-1", error={"code": "evaluation.failed", "message": "failed"}),
    )

    metrics = prepared.metrics.calculate(prepared.cases, results)

    assert {metric.name: metric.value for metric in metrics} == {
        "accuracy": None,
        "success_rate": None,
        "latency_p95_ms": None,
    }


def test_metrics_use_captured_results_deterministically() -> None:
    prepared = prepare_evaluation_pack(_pack())
    results = (
        SampleResult(
            case_id="sample-1",
            actual={"response": {"result": {"intent": "wealth"}}, "durationMs": 12},
            matched=True,
        ),
    )

    first = prepared.metrics.calculate(prepared.cases, results)
    second = prepared.metrics.calculate(prepared.cases, results)

    assert first == second
    assert {metric.name: metric.value for metric in first} == {
        "accuracy": 1.0,
        "success_rate": 1.0,
        "latency_p95_ms": 12.0,
    }
