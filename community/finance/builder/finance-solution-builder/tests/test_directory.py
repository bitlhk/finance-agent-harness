import json
from pathlib import Path

import pytest
import yaml

from finance_solution_builder.directory import describe_directory, resolve_directory
from finance_solution_builder.service import solution_for
from finance_solution_builder.workbench_contracts import ApiError, catalog, parse_selection, preflight
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.workbench import Jobs

PROJECT = Path(__file__).resolve().parents[1]


def selection(harness, **changes):
    cat = catalog(harness)
    ref = next(m["modelRef"] for m in cat["models"] if m["bindingStatus"] not in {"DISABLED", "PROTOCOL_UNSUPPORTED"})
    body = dict(extension_type="finance-workbench.selection", schema_version="0.4", runtimeId="openjiuwen-java-0.1.15",
                applicationTemplateId="banking-readonly-web-v1", bankConnectionId="local-simulator", capabilityIds=["account.balance.query"],
                selectedModelRefs=[ref], defaultModelRef=ref, modelCatalogRevision=cat["modelCatalogRevision"],
                audienceId="CUSTOMER", entryMode="COMPONENTS", scenarioTemplateId=None,
                componentIds=["account.balance.query"], directoryRevision=cat["directory"]["revision"])
    return {**body, **changes}


def test_same_components_have_two_views_and_derive_tools_not_grants(harness):
    value = describe_directory(harness)
    assert len(value["components"]) == 3
    old = [c for c in value["components"] if c["id"] != "finance.cashflow.readonly"]
    cashflow = next(c for c in value["components"] if c["id"] == "finance.cashflow.readonly")
    assert all(c["audienceIds"] == ["CUSTOMER", "EMPLOYEE"] for c in old)
    assert all(c["status"] == "AVAILABLE" for c in old)
    assert cashflow["audienceIds"] == ["CUSTOMER"] and cashflow["status"] != "AVAILABLE"
    assert {t["toolName"] for c in value["components"] for t in c["tools"]} == {"account_balance_query", "wealth_facts_describe"}
    assert all(t["operation"] == "QUERY" for c in value["components"] for t in c["tools"])
    assert "grantedScopes" not in json.dumps(value)
    assert not value["audiences"][1]["buildEnabled"] and not value["audiences"][2]["enabled"]


@pytest.mark.parametrize("entry,template", [("COMPONENTS", None), ("TEMPLATE", "customer-account-readonly")])
def test_both_entries_reuse_real_queue_recipe_and_frozen_directory(harness, tmp_path, monkeypatch, entry, template):
    jobs = Jobs(PROJECT, harness, data_dir=tmp_path / "jobs")
    calls = []
    monkeypatch.setattr(jobs.builder, "build", lambda *args, **kwargs: calls.append(args))
    try:
        code, value = WorkbenchApi(jobs).post("/api/v1/builds", selection(harness, entryMode=entry, scenarioTemplateId=template), "owner")
        jobs.queue.shutdown(wait=True)
        assert code == 202 and len(calls) == 1
        recipe = json.loads((jobs.inputs / (value["buildId"] + ".json")).read_text())
        assert recipe == solution_for(["account.balance.query"], value["buildId"])
        snapshot = jobs.jobs[value["buildId"]]["directorySelection"]
        assert snapshot["entryMode"] == entry and snapshot["authorizationStatus"] == "RUNTIME_CHECK_REQUIRED"
        assert snapshot["components"][0]["tools"][0]["toolName"] == "account_balance_query"
    finally:
        jobs.close()


@pytest.mark.parametrize("changes,error", [
    ({"audienceId": "EMPLOYEE"}, "AUDIENCE_NOT_BUILDABLE"),
    ({"audienceId": "ENGINEERING"}, "AUDIENCE_NOT_BUILDABLE"),
    ({"componentIds": ["transfer.execute"]}, "COMPONENT_UNAVAILABLE"),
    ({"componentIds": ["wealth.facts.describe"]}, "COMPONENT_SELECTION_MISMATCH"),
    ({"directoryRevision": "0" * 64}, "DIRECTORY_CHANGED"),
    ({"entryMode": "TEMPLATE", "scenarioTemplateId": "employee-previsit"}, "TEMPLATE_UNAVAILABLE"),
    ({"scenarioTemplateId": "customer-account-readonly"}, "TEMPLATE_SELECTION_INVALID"),
])
def test_denial_before_queue_or_network(harness, tmp_path, monkeypatch, changes, error):
    jobs = Jobs(PROJECT, harness, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs, "create", lambda *args, **kwargs: pytest.fail("queued an invalid selection"))
    try:
        with pytest.raises(ApiError, match=error):
            WorkbenchApi(jobs).post("/api/v1/builds", selection(harness, **changes), "owner")
    finally:
        jobs.close()


