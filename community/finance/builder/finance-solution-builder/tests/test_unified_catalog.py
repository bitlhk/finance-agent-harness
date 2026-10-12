from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from finance_solution_builder import unified_catalog as projection
from finance_solution_builder.directory import load_directory
from finance_solution_builder.managed_selection import selection_catalog
from finance_solution_builder.template_modules import load_template_modules

PROJECT = Path(__file__).resolve().parents[1]
HARNESS = Path(os.environ.get("FINANCE_UNIFIED_TEST_HARNESS",
                             "/home/ubuntu/finance-harness-capability-extension-v01"))


@pytest.fixture(scope="module")
def current():
    if not HARNESS.is_dir():
        pytest.skip("Set FINANCE_UNIFIED_TEST_HARNESS to the current Harness checkout")
    return projection.unified_catalog(PROJECT, HARNESS)


def test_current_managed_identity_and_shared_contract(current):
    managed = selection_catalog(PROJECT, HARNESS)
    assert current["managedRevision"] == managed["revision"]
    assert current["directory"]["components"] is current["templateModuleDirectory"]["components"]
    for original, entry in zip(managed["entries"], current["entries"], strict=True):
        for key in ("pack", "providers", "capabilities", "qualification", "installation", "authorization"):
            assert entry[key] == original[key]
    for component in current["directory"]["components"]:
        assert {"id", "label", "description", "scope", "version", "domain", "status",
                "capabilityPackRefs", "domainPackRefs", "providerRefs", "profileRef",
                "capabilityBindings", "tools", "runtimeIds", "methodCustomizationSupported"} <= component.keys()
        assert {c["capabilityId"] for c in component["tools"]} == set(component["capabilityIds"])
        assert all(ref["source"].startswith("repo://") for ref in component["capabilityPackRefs"])
        assert component["profileRef"]["id"]
        assert any("\u4e00" <= char <= "\u9fff" for char in component["label"])


def test_taxonomy_runtime_and_blank_template(current):
    modules = {m["id"]: m for m in current["templateModuleDirectory"]["modules"]}
    assert len(modules) >= 28
    expected = {"internal-transfer": "agent.transfer", "channel-preferences": "agent.channel_settings",
                "account.balance.query": "agent.account", "wealth.facts.describe": "agent.wealth_aggregate",
                "finance.cashflow.readonly": "agent.finance_assistant",
                "wealth-customer-context": "agent.wealth_aggregate",
                "smart-audit-personal-loan-audit": "smart-audit.personal-loan",
                "smart-audit-enterprise-credit-audit": "smart-audit.enterprise-credit"}
    old_ids = {c["id"] for c in load_directory()["components"]}
    for component in current["directory"]["components"]:
        assert component["id"] in modules[expected.get(component["id"], "agent.ungrouped")]["componentIds"]
        assert component["methodCustomizationSupported"] == (component["id"] in old_ids)
        assert (projection.PYTHON_RUNTIME in component["runtimeIds"]) == (component["id"] in old_ids)
    task = next(e for e in current["entries"] if e["pack"]["id"] == "internal-transfer")
    assert "TASK" in {cap["operation"] for cap in task["capabilities"]}
    assert not task["methodCustomizationSupported"]
    custom = next(t for t in current["templateModuleDirectory"]["templates"] if t["id"] == "customer-custom")
    assert custom["audienceId"] == "CUSTOMER"
    assert custom["defaultModuleIds"] == []
    assert custom["status"] == "PARTIAL"
    assert "local-simulator-task" in custom["profileIds"]
    assert set(custom["moduleIds"]) == set(modules)
    assert custom["defaultPackBindings"] == []
    mobile = next(t for t in current["templateModuleDirectory"]["templates"] if t["id"] == "mobile-banking")
    assert set(mobile["moduleIds"]) == set(modules)
    assert set(mobile["defaultComponentIds"]) == {"account.balance.query", "wealth.facts.describe"}
    assert {b["pack"]["id"] for b in mobile["defaultPackBindings"]} == {
        "account-balance-readonly", "wealth-facts-description"}

    loan_review = next(t for t in current["templateModuleDirectory"]["templates"] if t["id"] == "loan-review")
    assert loan_review["applicationTemplateId"] == "banking-readonly-web-v1"
    assert loan_review["defaultPackBindings"] == []
    assert "internal-control" in loan_review["profileIds"]

    entries = {entry["pack"]["id"]: entry for entry in current["entries"]}
    for blueprint in current["templateModuleDirectory"]["taskBlueprints"]:
        for pack_id in blueprint["packIds"]:
            assert entries[pack_id]["moduleIds"][0] in blueprint["moduleIds"]


