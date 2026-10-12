import pytest

from finance_solution_builder.workbench_contracts import STAGES, stage_view


def test_backend_plan_does_not_invent_legacy_regression_stages():
    report = {"status": "BUILT", "plannedStages": ["candidate_package", "component_installation"],
              "stages": [{"name": "candidate_package", "status": "PASS", "elapsedMillis": 120},
                         {"name": "component_installation", "status": "PASS", "elapsedMillis": 5}]}
    result = stage_view(report)
    assert [s["id"] for s in result] == report["plannedStages"]
    assert [s["status"] for s in result] == ["SUCCEEDED", "SUCCEEDED"]


def test_overall_success_never_completes_an_unobserved_stage():
    result = stage_view({"status": "BUILT", "plannedStages": ["candidate_package", "component_installation"],
                        "stages": [{"name": "candidate_package", "status": "FAIL", "elapsedMillis": 2}]})
    assert [s["status"] for s in result] == ["FAILED", "PENDING"]
    assert result[1]["elapsedMillis"] is None


def test_plan_does_not_hide_an_observed_failure():
    result = stage_view({"plannedStages": ["candidate_package"], "stages": [
        {"name": "candidate_package", "status": "PASS"},
        {"name": "observation_admission", "status": "FAIL"}]})
    assert [s["id"] for s in result] == ["candidate_package", "observation_admission"]
    assert result[1]["status"] == "FAILED"


@pytest.mark.parametrize("plan", [None, [], {}, "candidate_package", ["unknown"],
                                  ["candidate_package", "candidate_package"], [["candidate_package"]]])
def test_absent_or_invalid_plan_preserves_legacy_projection(plan):
    assert [s["id"] for s in stage_view({"plannedStages": plan})] == list(STAGES)


def test_legacy_python_keeps_its_actual_stage_contract():
    result = stage_view({"runtimeLanguage": "python", "stages": []})
    assert result[-1]["id"] == "python_dependency_check"
    assert all(s["status"] == "PENDING" for s in result)
