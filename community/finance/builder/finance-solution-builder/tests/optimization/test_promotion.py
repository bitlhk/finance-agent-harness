import pytest

from finance_solution_builder.optimization import (
    MetricDirection,
    MetricObjective,
    compare_for_promotion,
)

from . import report, snapshot


OBJECTIVES = (MetricObjective(name="accuracy", direction=MetricDirection.HIGHER),)


def test_promotes_strict_improvement_under_same_frozen_conditions() -> None:
    baseline = snapshot()
    candidate = snapshot("candidate-v2", "b")
    decision = compare_for_promotion(
        baseline=baseline,
        candidate=candidate,
        baseline_report=report(baseline, accuracy=0.8, passed=False),
        candidate_report=report(candidate, accuracy=0.9, passed=True),
        objectives=OBJECTIVES,
        final_gate=False,
        holdout_authorized=False,
        holdout_already_consumed=False,
    )
    assert decision.promoted is True
    assert decision.deltas[0].improvement == pytest.approx(0.1)


def test_rejects_regression_and_incomparable_candidate() -> None:
    baseline = snapshot()
    candidate = snapshot("candidate-v2", "b")
    regression = compare_for_promotion(
        baseline=baseline,
        candidate=candidate,
        baseline_report=report(baseline, accuracy=0.9, passed=True),
        candidate_report=report(candidate, accuracy=0.8, passed=True),
        objectives=OBJECTIVES,
        final_gate=False,
        holdout_authorized=False,
        holdout_already_consumed=False,
    )
    assert regression.promoted is False
    incompatible = candidate.model_copy(update={"dataset_digest": "c" * 64})
    assert compare_for_promotion(
        baseline=baseline,
        candidate=incompatible,
        baseline_report=report(baseline, accuracy=0.8, passed=False),
        candidate_report=report(candidate, accuracy=0.9, passed=True),
        objectives=OBJECTIVES,
        final_gate=False,
        holdout_authorized=False,
        holdout_already_consumed=False,
    ).comparable is False


def test_holdout_can_only_be_consumed_once_at_final_gate() -> None:
    baseline = snapshot()
    candidate = snapshot("candidate-v2", "b")
    decision = compare_for_promotion(
        baseline=baseline,
        candidate=candidate,
        baseline_report=report(baseline, accuracy=0.8, passed=False),
        candidate_report=report(candidate, accuracy=0.9, passed=True),
        objectives=OBJECTIVES,
        final_gate=True,
        holdout_authorized=True,
        holdout_already_consumed=True,
    )
    assert decision.promoted is False
    assert "already" in decision.reason