def test_task_blueprint_fails_closed_for_unknown_pack(monkeypatch):
    taxonomy = load_template_modules()
    taxonomy["taskBlueprints"][0]["packIds"][0] = "unknown-pack"
    monkeypatch.setattr(projection, "load_template_modules", lambda: deepcopy(taxonomy))
    value = projection.unified_catalog(PROJECT, HARNESS)
    blueprint = value["templateModuleDirectory"]["taskBlueprints"][0]
    assert blueprint["availability"] == "UNAVAILABLE"
    assert blueprint["unavailableReason"] == "PACK_REFERENCE_UNAVAILABLE"


def test_deterministic_and_legacy_unchanged(current, monkeypatch):
    before = (load_directory(), load_template_modules())
    def forbidden(*args, **kwargs):
        pytest.fail("Recipe must not supply projection dependencies")
    monkeypatch.setattr("finance_solution_builder.service.solution_for", forbidden)
    assert projection.unified_catalog(PROJECT, HARNESS) == current
    assert (load_directory(), load_template_modules()) == before


def test_alternative_provider_never_default_and_revision_changes(current, monkeypatch):
    managed = selection_catalog(PROJECT, HARNESS)
    entry = deepcopy(next(e for e in managed["entries"] if e["pack"]["id"] == "account-balance-readonly"))
    alternate = {"id": "alternative-provider", "version": "2.0.0"}
    entry["providers"].append(alternate)
    managed["entries"] = [entry]
    monkeypatch.setattr(projection.managed_selection, "selection_catalog", lambda *args: deepcopy(managed))
    resolve = projection.managed_selection.resolve_selection
    def alternatives(project, assembly, bindings):
        actual = deepcopy(bindings)
        actual[0]["provider"] = entry["providers"][0]
        support, result = resolve(project, assembly, actual)
        if bindings[0]["provider"] == alternate:
            result["providerRefs"] = [{**alternate, "source": "maven://fixture:alternative:2.0.0"}]
            result["capabilityBindings"] = {cid: alternate["id"] for cid in result["capabilityBindings"]}
        return support, result
    monkeypatch.setattr(projection.managed_selection, "resolve_selection", alternatives)
    value = projection.unified_catalog(PROJECT, HARNESS)
    component = value["directory"]["components"][0]
    assert component["packBindings"] == [{"pack": entry["pack"], "providers": entry["providers"]}]
    assert component["capabilityBindings"] == {}
    assert component["providerRefs"] == []
    assert len(component["providerOptions"]) == 2
    assert value["directory"]["revision"] != current["directory"]["revision"]


def test_multiple_packs_in_component_are_not_truncated(current, monkeypatch, tmp_path):
    metadata = json.loads(projection.PRESENTATION.read_text())
    metadata["packs"]["channel-preferences"].update(componentId="internal-transfer", moduleId="agent.transfer")
    path = tmp_path / "presentation.json"
    path.write_text(json.dumps(metadata))
    monkeypatch.setattr(projection, "PRESENTATION", path)
    value = projection.unified_catalog(PROJECT, HARNESS)
    component = next(c for c in value["directory"]["components"] if c["id"] == "internal-transfer")
    assert {b["pack"]["id"] for b in component["packBindings"]} == {"internal-transfer", "channel-preferences"}
    assert len(component["capabilityPackRefs"]) == 2
    assert not component["methodCustomizationSupported"]
    assert value["templateModuleDirectory"]["revision"] != current["templateModuleDirectory"]["revision"]


def test_metadata_is_declared_for_wheel():
    import tomllib
    config = tomllib.loads((PROJECT / "pyproject.toml").read_text())
    assert projection.PRESENTATION.name in config["tool"]["setuptools"]["package-data"]["finance_solution_builder"]


def test_unmapped_pack_stays_selectable_without_new_python_support(current, monkeypatch, tmp_path):
    metadata = json.loads(projection.PRESENTATION.read_text())
    del metadata["packs"]["wealth-customer-context"]
    path = tmp_path / "presentation.json"
    path.write_text(json.dumps(metadata))
    monkeypatch.setattr(projection, "PRESENTATION", path)
    value = projection.unified_catalog(PROJECT, HARNESS)
    component = next(c for c in value["directory"]["components"] if c["id"] == "wealth-customer-context")
    assert component["moduleIds"] == ["agent.ungrouped"]
    assert component["runtimeIds"] == ["openjiuwen-java-0.1.15"]
    module = next(m for m in value["templateModuleDirectory"]["modules"] if m["id"] == "agent.ungrouped")
    assert module["componentIds"] == [component["id"]]
    assert module["scope"] == component["label"]
    assert module["source"].startswith("builder://")
    assert len(value["templateModuleDirectory"]["modules"]) == 27
    for template in value["templateModuleDirectory"]["templates"]:
        if template["id"] in {"mobile-banking", "customer-custom"}:
            assert "agent.ungrouped" in template["moduleIds"]


@pytest.mark.parametrize("mutation,code", [("module", "UNIFIED_CATALOG_UNKNOWN_MODULE"),
                                          ("schema", "UNIFIED_CATALOG_PRESENTATION_INVALID")])
