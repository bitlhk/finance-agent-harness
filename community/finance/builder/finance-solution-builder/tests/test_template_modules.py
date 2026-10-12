import json

import pytest

from finance_solution_builder import template_modules
from finance_solution_builder.service import solution_for
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.workbench_contracts import ApiError, catalog, parse_selection
from test_directory import PROJECT, selection as legacy_selection


def selection(root, **changes):
    old = legacy_selection(root)
    return {**{k: v for k, v in old.items() if k not in {"audienceId", "entryMode", "componentIds"}},
            "schema_version": "0.5", "presentation": "MOBILE_WEB", "scenarioTemplateId": "mobile-banking", "moduleIds": ["agent.account"],
            "directoryRevision": catalog(root)["templateModuleDirectory"]["revision"], **changes}


def test_catalog_source_count_and_partial_operations(harness):
    directory = catalog(harness)["templateModuleDirectory"]
    assert len(directory["modules"]) == 28
    assert [(r["id"], r["language"]) for r in directory["runtimes"]] == [
        ("openjiuwen-java-0.1.15", "java"), ("openjiuwen-python-0.1.18", "python")]
    assert directory["runtimes"][1]["status"] == "AVAILABLE"
    assert directory['runtimes'][1]['capabilityIds'] == ['account.balance.query', 'wealth.facts.describe',
        'finance.transaction-summary.query', 'finance.spending-analysis.query']
    assert len({m["id"] for m in directory["modules"]}) == 28
    assert directory["source"]["commit"] == "3b430100b38037884f7eec51b33a7625e4276cca"
    assert {m["id"] for m in directory["modules"] if m["status"] == "PARTIAL"} == {"agent.account", "agent.wealth_aggregate"}
    mobile_banking_modules = [m for m in directory["modules"] if m["id"].startswith("agent.")]
    assert len(mobile_banking_modules) == 26
    assert all(directory["source"]["commit"] in m["source"] for m in mobile_banking_modules)
    smart_audit_modules = {m["id"]: m for m in directory["modules"] if m["id"].startswith("smart-audit.")}
    assert {m["source"] for m in smart_audit_modules.values()} == {
        "repo://domain-packs/smart-audit/personal-loan-audit",
        "repo://domain-packs/smart-audit/enterprise-credit-audit",
    }
    account = next(m for m in directory["modules"] if m["id"] == "agent.account")
    assert account["sourceOperations"] == ["余额查询", "交易明细"]
    assert [t["toolName"] for t in account["tools"]] == ["account_balance_query"]
    assert all(not t["moduleIds"] for t in directory["templates"]
               if t["id"] not in {"mobile-banking", "loan-review"})
    loan_review = next(t for t in directory["templates"] if t["id"] == "loan-review")
    assert loan_review["status"] == "PARTIAL"
    assert loan_review["moduleIds"] == ["smart-audit.personal-loan", "smart-audit.enterprise-credit"]
    assert all(c["required"] and not c["configurable"] for c in catalog(harness)["mandatoryControls"])


def test_business_domains_classify_every_source_module_once(harness):
    directory = catalog(harness)["templateModuleDirectory"]
    assert [item["label"] for item in sorted(directory["businessDomains"], key=lambda item: item["order"])] == [
        "客户经营", "业务办理", "信贷授信", "运营作业", "风险合规", "投研决策"]
    assignments = directory["moduleDomainAssignments"]
    assert len(assignments) == len(directory["modules"]) == 28
    assert {item["moduleId"] for item in assignments} == {item["id"] for item in directory["modules"]}
    assert len({item["moduleId"] for item in assignments}) == 28
    assert {item["businessDomainId"] for item in assignments} <= {
        item["id"] for item in directory["businessDomains"]}
    assert all(item["businessDomainId"] not in item["relatedBusinessDomainIds"] for item in assignments)


