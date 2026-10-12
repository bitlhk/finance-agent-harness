import json

import pytest

from finance_solution_builder.workbench_contracts import ApiError, parse_selection, preflight
from finance_solution_builder.workbench import Jobs
from tests.test_component_assembly import PROJECT, root
from tests.test_managed_selection import selection_for


def body():
    selected = selection_for(["account.balance.query"])
    return dict(extension_type="finance-workbench.selection", schema_version="0.8",
                runtimeId="openjiuwen-java-0.1.15", applicationTemplateId="banking-readonly-web-v1",
                bankConnectionId="local-simulator", capabilityIds=["account.balance.query"],
                selectedModelRefs=["model-a"], defaultModelRef="model-a", modelCatalogRevision="a" * 64,
                packBindings=selected["packBindings"])


@pytest.mark.parametrize("field,value", [("scopes", ["admin"]), ("principalId", "other"),
                                       ("className", "unsafe.Factory"), ("methodRef", {}),
                                       ("runtimeId", "openjiuwen-python-0.1.18"),
                                       ("capabilityIds", ["account.balance.query"] * 2)])
def test_selection_does_not_accept_grants_code_private_method_or_unverified_host(field, value):
    value_body = body()
    value_body[field] = value
    with pytest.raises(ApiError, match="SELECTION_INVALID"):
        parse_selection(value_body)


def test_binding_and_claimed_capabilities_must_match(root):
    value = body()
    value["capabilityIds"] = ["other.read"]
    with pytest.raises(ValueError, match="COMPONENT_SELECTION_MISMATCH"):
        parse_selection(value).resolve(PROJECT, root, "managed-test")


@pytest.mark.parametrize("terminal", ["MOBILE_WEB", "DESKTOP_WEB", "ATM_WEB"])
def test_managed_three_terminals_preserve_existing_presentation_contract(root, terminal):
    from finance_solution_builder.terminal_ui import presentation_config
    value = {**body(), "presentation": terminal, "interfaceConfig": {"assistantName": "My Agent"}}
    selection = parse_selection(value)
    assert selection.resolve(PROJECT, root, "managed-terminal")["capabilityBindings"]
    assert presentation_config(selection.model_dump())["terminal"] == terminal


def test_managed_atm_does_not_enable_voice(root):
    value = {**body(), "presentation": "ATM_WEB", "interfaceConfig": {"voiceInput": True}}
    with pytest.raises(ValueError, match="ATM_VOICE_UNSUPPORTED"):
        parse_selection(value).resolve(PROJECT, root, "managed-terminal")