def test_invalid_presentation_is_clear(current, monkeypatch, tmp_path, mutation, code):
    metadata = json.loads(projection.PRESENTATION.read_text())
    if mutation == "module":
        metadata["packs"]["internal-transfer"]["moduleId"] = "agent.missing"
    else:
        metadata["schemaVersion"] = "unknown"
    path = tmp_path / "presentation.json"
    path.write_text(json.dumps(metadata))
    monkeypatch.setattr(projection, "PRESENTATION", path)
    with pytest.raises(ValueError, match=code):
        projection.unified_catalog(PROJECT, HARNESS)


def test_multiple_versions_are_separate_choices(current, monkeypatch):
    managed = selection_catalog(PROJECT, HARNESS)
    entry = deepcopy(next(e for e in managed["entries"] if e["pack"]["id"] == "account-balance-readonly"))
    newer = deepcopy(entry)
    newer["pack"]["version"] = "9.0.0"
    managed["entries"] = [entry, newer]
    monkeypatch.setattr(projection.managed_selection, "selection_catalog", lambda *args: deepcopy(managed))
    resolve = projection.managed_selection.resolve_selection
    def versions(project, assembly, bindings):
        actual = deepcopy(bindings)
        actual[0]["pack"] = entry["pack"]
        support, result = resolve(project, assembly, actual)
        result["capabilityPackRefs"][0]["version"] = bindings[0]["pack"]["version"]
        return support, result
    monkeypatch.setattr(projection.managed_selection, "resolve_selection", versions)
    components = projection.unified_catalog(PROJECT, HARNESS)["directory"]["components"]
    assert len(components) == 2
    assert len({c["id"] for c in components}) == 2
    assert all(len(c["packBindings"]) == 1 for c in components)
    assert all(not c["methodCustomizationSupported"] for c in components)
    assert {c["version"] for c in components} == {entry["pack"]["version"], "9.0.0"}


def test_wealth_context_is_independent_not_a_presentation_dependency(current):
    from finance_solution_builder.component_assembly import ComponentAssembly
    from finance_solution_builder.managed_selection import resolve_selection
    components = {c["id"]: c for c in current["directory"]["components"]}
    context = components["wealth-customer-context"]
    facts = components["wealth.facts.describe"]
    assert context["moduleIds"] == facts["moduleIds"] == ["agent.wealth_aggregate"]
    assert context["capabilityIds"] == ["wealth.customer-context.query"]
    assert context["label"] == "财富客户上下文查询"
    assert "不作产品推荐" in context["scope"]
    assert context["domainPackRefs"] == []
    assembly = ComponentAssembly(HARNESS)
    for component in (context, facts):
        _, resolved = resolve_selection(PROJECT, assembly, component["packBindings"])
        assert resolved["capabilityPackRefs"] == component["capabilityPackRefs"]
        assert len(resolved["capabilityPackRefs"]) == 1
        assert set(resolved["capabilityBindings"]) == set(component["capabilityIds"])
    assert {r["id"] for r in facts["domainPackRefs"]} == {"wealth-shared-facts"}


def test_channel_preferences_scope_matches_real_task_schema(current):
    entry = next(e for e in current["entries"] if e["pack"]["id"] == "channel-preferences")
    component = next(c for c in current["directory"]["components"] if c["id"] == entry["componentId"])
    assert component["label"] == entry["label"] == "渠道展示偏好"
    assert component["scope"] == entry["scope"]
    assert "模拟银行样例" in component["scope"] and "宿主明确确认" in component["scope"]
    assert "不含转账" in component["scope"]
    assert component["moduleIds"] == ["agent.channel_settings"]
    assert component["capabilityIds"] == ["channel.preferences.update"]
    tool, = component["tools"]
    assert tool["operation"] == "TASK"
    assert tool["requiredScopes"] == ["channel:write"]
    assert tool["inputSchema"]["additionalProperties"] is False
    assert set(tool["inputSchema"]["properties"]) == {
        "language", "largeTextEnabled", "screenReaderEnabled", "accessibilityMode"}
    assert component["runtimeIds"] == ["openjiuwen-java-0.1.15"]
    assert not component["methodCustomizationSupported"]


def test_chinese_pack_name_wins_over_presentation_fallback(current, monkeypatch):
    managed = selection_catalog(PROJECT, HARNESS)
    entry = next(e for e in managed["entries"] if e["pack"]["id"] == "channel-preferences")
    entry["label"] = "渠道展示偏好（来源中文名称）"
    monkeypatch.setattr(projection.managed_selection, "selection_catalog", lambda *args: deepcopy(managed))
    value = projection.unified_catalog(PROJECT, HARNESS)
    component = next(c for c in value["directory"]["components"] if c["id"] == "channel-preferences")
    assert component["label"] == entry["label"]
    entry["label"] = "Channel preferences"
    value = projection.unified_catalog(PROJECT, HARNESS)
    component = next(c for c in value["directory"]["components"] if c["id"] == "channel-preferences")
    assert component["label"] == "渠道展示偏好"
