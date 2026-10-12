from types import SimpleNamespace
import pytest
from finance_solution_builder.workbench import Jobs


@pytest.mark.parametrize("reason", ["free", "count"])
def test_server_rejects_before_queueing_without_deleting_any_history(tmp_path, monkeypatch, reason):
    jobs = Jobs(tmp_path, tmp_path, protect_disk=True)
    body = {"capabilities": ["account.balance.query"], "runtime": "openjiuwen-java-0.1.15",
            "model": "configured-model", "connection": "local-simulator"}
    if reason == "free":
        monkeypatch.setattr("finance_solution_builder.workbench.shutil.disk_usage", lambda _: SimpleNamespace(free=1024))
        code = "INSUFFICIENT_BUILD_STORAGE"
    else:
        jobs.jobs = {str(i): {"status": "STOPPED"} for i in range(20)}
        code = "BUILD_RETENTION_LIMIT_REACHED"
    original = dict(jobs.jobs)
    try:
        with pytest.raises(ValueError, match=code):
            jobs.create(body, "owner")
        assert jobs.jobs == original
        assert not list(jobs.inputs.iterdir())
    finally:
        jobs.close()
