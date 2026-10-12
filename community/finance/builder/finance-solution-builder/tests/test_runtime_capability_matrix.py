from engineering.verify_runtime_capability_matrix import groups, managed_host
from engineering.verify_execution_history import host_command

import pytest


def test_groups_exhaust_every_non_empty_subset():
    assert len(list(groups([1, 2, 3, 4, 5, 6]))) == 63


def test_managed_host_uses_common_task_host_for_mixed_group():
    query = {"runtimeHostOptions": [
        {"hostId": "query", "profileRef": {"id": "read"}},
        {"hostId": "task", "profileRef": {"id": "task"}},
    ]}
    task = {"runtimeHostOptions": [{"hostId": "task", "profileRef": {"id": "task"}}]}
    assert managed_host([query, task])["hostId"] == "task"


def test_managed_host_honors_explicit_qualified_host():
    component = {"runtimeHostOptions": [
        {"hostId": "query", "profileRef": {"id": "read"}},
        {"hostId": "task", "profileRef": {"id": "task"}},
    ]}
    assert managed_host([component], "task")["hostId"] == "task"
    with pytest.raises(AssertionError, match="PREFERRED_HOST_DOES_NOT_SUPPORT_COMPONENTS"):
        managed_host([component], "missing")


def test_execution_history_starts_the_selected_runtime(tmp_path):
    python = host_command(tmp_path, True)
    assert python == [str(tmp_path / ".venv/bin/python"), "-I", str(tmp_path / "start_python.py")]

    target = tmp_path / "target"
    target.mkdir()
    (target / "candidate.jar").touch()
    java = host_command(tmp_path, False)
    assert java[0:2] == ["java", "-Xmx512m"]
    assert java[-2:] == ["io.github.bitlhk.finance.builder.local.LocalAgentHost", str(tmp_path)]
