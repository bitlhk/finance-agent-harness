"""Narrow, deterministic Evaluation algorithm skeleton."""

from __future__ import annotations

import json
from collections.abc import Callable, Sequence
from copy import deepcopy
from dataclasses import dataclass
from hashlib import sha256
from typing import Any, Protocol

from finance_builder_base.contracts import ErrorDetail, ExtensionModel
from finance_builder_base.evaluation.models import (
    AcceptanceRule,
    EvaluationCase,
    EvaluationConclusion,
    EvaluationProgress,
    EvaluationReport,
    MetricValue,
    RuleGroup,
    RuleOperator,
    SampleResult,
)


class SampleInvoker(Protocol):
    def invoke(self, case: EvaluationCase) -> Any: ...


class ExpectedEvaluator(Protocol):
    def matches(self, case: EvaluationCase, actual: Any) -> bool | None: ...


class MetricCalculator(Protocol):
    def calculate(
        self, cases: tuple[EvaluationCase, ...], results: tuple[SampleResult, ...]
    ) -> tuple[MetricValue, ...]: ...


class BadCaseAnalyzer(Protocol):
    def analyze(
        self, cases: tuple[EvaluationCase, ...], results: tuple[SampleResult, ...]
    ) -> ExtensionModel: ...


@dataclass
class CancellationToken:
    cancelled: bool = False

    def cancel(self) -> None:
        self.cancelled = True


def compare_metric(value: float, rule: AcceptanceRule) -> bool:
    """Compare one metric value against an acceptance rule."""

    if rule.operator is RuleOperator.GT:
        return value > rule.threshold
    if rule.operator is RuleOperator.GTE:
        return value >= rule.threshold
    if rule.operator is RuleOperator.LT:
        return value < rule.threshold
    if rule.operator is RuleOperator.LTE:
        return value <= rule.threshold
    if rule.operator is RuleOperator.EQ:
        return value == rule.threshold
    raise ValueError(f"unsupported rule operator: {rule.operator}")


def evaluate_rules(group: RuleGroup, metrics: dict[str, MetricValue]) -> bool | None:
    """Evaluate a nested rule group with deterministic three-state semantics."""

    values: list[bool | None] = []
    for rule in group.rules:
        metric = metrics.get(rule.metric)
        values.append(
            None
            if metric is None or metric.value is None
            else compare_metric(metric.value, rule)
        )
    values.extend(evaluate_rules(child, metrics) for child in group.groups)
    known = [value for value in values if value is not None]
    if group.logic == "all":
        if False in known:
            return False
        return True if len(known) == len(values) else None
    if True in known:
        return True
    return False if len(known) == len(values) else None


class EvaluationEngine:
    """Run snapshot -> samples -> metrics -> acceptance -> report in a fixed order."""

    def run(
        self,
        cases: Sequence[EvaluationCase],
        *,
        invoker: SampleInvoker,
        expected: ExpectedEvaluator,
        metrics: MetricCalculator,
        acceptance: RuleGroup,
        cancellation: CancellationToken | None = None,
        on_progress: Callable[[EvaluationProgress], None] | None = None,
        bad_case_analyzer: BadCaseAnalyzer | None = None,
    ) -> EvaluationReport:
        snapshot = tuple(deepcopy(tuple(cases)))
        snapshot_bytes = json.dumps(
            [case.model_dump(mode="json") for case in snapshot],
            sort_keys=True,
            ensure_ascii=False,
        ).encode()
        snapshot_id = sha256(snapshot_bytes).hexdigest()
        token = cancellation or CancellationToken()
        results: list[SampleResult] = []
        total = len(snapshot)

        for case in snapshot:
            if token.cancelled:
                break
            try:
                actual = invoker.invoke(case)
                matched = expected.matches(case, actual)
                result = SampleResult(case_id=case.case_id, actual=actual, matched=matched)
            except Exception as exc:  # noqa: BLE001 - converted at the public boundary
                result = SampleResult(
                    case_id=case.case_id,
                    error=ErrorDetail(
                        code="evaluation.sample_failed",
                        message=f"sample invocation failed: {type(exc).__name__}",
                    ),
                )
            results.append(result)
            if on_progress is not None:
                on_progress(EvaluationProgress(completed=len(results), total=total))

        result_tuple = tuple(results)
        metric_values = metrics.calculate(snapshot, result_tuple)
        if token.cancelled:
            conclusion = EvaluationConclusion.CANCELLED
        else:
            decision = evaluate_rules(
                acceptance, {metric.name: metric for metric in metric_values}
            )
            if decision is True:
                conclusion = EvaluationConclusion.PASSED
            elif decision is False:
                conclusion = EvaluationConclusion.FAILED
            else:
                conclusion = EvaluationConclusion.INDETERMINATE
        bad_cases = (
            None
            if bad_case_analyzer is None
            else bad_case_analyzer.analyze(snapshot, result_tuple)
        )
        return EvaluationReport(
            snapshot_id=snapshot_id,
            conclusion=conclusion,
            progress=EvaluationProgress(completed=len(results), total=total),
            samples=result_tuple,
            metrics=metric_values,
            bad_cases=bad_cases,
        )


# Compatibility aliases for 0.1.x callers. Both names point to the single
# public implementation and must not grow independent behavior.
_compare = compare_metric
_evaluate_rules = evaluate_rules
