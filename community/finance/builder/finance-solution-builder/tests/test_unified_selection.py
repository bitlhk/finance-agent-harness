from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from finance_solution_builder import unified_catalog as projection
from finance_solution_builder.component_assembly import AssemblyRejected
from finance_solution_builder.method_assets import runtime_reference
from finance_solution_builder.python_service import RUNTIME_ID, managed_python_bridge
from finance_solution_builder.service import solution_for
from finance_solution_builder.workbench import Jobs
from finance_solution_builder.workbench_api import WorkbenchApi
from finance_solution_builder.workbench_contracts import ApiError, parse_selection, preflight
from tests.test_component_assembly import COMBINATIONS, PROJECT, root
from tests.test_managed_selection import selection_for


def body(root, caps=None, **updates):
    caps = caps or ["account.balance.query"]
    directory = projection.unified_catalog(PROJECT, root)["templateModuleDirectory"]
    value = dict(extension_type="finance-workbench.selection", schema_version="0.9",
        runtimeId="openjiuwen-java-0.1.15", applicationTemplateId="banking-readonly-web-v1",
        bankConnectionId="local-simulator", capabilityIds=sorted(caps),
        selectedModelRefs=["model-a"], defaultModelRef="model-a", modelCatalogRevision="a" * 64,
        scenarioTemplateId="mobile-banking", directoryRevision=directory["revision"],
        moduleIds=[m["id"] for m in directory["modules"] if set(m["capabilityIds"]) & set(caps)],
        packBindings=selection_for(caps)["packBindings"])
    return {**value, **updates}


def frozen_method(caps):
    return {"assetId": "method-0123456789abcdef", "version": "1.0.0",
            "releaseDigest": "sha256:" + "a" * 64, "installationDigest": "sha256:" + "b" * 64,
            "data": {"capabilityIds": caps}, "source": {"kind": "IMPORTED"}}


@pytest.mark.parametrize("caps", COMBINATIONS)
@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
def test_seven_combinations_use_declarations_and_preserve_python(root, caps, runtime, monkeypatch):
    selected = parse_selection(body(root, caps, runtimeId=runtime))
    expected = solution_for(caps, "unified-test")
    def forbidden(*args, **kwargs):
        pytest.fail("unified resolution invoked a business recipe")
    monkeypatch.setattr("finance_solution_builder.service.solution_for", forbidden)
    monkeypatch.setattr("finance_solution_builder.python_service.solution_for", forbidden)
    actual = selected.resolve(PROJECT, root, "unified-test")
    for key in ("profileRef", "capabilityBindings", "capabilityPackRefs", "providerRefs", "domainPackRefs"):
        assert actual[key] == expected[key]
    if runtime == RUNTIME_ID:
        bridge, plan = managed_python_bridge(PROJECT, root, actual)
        assert bridge == expected
        assert all(c["operation"] == "QUERY" for c in plan["capabilities"])


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
@pytest.mark.parametrize("scope", [["wealth.facts.describe"], ["finance.transaction-summary.query"], ["account.balance.query"]])
def test_private_method_replaces_only_declared_default_scope(root, runtime, scope):
    caps = COMBINATIONS[-1]
    snapshot = frozen_method(scope)
    selected = parse_selection(body(root, caps, runtimeId=runtime,
        methodRef={k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}))
    actual = selected.resolve(PROJECT, root, "unified-method", snapshot)
    expected = solution_for(caps, "unified-method", runtime_reference(snapshot), scope)
    assert actual["domainPackRefs"] == expected["domainPackRefs"]
    if runtime == RUNTIME_ID:
        bridge, _ = managed_python_bridge(PROJECT, root, actual, snapshot)
        assert bridge == expected
    changed = deepcopy(snapshot)
    changed["releaseDigest"] = "sha256:" + "c" * 64
    with pytest.raises(ValueError, match="ASSEMBLY_METHOD_SNAPSHOT_MISMATCH"):
        selected.resolve(PROJECT, root, "unified-method", changed)


