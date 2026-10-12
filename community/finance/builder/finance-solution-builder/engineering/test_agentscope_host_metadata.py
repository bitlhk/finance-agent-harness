"""Actual Host declarations and original Harness assets; no admission test doubles."""
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path

import pytest

from finance_solution_builder.component_assembly import AssemblyRejected, ComponentAssembly
from finance_solution_builder.managed_runtime_support import declarations, freeze_binding, supported_selection

PROJECT = Path(__file__).resolve().parents[1]
TEMPLATE = PROJECT / "templates/agentscope-query-service"
SOURCE = "builder-template://agentscope-query-service/agentscope-support.v0.1.json"


@pytest.fixture
def harness():
    path = os.environ.get("FH_HARNESS_REPO")
    if not path:
        pytest.skip("explicit unchanged Harness checkout required")
    return Path(path).resolve()


def selection(packs):
    return [{"pack": {"id": pack, "version": "0.1.0"},
             "provider": {"id": "non-ai-banking-reference", "version": "0.1.0-SNAPSHOT"}} for pack in packs]


@pytest.mark.parametrize("packs", [["account-balance-readonly"], ["cashflow-readonly"],
                                  ["account-balance-readonly", "cashflow-readonly"]])
def test_real_host_schema_and_original_packs(harness, packs):
    host = json.loads((TEMPLATE / "host-support.v0.1.json").read_bytes())
    assembly = ComponentAssembly(harness)
    result = assembly.select(selection(packs), host=host, connection_kind="synthetic-bank-http.v1")
    assert {pack["id"] for pack in result["capabilityPackRefs"]} == set(packs)
    for unknown in ("modelPolicy", "admission"):
        invalid = deepcopy(host)
        invalid[unknown] = {}
        with pytest.raises(AssemblyRejected, match="ASSEMBLY_HOST_DECLARATION_INVALID"):
            assembly.select(selection(packs), host=invalid, connection_kind="synthetic-bank-http.v1")


def test_managed_support_is_independent_not_a_business_or_cli_rewrite(harness):
    managed = json.loads((TEMPLATE / "agentscope-support.v0.1.json").read_bytes())
    cli = json.loads((harness / "integrations/agentscope-java/reference/support.json").read_bytes())
    profiles = {p["profile"]["id"]: p for p in managed["profiles"]}
    assert set(profiles) == {"local-read-only", "local-simulator-task"}
    assert profiles["local-read-only"]["purposes"] == ["LOCAL_READONLY_DESCRIPTION"]
    assert all(profile["purposes"] == ["customer-self-service"] for profile in cli["profiles"])
    original = next(p for p in cli["profiles"] if p["profile"]["id"] == "local-read-only")
    expected = deepcopy(original)
    expected["purposes"] = ["LOCAL_READONLY_DESCRIPTION"]
    expected["domainProfiles"] = ["customer-self-service", "local-read-only"]
    assert profiles["local-read-only"] == expected
    task = profiles["local-simulator-task"]
    assert task["purposes"] == ["LOCAL_SIMULATOR_TASK"]
    assert task["principalTypes"] == ["CUSTOMER"]
    assert set(task["allowedCapabilityIds"]) == set(expected["allowedCapabilityIds"]) | {"transfer.internal.create"}
    assert "transfer.internal.create" not in expected["allowedCapabilityIds"]
    assert cli["operation"] == "QUERY"
    assert managed["operation"] == "QUERY_TASK"
    for key in ("family", "language", "sdkVersion", "modelAttempts", "toolAttempts", "stream", "fallback", "pendingToolRecovery"):
        assert managed[key] == cli[key]


def test_private_method_profile_does_not_extend_authority():
    from finance_solution_builder.method_assets import parse_content, runtime_files
    data = parse_content({"name": "Private read-only method", "content": "# Method\nRead the selected balance.",
                          "capabilityIds": ["account.balance.query"],
                          "cases": [{"name": "balance", "query": "Read my balance", "expectedTerms": ["balance"]}]})
    pack = json.loads(runtime_files("method-0123456789abcdef", "0.0.0", data)["pack.yaml"])
    managed = json.loads((TEMPLATE / "agentscope-support.v0.1.json").read_bytes())
    profile = next(p for p in managed["profiles"] if p["profile"]["id"] == pack["profile"])
    assert pack["profile"] == "local-read-only" and pack["profile"] in profile["domainProfiles"]
    assert pack["scope"] == ["Private read-only method"]
    assert pack["outOfScope"] == ["Transactions", "Granting access"]
    assert pack["capabilities"] == ["account.balance.query"]
    assert profile["principalTypes"] == ["CUSTOMER"]
    assert profile["purposes"] == ["LOCAL_READONLY_DESCRIPTION"]
    assert set(pack["capabilities"]) <= set(profile["allowedCapabilityIds"])
    assert "transfer.internal.create" not in profile["allowedCapabilityIds"]
    assert managed["operation"] == "QUERY_TASK"
    assert managed["modelAttempts"] == managed["toolAttempts"] == 1
    assert not any(managed[key] for key in ("stream", "fallback", "pendingToolRecovery"))


def test_actual_binding_selects_fixed_managed_source(harness):
    record = next(r for r in declarations(PROJECT) if r["id"] == "agentscope-java-2.0.3")
    host = next(h for h in record["hosts"] if h["id"] == "agentscope-query-service")
    assert record["supportProfileSource"] == SOURCE
    binding = freeze_binding(PROJECT, record, host, TEMPLATE / "host-support.v0.1.json", harness=harness)
    assert binding["supportProfile"] == {"source": SOURCE, "digest": "sha256:" +
        hashlib.sha256((TEMPLATE / "agentscope-support.v0.1.json").read_bytes()).hexdigest()}
    _, selected = supported_selection(PROJECT, ComponentAssembly(harness), record, host,
                                     selection(["account-balance-readonly", "cashflow-readonly"]))
    assert len(selected["capabilities"]) == 3
