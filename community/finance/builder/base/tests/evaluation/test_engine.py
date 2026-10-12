from __future__ import annotations

from typing import Any

from finance_builder_base.evaluation import (
    AcceptanceRule,
    CancellationToken,
    EvaluationCase,
    EvaluationConclusion,
    EvaluationEngine,
    ExpectedKind,
    MetricValue,
    RuleGroup,
    RuleOperator,
    compare_metric,
    evaluate_rules,
)
from finance_builder_base.evaluation.engine import _compare, _evaluate_rules


class Invoker:
    def __init__(self, token: CancellationToken | None = None) -> None:
        self.token = token

    def invoke(self, case: EvaluationCase) -> Any:
        if self.token is not None:
            self.token.cancel()
        return case.input


class Expected:
    def matches(self, case: EvaluationCase, actual: Any) -> bool | None:
        if case.expected_kind is ExpectedKind.OPEN:
            return None
        return actual == case.expected


class Accuracy:
    def calculate(self, cases, results):
        closed = [result for result in results if result.matched is not None]
        numerator = sum(result.matched is True for result in closed)
        denominator = len(closed)
        return (
            MetricValue(
                name="accuracy",
                value=None if denominator == 0 else numerator / denominator,
                numerator=numerator,
                denominator=denominator,
            ),
        )


def _rule(threshold: float = 0.8) -> RuleGroup:
    return RuleGroup(
        logic="all",
        rules=(
            AcceptanceRule(
                metric="accuracy", operator=RuleOperator.GTE, threshold=threshold
            ),
        ),
    )


def test_fixed_loop_produces_traceable_pass_and_progress() -> None:
    progress = []
    cases = [
        EvaluationCase(
            case_id="c1", input="wealth", expected="wealth", expected_kind=ExpectedKind.CLOSED
        ),
        EvaluationCase(
            case_id="c2", input="loan", expected="loan", expected_kind=ExpectedKind.CLOSED
        ),
    ]
    report = EvaluationEngine().run(
        cases,
        invoker=Invoker(),
        expected=Expected(),
        metrics=Accuracy(),
        acceptance=_rule(),
        on_progress=progress.append,
    )
    assert report.conclusion is EvaluationConclusion.PASSED
    assert [(item.completed, item.total) for item in progress] == [(1, 2), (2, 2)]
    assert report.metrics[0].denominator == 2


def test_cancel_keeps_partial_results() -> None:
    token = CancellationToken()
    cases = [
        EvaluationCase(case_id=str(index), input=index, expected=index, expected_kind="closed")
        for index in range(3)
    ]
    report = EvaluationEngine().run(
        cases,
        invoker=Invoker(token),
        expected=Expected(),
        metrics=Accuracy(),
        acceptance=_rule(),
        cancellation=token,
    )
    assert report.conclusion is EvaluationConclusion.CANCELLED
    assert report.progress.completed == 1
    assert len(report.samples) == 1


def test_no_computable_evidence_is_indeterminate() -> None:
    report = EvaluationEngine().run(
        [EvaluationCase(case_id="open", input="x", expected="semantic", expected_kind="open")],
        invoker=Invoker(),
        expected=Expected(),
        metrics=Accuracy(),
        acceptance=_rule(),
    )
    assert report.conclusion is EvaluationConclusion.INDETERMINATE
    assert report.metrics[0].denominator == 0


def test_rule_groups_support_all_and_any() -> None:
    metrics = (MetricValue(name="accuracy", value=0.9, numerator=9, denominator=10),)

    class FixedMetrics:
        def calculate(self, cases, results):
            return metrics

    acceptance = RuleGroup(
        logic="any",
        rules=(
            AcceptanceRule(metric="missing", operator=">=", threshold=1),
            AcceptanceRule(metric="accuracy", operator=">", threshold=0.8),
        ),
    )
    report = EvaluationEngine().run(
        [EvaluationCase(case_id="c", input="x", expected="x", expected_kind="closed")],
        invoker=Invoker(),
        expected=Expected(),
        metrics=FixedMetrics(),
        acceptance=acceptance,
    )
    assert report.conclusion is EvaluationConclusion.PASSED


def test_public_rule_api_preserves_legacy_results_and_is_repeatable() -> None:
    rule = AcceptanceRule(metric="accuracy", operator=">=", threshold=0.8)
    metric = MetricValue(name="accuracy", value=0.9, numerator=9, denominator=10)
    metrics = {"accuracy": metric}
    group = RuleGroup(
        logic="all",
        rules=(rule,),
        groups=(RuleGroup(logic="any", rules=(rule,)),),
    )

    assert compare_metric(0.9, rule) is _compare(0.9, rule) is True
    first = evaluate_rules(group, metrics)
    second = evaluate_rules(group, metrics)
    assert first is second is _evaluate_rules(group, metrics) is True
    assert metrics == {"accuracy": metric}
