"""Deterministic Evaluation loop and extension contracts."""

from finance_builder_base.evaluation.engine import (
    BadCaseAnalyzer,
    CancellationToken,
    EvaluationEngine,
    ExpectedEvaluator,
    MetricCalculator,
    SampleInvoker,
    compare_metric,
    evaluate_rules,
)
from finance_builder_base.evaluation.models import (
    AcceptanceRule,
    EvaluationCase,
    EvaluationConclusion,
    EvaluationProgress,
    EvaluationReport,
    ExpectedKind,
    MetricValue,
    RuleGroup,
    RuleOperator,
    SampleResult,
)

__all__ = [
    "AcceptanceRule",
    "BadCaseAnalyzer",
    "CancellationToken",
    "EvaluationCase",
    "EvaluationConclusion",
    "EvaluationEngine",
    "EvaluationProgress",
    "EvaluationReport",
    "ExpectedEvaluator",
    "ExpectedKind",
    "MetricCalculator",
    "MetricValue",
    "RuleGroup",
    "RuleOperator",
    "SampleInvoker",
    "SampleResult",
    "compare_metric",
    "evaluate_rules",
]