def test_current_capabilities_are_grouped_as_task_blueprints_without_duplication(harness):
    directory = catalog(harness)["templateModuleDirectory"]
    blueprints = {item["id"]: item for item in directory["taskBlueprints"]}
    assert set(blueprints) == {"account-cashflow-service", "wealth-holding-service",
                               "transfer-service", "channel-preference-service",
                               "personal-loan-audit-assistance",
                               "enterprise-credit-audit-assistance"}
    assigned = [module_id for item in blueprints.values() for module_id in item["moduleIds"]]
    assert len(assigned) == len(set(assigned))
    assert set(assigned) == {"agent.account", "agent.finance_assistant", "agent.wealth_aggregate",
                             "agent.transfer", "agent.channel_settings", "smart-audit.personal-loan",
                             "smart-audit.enterprise-credit"}
    assert all(item["legacyTemplateId"] == "mobile-banking" for item in blueprints.values()
               if item["id"] not in {"personal-loan-audit-assistance", "enterprise-credit-audit-assistance"})
    assert all(blueprints[item]["legacyTemplateId"] == "loan-review" for item in
               ("personal-loan-audit-assistance", "enterprise-credit-audit-assistance"))
    pack_ids = [pack_id for item in blueprints.values() for pack_id in item["packIds"]]
    assert len(pack_ids) == len(set(pack_ids)) == 8
    assert blueprints["channel-preference-service"]["label"] == "渠道展示设置"
    assert blueprints["channel-preference-service"]["kind"] == "APPLICATION_CONFIGURATION"
    assert blueprints["channel-preference-service"]["businessDomainId"] == "business-processing"
    assert directory["channels"] == [{"id": "mobile-banking", "label": "手机银行",
                                       "kind": "CUSTOMER_CHANNEL"}]


def test_credit_approval_exposes_two_isolated_smart_audit_task_blueprints(harness):
    directory = catalog(harness)["templateModuleDirectory"]
    blueprints = {item["id"]: item for item in directory["taskBlueprints"]}

    personal = blueprints["personal-loan-audit-assistance"]
    enterprise = blueprints["enterprise-credit-audit-assistance"]

    assert personal["businessDomainId"] == enterprise["businessDomainId"] == "credit-approval"
    assert personal["relatedBusinessDomainIds"] == enterprise["relatedBusinessDomainIds"] == ["operations"]
    assert personal["moduleIds"] == ["smart-audit.personal-loan"]
    assert enterprise["moduleIds"] == ["smart-audit.enterprise-credit"]
    assert personal["packIds"] == ["smart-audit-personal-loan-audit"]
    assert enterprise["packIds"] == ["smart-audit-enterprise-credit-audit"]
    assert set(personal["packIds"]).isdisjoint(enterprise["packIds"])


@pytest.mark.parametrize("modules,caps", [
    (["agent.account"], ["account.balance.query"]),
    (["agent.wealth_aggregate"], ["wealth.facts.describe"]),
    (["agent.account", "agent.wealth_aggregate"], ["account.balance.query", "wealth.facts.describe"]),
])
def test_three_compositions_use_existing_recipe_and_frozen_selection(harness, tmp_path, monkeypatch, modules, caps):
    jobs = Jobs(PROJECT, harness, data_dir=tmp_path / "jobs")
    calls = []
    monkeypatch.setattr(jobs.builder, "build", lambda *a, **kw: calls.append((a, kw)))
    try:
        body = selection(harness, moduleIds=modules, capabilityIds=caps)
        code, result = WorkbenchApi(jobs).post("/api/v1/builds", body, "owner")
        jobs.queue.shutdown(wait=True)
        assert code == 202 and len(calls) == 1
        recipe = json.loads((jobs.inputs / (result["buildId"] + ".json")).read_text())
        assert recipe == solution_for(caps, result["buildId"])
        record = jobs.jobs[result["buildId"]]
        assert record["selection"] == body
        snapshot = record["directorySelection"]
        assert {m["id"] for m in snapshot["modules"]} == set(modules)
        assert snapshot["runtime"]["id"] == body["runtimeId"]
        assert snapshot["audienceId"] == "CUSTOMER"
        assert snapshot["authorizationStatus"] == "RUNTIME_CHECK_REQUIRED"
        assert {t["operation"] for c in snapshot["components"] for t in c["tools"]} == {"QUERY"}
        assert {t["capabilityId"] for c in snapshot["components"] for t in c["tools"]} == set(caps)
    finally:
        jobs.close()


