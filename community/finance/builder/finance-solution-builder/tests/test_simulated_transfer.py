
import pytest

from finance_solution_builder import simulated_transfer
from finance_solution_builder.service import valid_capability_set, validate_service, solution_for


def test_pilot_does_not_expand_readonly_selection():
    assert not valid_capability_set(["transfer.internal.create"])
    assert not valid_capability_set(["account.balance.query", "transfer.internal.create"])
    with pytest.raises(ValueError, match="READONLY_SELECTION_INVALID"):
        solution_for(["transfer.internal.create"], "bad-readonly")


def test_retail_task_pack_still_rejected_by_stable_recipe():
    value = solution_for(["account.balance.query"], "readonly")
    value["capabilityPackRefs"][0]["id"] = "retail-banking-basics"
    with pytest.raises(ValueError, match="SERVICE_CAPABILITY_UNSUPPORTED"):
        validate_service(value)


def test_existing_candidate_is_never_overwritten(tmp_path):
    marker = tmp_path / "retained.txt"
    marker.write_text("old candidate")
    with pytest.raises(ValueError, match="OUTPUT_EXISTS"):
        simulated_transfer.build(tmp_path, tmp_path, "not-a-command")
    assert marker.read_text() == "old candidate"


def test_release_build_requires_clean_sources_before_creating_output(tmp_path, monkeypatch):
    monkeypatch.setattr(simulated_transfer, "revision", lambda root: {"commit": "fixed", "worktreeDirty": True})
    output = tmp_path / "new"
    with pytest.raises(ValueError, match="CLEAN_SOURCES_REQUIRED"):
        simulated_transfer.build(tmp_path, output, "not-a-command")
    assert not output.exists()