@pytest.mark.parametrize("updates,code", [
    ({"directoryRevision": "f" * 64}, "DIRECTORY_CHANGED"),
    ({"scenarioTemplateId": "unknown"}, "TEMPLATE_UNAVAILABLE"),
    ({"moduleIds": ["agent.wealth_aggregate"]}, "MODULE_SELECTION_MISMATCH"),
    ({"moduleIds": ["unknown"]}, "MODULE_NOT_IN_TEMPLATE"),
    ({"capabilityIds": ["unknown.query"]}, "MODULE_SELECTION_MISMATCH"),
])
def test_unified_preflight_preserves_safe_reason(root, updates, code):
    with pytest.raises(ApiError, match=code):
        preflight(parse_selection(body(root, **updates)), PROJECT, root, "mvn", {})


def test_preflight_does_not_echo_arbitrary_assembly_error(root, monkeypatch):
    selected = parse_selection(body(root))
    def rejected(*args, **kwargs):
        raise AssemblyRejected("secret /private/customer/token")
    monkeypatch.setattr("finance_solution_builder.managed_selection.resolve_unified_selection", rejected)
    with pytest.raises(ApiError, match="^COMPONENT_SELECTION_REJECTED$"):
        preflight(selected, PROJECT, root, "mvn", {})


def test_loan_review_declares_the_application_terminal_required_by_unified_selection():
    template = next(item for item in projection.load_template_modules()["templates"]
                    if item["id"] == "loan-review")
    assert template["applicationTemplateId"] == "banking-readonly-web-v1"


def test_invalid_provider_never_queues(root, tmp_path, monkeypatch):
    value = body(root)
    value["packBindings"][0]["provider"]["version"] = "999"
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: pytest.fail("invalid provider queued"))
    try:
        with pytest.raises(ApiError, match="COMPONENT_SELECTION_MISMATCH"):
            WorkbenchApi(jobs).post("/api/v1/builds", value, "owner")
        assert not jobs.jobs and not list(jobs.inputs.iterdir())
    finally:
        jobs.close()


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
def test_jobs_freeze_unified_selection_and_dispatch_managed(root, tmp_path, monkeypatch, runtime):
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    calls = []
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: None)
    monkeypatch.setattr(jobs.builder, "build", lambda *args, **kwargs: calls.append((args, kwargs)))
    try:
        selected = parse_selection(body(root, runtimeId=runtime))
        job = jobs.create(selected.legacy(), "owner", selection=selected.model_dump())
        jobs.build(job["id"])
        assert calls[0][1]["managed"] is True
        assert calls[0][1]["interface_config"]["terminal"] == "MOBILE_WEB"
        saved = json.loads((jobs.inputs / (job["id"] + ".json")).read_text())
        assert saved["runtime"]["language"] == ("python" if runtime == RUNTIME_ID else "java")
        assert jobs.jobs[job["id"]]["directorySelection"]["revision"] == selected.directoryRevision
        frozen_directory = jobs.jobs[job["id"]]["directorySelection"]
        assert frozen_directory["metadataRole"] == "CATALOG_SELECTION_NOT_INSTALLATION"
        assert frozen_directory["selectedPackBindings"] == [b.model_dump() for b in selected.packBindings]
        assert frozen_directory["selectedCapabilityIds"] == sorted(selected.capabilityIds)
    finally:
        jobs.close()