def test_workbench_creates_managed_input_without_legacy_recipe_and_dispatches_managed_build(root, tmp_path, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("managed creation called legacy business recipe")
    monkeypatch.setattr("finance_solution_builder.workbench.solution_for", forbidden)
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    calls = []
    monkeypatch.setattr(jobs.queue, "submit", lambda *args: None)
    monkeypatch.setattr(jobs.builder, "build", lambda *args, **kwargs: calls.append((args, kwargs)))
    try:
        selection = parse_selection(body())
        job = jobs.create(selection.legacy(), "owner-a", selection=selection.model_dump())
        frozen = json.loads((jobs.inputs / (job["id"] + ".json")).read_bytes())
        assert frozen["capabilityBindings"] == {"account.balance.query": "non-ai-banking-reference"}
        jobs.build(job["id"])
        assert calls[0][1]["managed"] is True
        assert jobs.jobs[job["id"]]["status"] == "BUILT"
        with pytest.raises(PermissionError):
            jobs.owned(job["id"], "owner-b")
    finally:
        jobs.queue.shutdown(wait=True)


def test_invalid_binding_creates_no_job_or_generated_input(root, tmp_path, monkeypatch):
    jobs = Jobs(PROJECT, root, data_dir=tmp_path / "jobs")
    monkeypatch.setattr(jobs.queue, "submit", lambda *args: pytest.fail("queued invalid selection"))
    try:
        value = body()
        value["packBindings"][0]["provider"]["version"] = "999"
        selection = parse_selection(value)
        with pytest.raises(ValueError):
            jobs.create(selection.legacy(), "owner-a", selection=selection.model_dump())
        assert jobs.jobs == {}
        assert list(jobs.inputs.iterdir()) == []
    finally:
        jobs.queue.shutdown(wait=True)


def test_actual_builder_input_validation_and_dispatch_use_managed_admission(root, tmp_path, monkeypatch):
    from finance_solution_builder.builder import SolutionBuilder
    selected = parse_selection(body())
    solution = selected.resolve(PROJECT, root, "managed-dispatch")
    path = tmp_path / "solution.json"
    path.write_text(json.dumps(solution))
    builder = SolutionBuilder(PROJECT, root, service_output_root=tmp_path / "output")
    # The copied component fixture covers admission, not the separate Solution Schema.
    monkeypatch.setattr("finance_solution_builder.builder.validate_solution", lambda *_: None)
    monkeypatch.setattr(builder, "_validate_assembly_support", lambda *_: pytest.fail("legacy recipe invoked"))
    calls = []
    sentinel = object()
    def build_service(*args, **kwargs):
        calls.append(kwargs)
        return sentinel
    monkeypatch.setattr("finance_solution_builder.service.build_service", build_service)
    assert builder.build(path, tmp_path / "output/candidate", managed=True) is sentinel
    assert calls[0]["managed"] is True


def test_managed_directory_is_repeatable_and_only_exposes_query_requirements(root):
    from finance_solution_builder.managed_selection import selection_catalog
    before = {str(p): p.read_bytes() for p in root.rglob("*") if p.is_file()}
    directory = selection_catalog(PROJECT, root)
    assert directory == selection_catalog(PROJECT, root)
    assert len(directory["entries"]) == 3
    for entry in directory["entries"]:
        assert entry["installation"] == "NOT_OBSERVED"
        assert entry["authorization"] == "RUNTIME_CHECK_REQUIRED"
        assert all(cap["operation"] == "QUERY" for cap in entry["capabilities"])
        assert all(set(p) == {"id", "version"} for p in entry["providers"])
    assert before == {str(p): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def test_managed_preflight_uses_bindings_not_recipe(root, monkeypatch):
    solution = parse_selection(body()).resolve(PROJECT, root, "preflight-fixture")
    for ref in (solution["profileRef"], solution["evaluationPackRef"]):
        path = root / ref["source"].removeprefix("repo://")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.touch()
    def forbidden(*args, **kwargs):
        pytest.fail("managed preflight called legacy recipe")
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.solution_for", forbidden)
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.shutil.which", lambda _: "/fake/tool")
    class Registry:
        def __init__(self, *_): pass
        def snapshot(self, *args): return {"controlled": True}
        def status(self, *_): return "MISSING"
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.ModelRegistry", Registry)
    result = preflight(parse_selection(body()), PROJECT, root, "mvn", {})
    assert result["canBuild"] and not result["startPrerequisitesSatisfied"]
    assert result["networkRequests"] == 0 and result["preflightIsAuthorization"] is False


def test_managed_start_uses_frozen_artifact_not_mutable_catalog(monkeypatch):
    from threading import RLock
    from types import SimpleNamespace
    from finance_solution_builder.workbench_api import WorkbenchApi
    import finance_solution_builder.workbench_api as module
    calls = []
    class Registry:
        def check_frozen(self, value): calls.append(("model", value))
    monkeypatch.setattr(module, "ModelRegistry", Registry)
    monkeypatch.setattr(module, "start_readiness", lambda **_: {"configurationReady": True})
    monkeypatch.setattr(module, "preflight", lambda *_: pytest.fail("old artifact reselected catalog"))
    jobs = SimpleNamespace(lock=RLock(), owned=lambda *args: None,
        start=lambda *args, **kwargs: calls.append(("start", kwargs)) or {"instanceId": "instance-new"})
    api = object.__new__(WorkbenchApi)
    api.jobs = jobs
    api.inspect = lambda *args, **kwargs: {"selection": body(), "modelSelection": {"frozen": True}}
    artifact = "sha256:" + "b" * 64
    status, _ = api.post("/api/v1/builds/agent-test/start", {"artifactId": artifact}, "owner-a")
    assert status == 200
    assert calls == [("model", {"frozen": True}), ("start", {"expected_artifact": artifact})]
