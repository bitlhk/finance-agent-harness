"""Real source resolution/generation only, not Java admission or Runtime qualification."""
import json
import os
from pathlib import Path

import pytest

from finance_solution_builder.agentscope_service import AgentScopeServiceBuild
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.managed_runtime_support import SUPPORT_PROFILE_SOURCE
from finance_solution_builder.service import digest
from finance_solution_builder.workbench_contracts import parse_selection
from tests.test_agentscope_backend_integration import PROJECT, _selection


@pytest.mark.skipif(not os.environ.get("FH_HARNESS_REPO"), reason="explicit real Harness checkout required")
@pytest.mark.parametrize("packs", [("account-balance-readonly",), ("cashflow-readonly",),
                                   ("account-balance-readonly", "cashflow-readonly")])
def test_real_selection_and_generator_preserve_fixed_support_source(tmp_path, monkeypatch, packs):
    harness = Path(os.environ["FH_HARNESS_REPO"]).resolve()
    monkeypatch.setenv("FH_CANDIDATE_RUNTIME_IDS", "agentscope-java-2.0.3")
    selected = parse_selection(_selection(harness, "0" * 64, packs))
    solution, directory, plan = selected.resolve_details(PROJECT, harness, "managed-source-probe")
    binding = plan["runtimeBinding"]
    source = PROJECT / "templates" / SUPPORT_PROFILE_SOURCE.removeprefix("builder-template://")
    original = source.read_bytes()
    assert binding == directory["runtimeBinding"]
    assert binding["supportProfile"] == {"source": SUPPORT_PROFILE_SOURCE, "digest": "sha256:" + digest(source)}
    assert plan["evidence"]["supportProfile"] == binding["supportProfile"]
    builder = SolutionBuilder(PROJECT, harness, service_output_root=tmp_path)
    generated = AgentScopeServiceBuild(builder, solution, tmp_path / "generated", binding, None)
    generated.assemble()
    result = generated.build(verify=False)
    assert result.status == "NOT_RUN"
    assert (result.candidate_dir / "agentscope-support-source.json").read_bytes() == original
    projected = json.loads((result.candidate_dir / "agentscope-support.json").read_bytes())
    assert projected["profiles"] == json.loads(original)["profiles"]
    assert projected["providers"] == json.loads(original)["providers"]
    projection = json.loads((result.candidate_dir / "runtime-asset-projection.json").read_bytes())
    decisions = projection["observationRequirements"]
    assert projected["observationRequirements"] == [r["reference"] for r in decisions if r["status"] == "SELECTED"]
    assert {cap for r in decisions if r["status"] == "SELECTED" for cap in r["capabilityIds"]} == set(solution["capabilityBindings"])
    assert all(set(r["capabilityIds"]).isdisjoint(solution["capabilityBindings"])
               for r in decisions if r["status"] == "NOT_APPLICABLE")
    for ref in projected["observationRequirements"]:
        path = ref["source"].removeprefix("repo://")
        assert (result.candidate_dir / "assets" / path).read_bytes() == (harness / path).read_bytes()
    lock = json.loads(result.lock_path.read_bytes())
    assert lock["runtimeBinding"] == binding
    assert lock["managedTemplateDigest"] == binding["managedTemplate"]["digest"]
    assert lock["verification"] == "NOT_RUN" and lock["runtimeAdmission"] is None
    assert not (generated.output / "candidate-descriptor.json").exists()
