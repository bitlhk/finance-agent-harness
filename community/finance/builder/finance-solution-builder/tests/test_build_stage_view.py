from finance_solution_builder.workbench_contracts import stage_view


def test_installation_and_observation_failures_remain_visible():
    report = {"stages": [
        {"name": "harness_contracts", "status": "PASS"},
        {"name": "harness_tests_install", "status": "PASS"},
        {"name": "candidate_package", "status": "PASS"},
        {"name": "component_installation", "status": "PASS"},
        {"name": "observation_admission", "status": "FAIL"},
    ]}
    stages = stage_view(report)
    assert [stage["id"] for stage in stages] == [item["name"] for item in report["stages"]]
    assert stages[-1]["status"] == "FAILED"
    assert stages[-1]["error"]["stageId"] == "observation_admission"