def test_method_snapshot_is_resolved_under_owner_and_ancestry_is_not_rewritten(root, tmp_path, monkeypatch):
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    snapshot = frozen_method(["account.balance.query"])
    snapshot["source"] = {"kind": "BUSINESS_DERIVED", "binding": {"directoryRevision": "legacy-revision"}}
    seen = []
    def release(owner, ref):
        assert owner == "owner-a"
        assert ref == {k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}
        return deepcopy(snapshot)
    monkeypatch.setattr(jobs.method_assets, "release", release)
    monkeypatch.setattr(jobs.method_assets, "check_source", lambda value: seen.append(deepcopy(value)))
    monkeypatch.setattr(jobs.method_assets, "require_active", lambda *args: None)
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: None)
    try:
        selected = parse_selection(body(root, methodRef={k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}))
        forged = deepcopy(snapshot)
        forged["source"] = {}
        with pytest.raises(ValueError, match="ASSEMBLY_METHOD_SNAPSHOT_MISMATCH"):
            jobs.create(selected.legacy(), "owner-a", selection=selected.model_dump(), method_snapshot=forged)
        assert not jobs.jobs
        jobs.create(selected.legacy(), "owner-a", selection=selected.model_dump(), method_snapshot=snapshot)
        assert seen and all(value == snapshot for value in seen)
    finally:
        jobs.close()


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
@pytest.mark.parametrize("private", [False, True])
def test_builder_delegates_managed_validation_and_owned_snapshot(root, tmp_path, monkeypatch, runtime, private):
    from finance_solution_builder.builder import SolutionBuilder
    snapshot = frozen_method(["account.balance.query"]) if private else None
    selected = parse_selection(body(root, runtimeId=runtime,
        methodRef={k: snapshot[k] for k in ("assetId", "version", "releaseDigest")} if snapshot else None))
    solution = selected.resolve(PROJECT, root, "unified-build", snapshot)
    source = tmp_path / "solution.json"
    source.write_text(json.dumps(solution))
    builder = SolutionBuilder(PROJECT, root, service_output_root=tmp_path / "output")
    monkeypatch.setattr("finance_solution_builder.builder.validate_solution", lambda *_: None)
    monkeypatch.setattr(builder, "_validate_assembly_support", lambda *_: pytest.fail("legacy validation"))
    calls = []
    sentinel = object()
    def delegated(*args, **kwargs):
        calls.append((args, kwargs))
        return sentinel
    module = ("finance_solution_builder.candidate_backend.openjiuwen_python.build_python_service"
              if runtime == RUNTIME_ID else "finance_solution_builder.service.build_service")
    monkeypatch.setattr(module, delegated)
    assert builder.build(source, tmp_path / "output/candidate", managed=True, method_snapshot=snapshot) is sentinel
    assert calls[0][1]["managed"] is True
    assert calls[0][1]["method_snapshot"] == snapshot


def test_partial_module_selection_and_task_gates_against_current_catalog(monkeypatch):
    harness = Path(os.environ.get("FINANCE_UNIFIED_TEST_HARNESS", "/home/ubuntu/finance-harness-capability-extension-v01"))
    if not harness.is_dir():
        pytest.skip("Current managed Harness checkout required")
    catalog = projection.unified_catalog(PROJECT, harness)
    task = next(e for e in catalog["entries"] if any(c["operation"] == "TASK" for c in e["capabilities"]))
    value = body(harness)
    value.update(packBindings=[{"pack": task["pack"], "provider": task["providers"][0]}],
                 moduleIds=task["moduleIds"], capabilityIds=[c["capabilityId"] for c in task["capabilities"]])
    solution = parse_selection(value).resolve(PROJECT, harness, "unified-task")
    assert solution["profileRef"]["id"] == "local-simulator-task"
    with pytest.raises(ValueError, match="RUNTIME_NOT_SUPPORTED|PYTHON_COMPOSITION_UNSUPPORTED"):
        parse_selection({**value, "runtimeId": RUNTIME_ID}).resolve(PROJECT, harness, "unified-task")
    snapshot = frozen_method(["account.balance.query"])
    with pytest.raises(ValueError, match="MANAGED_METHOD_UNSUPPORTED"):
        parse_selection({**value, "methodRef": {k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}}).resolve(
            PROJECT, harness, "unified-task", snapshot)
    readonly = parse_selection(body(harness, ["wealth.facts.describe"], runtimeId=RUNTIME_ID))
    solution, directory, _ = readonly.resolve_details(PROJECT, harness, "partial-wealth")
    assert set(solution["capabilityBindings"]) == {"wealth.facts.describe"}
    assert directory["templateCustomized"] is True


