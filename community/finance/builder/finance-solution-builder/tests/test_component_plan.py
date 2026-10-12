from copy import deepcopy
import json
from pathlib import Path
import shutil

import pytest

from finance_solution_builder.component_assembly import AssemblyRejected
from finance_solution_builder.component_plan import host_support_files, qualify_solution, support_file_for
from finance_solution_builder.builder import SolutionBuilder
from finance_solution_builder.method_assets import runtime_reference
from finance_solution_builder.service import build_service, solution_for
from tests.test_component_assembly import COMBINATIONS, FIXTURE, PROJECT, root


def test_internal_control_smart_audit_host_is_a_declared_in_process_query_host():
    support_path = support_file_for(PROJECT, "internal-control")
    support = json.loads(support_path.read_text())

    assert support_path in host_support_files(PROJECT)
    assert support["operations"] == ["QUERY"]
    assert support["connectionKinds"] == ["in-process-smart-audit.v1"]


@pytest.mark.parametrize("caps", COMBINATIONS)
def test_qualified_build_plan_preserves_all_legacy_selections(root, caps):
    solution = solution_for(caps, "managed-plan")
    original = deepcopy(solution)
    plan = qualify_solution(PROJECT, root, solution)
    assert solution == original
    assert plan["capabilityBindings"] == solution["capabilityBindings"]
    assert plan["domainPackRefs"] == solution["domainPackRefs"]
    assert plan["managedTemplate"]["source"] == "builder-template://local-readonly-service"
    assert plan["runtime"]["templateRef"].startswith("maven://")
    assert plan["evidence"]["installation"] == "NOT_OBSERVED"


def test_private_method_replaces_default_without_requiring_its_files(root):
    snapshot = {"assetId": "method-0123456789abcdef", "version": "0.1.0",
                "installationDigest": "sha256:" + "1" * 64, "data": {"capabilityIds": ["wealth.facts.describe"]}}
    ref = runtime_reference(snapshot)
    solution = solution_for(["wealth.facts.describe"], "private", ref, snapshot["data"]["capabilityIds"])
    shutil.rmtree(root / "domain-packs/wealth/shared-facts")
    plan = qualify_solution(PROJECT, root, solution, snapshot)
    assert plan["domainPackRefs"] == [ref]
    assert not any(k.startswith("domain-packs/wealth/shared-facts") for k in plan["evidence"]["files"])
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_METHOD_SNAPSHOT_MISMATCH"):
        qualify_solution(PROJECT, root, solution)


def test_client_host_claim_cannot_supply_a_missing_managed_template(root, tmp_path):
    solution = solution_for(["account.balance.query"], "forged-host")
    solution["hostSupport"] = {"features": ["query.v1", "task.v1", "trusted-grants.v1"]}
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_MANAGED_HOST_REQUIRED"):
        qualify_solution(tmp_path, root, solution)


def test_cli_build_cannot_bypass_admission_or_write_output(root, tmp_path):
    (root / "providers/non-ai-banking/assembly.v0.1.json").unlink()
    output_root = tmp_path / "generated"
    builder = SolutionBuilder(PROJECT, root, service_output_root=output_root)
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_SOURCE_UNAVAILABLE"):
        build_service(builder, solution_for(["account.balance.query"], "rejected"), output_root / "agent", False)
    assert not output_root.exists()


def test_build_detects_runtime_claim_instead_of_trusting_frontend(root):
    solution = solution_for(["account.balance.query"], "wrong-runtime")
    solution["runtime"]["templateRef"] = "maven://fake:sdk:99"
    with pytest.raises(AssemblyRejected, match="ASSEMBLY_RUNTIME_UNSUPPORTED"):
        qualify_solution(PROJECT, root, solution)


def test_routing_assets_are_discovered_from_installed_pack(root):
    description = root / "routing/capability-descriptions/account-balance-readonly.v0.1.json"
    terminology = root / "routing/terminology/demo-bank-zh-cn.v0.1.json"
    description.parent.mkdir(parents=True)
    terminology.parent.mkdir(parents=True)
    description.write_text('{"fixture":"description"}')
    terminology.write_text('{"fixture":"terminology"}')
    solution = solution_for(["account.balance.query"], "routing-assets")
    plan = qualify_solution(PROJECT, root, solution)
    assert plan["routingAssets"] == {
        "descriptionRefs": ["repo://routing/capability-descriptions/account-balance-readonly.v0.1.json"],
        "terminologyRefs": ["repo://routing/terminology/demo-bank-zh-cn.v0.1.json"]}
