from datetime import datetime, timezone

import pytest

from finance_solution_builder.workbench import trusted_business_time_environment
from finance_solution_builder import workbench
from finance_solution_builder.service import digest, write_json
from finance_solution_builder.workbench_api import public_error


def test_workbench_derives_business_date_from_trusted_host_clock():
    value = trusted_business_time_environment(
        {"FH_WORKBENCH_BUSINESS_TIME_ZONE": "Asia/Shanghai"},
        datetime(2026, 9, 16, 16, 30, tzinfo=timezone.utc),
    )
    assert value == {
        "FH_BUSINESS_DATE": "2026-09-17",
        "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai",
        "FH_BUSINESS_DATE_SOURCE": "WORKBENCH_HOST_CLOCK",
    }


def test_workbench_preserves_complete_controlled_business_time():
    value = trusted_business_time_environment({
        "FH_BUSINESS_DATE": "2026-09-15",
        "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai",
        "FH_BUSINESS_DATE_SOURCE": "controlled-evaluation-host",
    })
    assert value["FH_BUSINESS_DATE"] == "2026-09-15"
    assert value["FH_BUSINESS_DATE_SOURCE"] == "controlled-evaluation-host"


@pytest.mark.parametrize("environment", [
    {"FH_BUSINESS_DATE": "2026-09-15"},
    {"FH_BUSINESS_DATE": "bad", "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai", "FH_BUSINESS_DATE_SOURCE": "host"},
    {"FH_WORKBENCH_BUSINESS_TIME_ZONE": "Not/A_Zone"},
])
def test_workbench_rejects_incomplete_or_invalid_business_time(environment):
    with pytest.raises(ValueError, match="BUSINESS_TIME_CONFIGURATION"):
        trusted_business_time_environment(environment)


@pytest.mark.parametrize("code", [
    "BUSINESS_TIME_CONFIGURATION_INCOMPLETE", "BUSINESS_TIME_CONFIGURATION_INVALID",
])
def test_business_time_error_translation_is_exact_and_does_not_expose_values(code):
    assert public_error(ValueError(code)) == (503, {"code": code, "retryable": False})
    for error in (RuntimeError(code), ValueError(code + ": /private/operator-secret"),
                  ValueError("unknown " + code), ValueError("BUSINESS_TIME_CONFIGURATION_UNKNOWN")):
        assert public_error(error) == (503, {"code": "OPERATION_FAILED", "retryable": False})


@pytest.mark.parametrize("environment,code", [
    ({"FH_BUSINESS_DATE": "2026-09-20", "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai",
      "FH_BUSINESS_TIME_SOURCE": "ISOLATED_ACCEPTANCE"}, "BUSINESS_TIME_CONFIGURATION_INCOMPLETE"),
    ({"FH_BUSINESS_DATE": "private-invalid-date", "FH_BUSINESS_TIME_ZONE": "Asia/Shanghai",
      "FH_BUSINESS_DATE_SOURCE": "controlled-host"}, "BUSINESS_TIME_CONFIGURATION_INVALID"),
    ({"FH_BUSINESS_DATE": "2026-09-20", "FH_BUSINESS_TIME_ZONE": "Not/A_Zone",
      "FH_BUSINESS_DATE_SOURCE": "controlled-host"}, "BUSINESS_TIME_CONFIGURATION_INVALID"),
], ids=["run3-wrong-source-name", "invalid-date", "invalid-zone"])
def test_jobs_start_rejects_business_time_before_spawning(tmp_path, monkeypatch, environment, code):
    for name in ("FH_BUSINESS_DATE", "FH_BUSINESS_TIME_ZONE", "FH_BUSINESS_DATE_SOURCE",
                 "FH_BUSINESS_TIME_SOURCE", "FH_WORKBENCH_BUSINESS_TIME_ZONE"):
        monkeypatch.delenv(name, raising=False)
    for name in workbench.ENV_KEYS:
        monkeypatch.setenv(name, "configured")
    monkeypatch.setenv("FH_MODEL_MODE", "LIVE")
    for name, value in environment.items():
        monkeypatch.setenv(name, value)
    monkeypatch.setattr(workbench, "spawn_process", lambda *args, **kwargs: pytest.fail("spawned"))
    jobs = workbench.Jobs(tmp_path, tmp_path)
    try:
        key = "agent-business-time"
        jobs.jobs[key] = {"id": key, "owner": "owner", "status": "BUILT", "error": None,
                          "capabilities": []}
        root = jobs.path(key)
        lock = root / "candidate/service-lock.json"
        lock.parent.mkdir(parents=True)
        write_json(lock, {"buildId": key, "verification": "COMPILED", "files": {}})
        lock_digest = digest(lock)
        write_json(root / "build-report.json", {"status": "BUILT", "lockSha256": lock_digest})
        with pytest.raises(ValueError, match="^" + code + "$") as caught:
            jobs.start(key, "owner", expected_artifact="sha256:" + lock_digest)
        assert public_error(caught.value) == (503, {"code": code, "retryable": False})
        assert jobs.jobs[key]["status"] == "BUILT" and jobs.jobs[key]["error"] is None
        assert not jobs.processes and not jobs.tokens
        assert not (root / "instance.log").exists()
        assert not (root / "candidate/instance.json").exists()
        assert digest(lock) == lock_digest
    finally:
        jobs.close()