def test_managed_python_bridge_cannot_accept_task_even_with_readonly_names(root, monkeypatch):
    selected = parse_selection(body(root, runtimeId=RUNTIME_ID))
    solution = selected.resolve(PROJECT, root, "python-task-rejected")
    from finance_solution_builder.component_plan import qualify_solution
    def task_plan(*args, **kwargs):
        plan = qualify_solution(*args, **kwargs)
        plan["capabilities"][0]["operation"] = "TASK"
        return plan
    monkeypatch.setattr("finance_solution_builder.component_plan.qualify_solution", task_plan)
    with pytest.raises(ValueError, match="PYTHON_COMPOSITION_UNSUPPORTED"):
        managed_python_bridge(PROJECT, root, solution)


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
def test_api_preflight_and_create_carry_exact_method_snapshot(root, tmp_path, monkeypatch, runtime):
    import finance_solution_builder.workbench_contracts as contracts
    snapshot = frozen_method(["wealth.facts.describe"])
    selected = parse_selection(body(root, ["wealth.facts.describe"], runtimeId=runtime,
        methodRef={k: snapshot[k] for k in ("assetId", "version", "releaseDigest")}))
    solution = selected.resolve(PROJECT, root, "preflight-fixture", snapshot)
    for ref in (solution["profileRef"], solution["evaluationPackRef"]):
        path = root / ref["source"].removeprefix("repo://")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    class Registry:
        def __init__(self, *_): pass
        def snapshot(self, *args): return {"controlled": True, "connections": []}
        def status(self, *_): return "MISSING"
    monkeypatch.setattr(contracts, "ModelRegistry", Registry)
    monkeypatch.setattr(contracts.shutil, "which", lambda _: "/fake/tool")
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs.queue, "submit", lambda *_: None)
    monkeypatch.setattr(jobs.method_assets, "release", lambda owner, ref: deepcopy(snapshot))
    monkeypatch.setattr(jobs.method_assets, "check_source", lambda _: None)
    monkeypatch.setattr(jobs.method_assets, "require_active", lambda *_: None)
    try:
        api = WorkbenchApi(jobs)
        code, result = api.post("/api/v1/preflight", selected.model_dump(), "owner")
        assert code == 200 and result["canBuild"]
        assert result["method"]["releaseDigest"] == snapshot["releaseDigest"]
        assert result["networkRequests"] == 0 and not result["preflightIsAuthorization"]
        code, result = api.post("/api/v1/builds", selected.model_dump(), "owner")
        assert code == 202
        assert jobs.jobs[result["buildId"]]["methodRef"] == selected.methodRef.reference()
    finally:
        jobs.close()


@pytest.mark.parametrize("runtime", ["openjiuwen-java-0.1.15", RUNTIME_ID])
def test_unified_start_uses_frozen_artifact_without_catalog(root, monkeypatch, runtime):
    from threading import RLock
    from types import SimpleNamespace
    import finance_solution_builder.workbench_api as module
    selected = body(root, runtimeId=runtime)
    calls = []
    class Registry:
        def check_frozen(self, value): calls.append(("model", value))
    monkeypatch.setattr(module, "ModelRegistry", Registry)
    monkeypatch.setattr(module, "start_readiness", lambda **_: {"configurationReady": True})
    monkeypatch.setattr(module, "preflight", lambda *_: pytest.fail("frozen artifact reselected"))
    monkeypatch.setattr(projection, "unified_catalog", lambda *_: pytest.fail("catalog read on start"))
    jobs = SimpleNamespace(lock=RLock(), owned=lambda *_: None,
        start=lambda *args, **kwargs: calls.append(("start", kwargs)) or {"instanceId": "new-instance"})
    api = object.__new__(WorkbenchApi)
    api.jobs = jobs
    api.inspect = lambda *args, **kwargs: {"selection": selected, "modelSelection": {"frozen": True}}
    artifact = "sha256:" + "b" * 64
    code, _ = api.post("/api/v1/builds/agent-test/start", {"artifactId": artifact}, "owner")
    assert code == 200 and calls == [("model", {"frozen": True}), ("start", {"expected_artifact": artifact})]


