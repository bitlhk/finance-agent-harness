"""Evaluation input, result, metric and acceptance-rule contracts."""

from __future__ import annotations

from enum import Enum
from typing import Any

from pydantic import Field, SerializeAsAny, model_validator

from finance_builder_base.contracts import ContractModel, ErrorDetail, ExtensionModel


class ExpectedKind(str, Enum):
    CLOSED = "closed"
    OPEN = "open"


class EvaluationConclusion(str, Enum):
    PASSED = "passed"
    FAILED = "failed"
    INDETERMINATE = "indeterminate"
    CANCELLED = "cancelled"


class RuleOperator(str, Enum):
    GT = ">"
    GTE = ">="
    LT = "<"
    LTE = "<="
    EQ = "=="


class EvaluationCase(ContractModel):
    case_id: str = Field(min_length=1)
    input: Any
    expected: Any
    expected_kind: ExpectedKind
    extension: SerializeAsAny[ExtensionModel] | None = None


class SampleResult(ContractModel):
    case_id: str = Field(min_length=1)
    actual: Any = None
    matched: bool | None = None
    error: ErrorDetail | None = None


class MetricValue(ContractModel):
    name: str = Field(min_length=1)
    value: float | None
    numerator: int = Field(ge=0)
    denominator: int = Field(ge=0)

    @model_validator(mode="after")
    def validate_fraction(self) -> MetricValue:
        if self.numerator > self.denominator:
            raise ValueError("metric numerator cannot exceed denominator")
        if self.denominator == 0 and self.value is not None:
            raise ValueError("metric with no evidence cannot have a value")
        return self


class AcceptanceRule(ContractModel):
    metric: str = Field(min_length=1)
    operator: RuleOperator
    threshold: float


class RuleGroup(ContractModel):
    logic: str = Field(pattern=r"^(all|any)$")
    rules: tuple[AcceptanceRule, ...] = ()
    groups: tuple[RuleGroup, ...] = ()

    @model_validator(mode="after")
    def require_children(self) -> RuleGroup:
        if not self.rules and not self.groups:
            raise ValueError("rule group requires at least one child")
        return self


class EvaluationProgress(ContractModel):
    completed: int = Field(ge=0)
    total: int = Field(ge=0)


class EvaluationReport(ContractModel):
    snapshot_id: str = Field(min_length=1)
    conclusion: EvaluationConclusion
    progress: EvaluationProgress
    samples: tuple[SampleResult, ...]
    metrics: tuple[MetricValue, ...]
    bad_cases: SerializeAsAny[ExtensionModel] | None = None
