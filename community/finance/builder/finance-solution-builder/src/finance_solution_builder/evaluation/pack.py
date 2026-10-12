"""Explicit Evaluation Pack adapter for the shared deterministic engine."""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

from finance_builder_base.evaluation import (
    EvaluationCase,
    ExpectedKind,
    MetricValue,
    RuleGroup,
    SampleResult,
)

from .models import EvaluationPack, EvaluationSample, MetricKind


class ExactExpectedEvaluator:
    """Compare a declared JSON location without scene-name inference."""

    def matches(self, case: EvaluationCase, actual: Any) -> bool | None:
        if case.expected_kind is ExpectedKind.OPEN:
            return None
        response = actual.get("response") if isinstance(actual, dict) else actual
        expected_path = ""
        if isinstance(case.input, dict):
            expected_path = str(case.input.get("expectedPath", ""))
        selected = _resolve_json_pointer(response, expected_path)
        return selected == case.expected


class DeclaredMetricCalculator:
    def __init__(self, pack: EvaluationPack) -> None:
        self._specs = pack.metrics

    def calculate(
        self,
        cases: tuple[EvaluationCase, ...],
        results: tuple[SampleResult, ...],
    ) -> tuple[MetricValue, ...]:
        total = len(cases)
        complete = len(results) == total and all(result.error is None for result in results)
        values: list[MetricValue] = []
        for spec in self._specs:
            if not complete or total == 0:
                values.append(
                    MetricValue(
                        name=spec.name,
                        value=None,
                        numerator=sum(result.error is None for result in results),
                        denominator=total,
                    )
                )
                continue
            if spec.kind is MetricKind.ACCURACY:
                matched = sum(result.matched is True for result in results)
                unknown = any(result.matched is None for result in results)
                values.append(
                    MetricValue(
                        name=spec.name,
                        value=None if unknown else matched / total,
                        numerator=matched,
                        denominator=total,
                    )
                )
            elif spec.kind is MetricKind.SUCCESS_RATE:
                values.append(
                    MetricValue(
                        name=spec.name,
                        value=1.0,
                        numerator=total,
                        denominator=total,
                    )
                )
            else:
                durations = [
                    float(result.actual["durationMs"])
                    for result in results
                    if isinstance(result.actual, dict)
                    and isinstance(result.actual.get("durationMs"), (int, float))
                ]
                if len(durations) != total:
                    value = None
                else:
                    durations.sort()
                    value = durations[max(0, math.ceil(0.95 * total) - 1)]
                values.append(
                    MetricValue(
                        name=spec.name,
                        value=value,
                        numerator=len(durations),
                        denominator=total,
                    )
                )
        return tuple(values)


@dataclass(frozen=True)
class PreparedEvaluation:
    cases: tuple[EvaluationCase, ...]
    expected: ExactExpectedEvaluator
    metrics: DeclaredMetricCalculator
    acceptance: RuleGroup
    samples: dict[str, EvaluationSample]
    dataset_ids: dict[str, str]


def prepare_evaluation_pack(pack: EvaluationPack) -> PreparedEvaluation:
    """Convert one validated Pack into Base Evaluation inputs."""

    cases: list[EvaluationCase] = []
    samples: dict[str, EvaluationSample] = {}
    dataset_ids: dict[str, str] = {}
    for partition in pack.partitions:
        for sample in partition.samples:
            cases.append(
                EvaluationCase(
                    case_id=sample.sample_id,
                    input={
                        "payload": sample.payload,
                        "expectedPath": sample.expected_path,
                        "datasetId": partition.dataset_id,
                    },
                    expected=sample.expected,
                    expected_kind=sample.expected_kind,
                )
            )
            samples[sample.sample_id] = sample
            dataset_ids[sample.sample_id] = partition.dataset_id
    return PreparedEvaluation(
        cases=tuple(cases),
        expected=ExactExpectedEvaluator(),
        metrics=DeclaredMetricCalculator(pack),
        acceptance=pack.acceptance,
        samples=samples,
        dataset_ids=dataset_ids,
    )


def _resolve_json_pointer(document: Any, pointer: str) -> Any:
    if not pointer:
        return document
    current = document
    for raw_part in pointer.removeprefix("/").split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and part in current:
            current = current[part]
        elif isinstance(current, list) and part.isdigit() and int(part) < len(current):
            current = current[int(part)]
        else:
            return None
    return current