def test_unified_catalog_exposes_enriched_entries_without_changing_managed(root):
    from finance_solution_builder.managed_selection import selection_catalog
    from finance_solution_builder.workbench_contracts import catalog
    value = catalog(root)
    assert value["managedDirectory"] == selection_catalog(PROJECT, root)
    assert value["unifiedManagedDirectory"]["entries"][0]["runtimeIds"]
    assert value["unifiedSelectionSchema"]["properties"]["schema_version"]["const"] == "0.9"


def test_unified_respects_advertised_presentation_and_pack_defaults(root, monkeypatch):
    selected = parse_selection(body(root, ["account.balance.query", "wealth.facts.describe"]))
    value = projection.unified_catalog(PROJECT, root)
    template = next(t for t in value["templateModuleDirectory"]["templates"] if t["id"] == selected.scenarioTemplateId)
    for binding in template["defaultPackBindings"]:
        binding.pop("provider", None)
        binding["providers"] = [{"id": "alternate-provider", "version": "1"}]
    monkeypatch.setattr(projection, "unified_catalog", lambda *_: value)
    _, directory, _ = selected.resolve_details(PROJECT, root, "default-packs")
    assert not directory["templateCustomized"]
    value["selectionSupport"]["presentations"] = ["MOBILE_WEB"]
    atm = selected.model_copy(update={"presentation": "ATM_WEB"})
    with pytest.raises(ValueError, match="MANAGED_HOST_UNSUPPORTED"):
        atm.resolve(PROJECT, root, "no-atm-host")
    with pytest.raises(ApiError, match="^MANAGED_HOST_UNSUPPORTED$"):
        preflight(atm, PROJECT, root, "mvn", {})


def test_directory_snapshot_does_not_claim_unselected_module_assets(root, monkeypatch):
    selected = parse_selection(body(root, ["wealth.facts.describe"]))
    value = projection.unified_catalog(PROJECT, root)
    module = next(m for m in value["templateModuleDirectory"]["modules"] if m["id"] == "agent.wealth_aggregate")
    module["componentIds"].append("extra-wealth-context")
    module["capabilityIds"].append("wealth.context.query")
    monkeypatch.setattr(projection, "unified_catalog", lambda *_: value)
    solution, snapshot, _ = selected.resolve_details(PROJECT, root, "partial-snapshot")
    assert "wealth.context.query" in snapshot["modules"][0]["capabilityIds"]
    assert snapshot["metadataRole"] == "CATALOG_SELECTION_NOT_INSTALLATION"
    assert snapshot["selectedCapabilityIds"] == ["wealth.facts.describe"]
    assert snapshot["selectedPackBindings"] == [b.model_dump() for b in selected.packBindings]
    assert "wealth.context.query" not in solution["capabilityBindings"]
    assert all(b["pack"]["id"] != "extra-wealth-context" for b in snapshot["selectedPackBindings"])


@pytest.mark.parametrize("changed", [{"id": "different-pack"}, {"version": "999"}])
def test_customization_detects_pack_identity_change_not_provider(root, monkeypatch, changed):
    selected = parse_selection(body(root, ["account.balance.query", "wealth.facts.describe"]))
    value = projection.unified_catalog(PROJECT, root)
    template = next(t for t in value["templateModuleDirectory"]["templates"] if t["id"] == selected.scenarioTemplateId)
    template["defaultPackBindings"][0]["pack"].update(changed)
    monkeypatch.setattr(projection, "unified_catalog", lambda *_: value)
    _, snapshot, _ = selected.resolve_details(PROJECT, root, "changed-pack-default")
    assert snapshot["templateCustomized"] is True