@pytest.mark.parametrize("changes", [{"componentIds": []}, {"componentIds": ["account.balance.query"] * 2},
                                      {"mandatoryControls": []}, {"scopes": ["account:read"]}, {"principalId": "employee"},
                                      {"runtimeId": "opencode"}, {"bankConnectionId": "crm"}, {"capabilityIds": ["transfer.execute"]}])
def test_client_cannot_change_mandatory_controls_or_runtime(harness, changes):
    with pytest.raises(ApiError, match="SELECTION_INVALID"):
        parse_selection(selection(harness, **changes))


def test_template_adjustment_and_cancelled_capability_are_explicit(harness):
    body = selection(harness, entryMode="TEMPLATE", scenarioTemplateId="customer-wealth-readonly")
    value = resolve_directory(parse_selection(body), harness)
    assert value["templateCustomized"]
    assert [c["id"] for c in value["components"]] == ["account.balance.query"]
    assert solution_for(body["capabilityIds"], "customized")["domainPackRefs"] == []


def test_directory_flags_cannot_enable_an_unimplemented_employee_host(harness, monkeypatch):
    import finance_solution_builder.directory as directory
    value = directory.load_directory()
    value["audiences"][1]["buildEnabled"] = True
    monkeypatch.setattr(directory, "load_directory", lambda: json.loads(json.dumps(value)))
    with pytest.raises(ValueError, match="AUDIENCE_NOT_BUILDABLE"):
        resolve_directory(parse_selection(selection(harness, audienceId="EMPLOYEE")), harness)


@pytest.mark.parametrize("bad", ["write", "version", "missing"])
def test_actual_pack_changes_invalidate_availability_and_revision(harness, bad):
    initial = describe_directory(harness)
    path = harness / "capability-packs/account-balance-readonly/pack.yaml"
    pack = yaml.safe_load(path.read_text())
    if bad == "write":
        pack["capabilities"][0]["operation"] = "TASK"
    if bad == "version":
        pack["version"] = "9.9.9"
    if bad == "missing":
        path.unlink()
    else:
        path.write_text(yaml.safe_dump(pack))
    changed = describe_directory(harness)
    assert initial["revision"] != changed["revision"]
    assert changed["components"][0]["status"] == "ASSET_UNAVAILABLE"
    with pytest.raises(ApiError, match="COMPONENT_UNAVAILABLE"):
        preflight(parse_selection(selection(harness)), PROJECT, harness, "mvn")


def test_planned_transaction_is_metadata_not_an_installed_capability(harness):
    value = describe_directory(harness)
    account = next(item for item in value["components"] if item["id"] == "account.balance.query")
    assert account["plannedCapabilities"] == [{"id": "account.transaction.query", "label": "交易明细查询", "status": "NOT_INTEGRATED"}]
    assert account["capabilityIds"] == ["account.balance.query"]
    assert all(tool["capabilityId"] != "account.transaction.query" for tool in account["tools"])


@pytest.mark.parametrize("change", [{"status": "AVAILABLE"}, {"id": "account.balance.query"}, {"toolName": "fake"}])
def test_planned_metadata_cannot_claim_installation(tmp_path, monkeypatch, change):
    import finance_solution_builder.directory as directory
    value = directory.load_directory()
    account = next(item for item in value["components"] if item["id"] == "account.balance.query")
    account["plannedCapabilities"][0].update(change)
    path = tmp_path / "directory.json"
    path.write_text(json.dumps(value))
    monkeypatch.setattr(directory, "DIRECTORY", path)
    with pytest.raises(ValueError, match="DIRECTORY_INVALID_PLANNED_CAPABILITY"):
        directory.load_directory()