@pytest.mark.parametrize("changes,error", [
    ({"runtimeId": "openjiuwen-python"}, "RUNTIME_NOT_SUPPORTED"),
    ({"runtimeId": "agentscope"}, "RUNTIME_NOT_SUPPORTED"),
    ({"runtimeId": "unknown-runtime"}, "RUNTIME_NOT_SUPPORTED"),
    ({"moduleIds": ["agent.transfer"]}, "MODULE_UNAVAILABLE"),
    ({"moduleIds": ["agent.security_service"]}, "MODULE_UNAVAILABLE"),
    ({"moduleIds": ["agent.does_not_exist"]}, "MODULE_NOT_IN_TEMPLATE"),
    ({"moduleIds": ["agent.wealth_aggregate"]}, "MODULE_SELECTION_MISMATCH"),
    ({"moduleIds": ["agent.account", "agent.wealth_aggregate"]}, "MODULE_SELECTION_MISMATCH"),
    ({"scenarioTemplateId": "wealth-assistant"}, "TEMPLATE_UNAVAILABLE"),
    ({"scenarioTemplateId": "group-insurance-review"}, "TEMPLATE_UNAVAILABLE"),
    ({"scenarioTemplateId": "customer-account-readonly"}, "TEMPLATE_UNAVAILABLE"),
    ({"directoryRevision": "0" * 64}, "DIRECTORY_CHANGED"),
])
def test_reject_before_queue(harness, tmp_path, monkeypatch, changes, error):
    jobs = Jobs(PROJECT, harness, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs, "create", lambda *a, **kw: pytest.fail("invalid request reached queue"))
    try:
        with pytest.raises(ApiError, match=error):
            WorkbenchApi(jobs).post("/api/v1/builds", selection(harness, **changes), "owner")
    finally:
        jobs.close()


@pytest.mark.parametrize("changes", [
    {"moduleIds": []}, {"moduleIds": ["agent.account"] * 2}, {"audienceId": "EMPLOYEE"},
    {"principalId": "other"}, {"profileId": "employee"}, {"mandatoryControls": []},
    {"componentIds": ["account.balance.query"]}, {"capabilityIds": ["transfer.execute"]},
    {"bankConnectionId": "other-bank"}, {"defaultModelRef": "model-not-selected"},
])
def test_model_or_browser_cannot_grant_or_override(harness, changes):
    with pytest.raises(ApiError, match="SELECTION_INVALID"):
        parse_selection(selection(harness, **changes))


@pytest.mark.parametrize("change,error", [
    ("runtime", "RUNTIME_NOT_SUPPORTED"), ("identity", "TEMPLATE_UNAVAILABLE"),
    ("profile", "TEMPLATE_BINDING_MISMATCH"), ("membership", "MODULE_NOT_IN_TEMPLATE"),
])
def test_directory_labels_cannot_enable_backend_or_change_identity(harness, monkeypatch, change, error):
    value = template_modules.load_template_modules()
    if change == "runtime":
        value["runtimes"][1]["status"] = "AVAILABLE"
    if change == "identity":
        value["templates"][0]["audienceId"] = "EMPLOYEE"
    if change == "profile":
        value["templates"][0]["profileId"] = "employee-previsit"
    if change == "membership":
        value["templates"][0]["moduleIds"].remove("agent.account")
    monkeypatch.setattr(template_modules, "load_template_modules", lambda: json.loads(json.dumps(value)))
    body = selection(harness, **({"runtimeId": "openjiuwen-python"} if change == "runtime" else {}))
    with pytest.raises(ValueError, match=error):
        template_modules.resolve_template_modules(parse_selection(body), harness)


def test_legacy_directory_is_retained(harness):
    body = legacy_selection(harness)
    parsed = parse_selection(body)
    assert parsed.model_dump() == {**body, "presentation": "MOBILE_WEB"}
    assert catalog(harness)["directory"]["schemaVersion"] == "finance-studio-directory.v1"


def test_missing_pack_marks_module_unavailable(harness):
    initial = selection(harness)
    (harness / "capability-packs/account-balance-readonly/pack.yaml").unlink()
    updated = selection(harness)
    assert initial["directoryRevision"] != updated["directoryRevision"]
    with pytest.raises(ValueError, match="MODULE_UNAVAILABLE"):
        template_modules.resolve_template_modules(parse_selection(updated), harness)
