import copy
import json
from pathlib import Path

import pytest

from finance_solution_builder.model_registry import ModelRegistry, ModelRegistryError
from finance_solution_builder.workbench_contracts import parse_selection, ApiError, preflight


def test_default_catalog_contains_verified_glm_53_identity():
    registry = ModelRegistry({})
    model = registry.models["model-ea-013"]
    assert model["displayName"] == "GLM-5.3-Flash"
    assert model["modelName"] == "glm-5.3-flash"
    assert registry.status("model-ea-013") == "UNBOUND"


@pytest.fixture
def configured(tmp_path):
    catalog = {"schemaVersion": "finance-model-catalog.v1", "source": {"kind": "CONTROLLED_TEST"}, "models": [
        {"modelRef": "model-" + x, "displayName": "Test " + x, "provider": "OpenAI", "modelName": "test-" + x,
         "eaCatalogId": x, "eaConfigured": False} for x in "abc"]}
    bindings = {"schemaVersion": "finance-model-bindings.v1", "bindings": [
        {"modelRef": "model-" + x, "revision": "v1", "enabled": True, "protocol": "OPENAI_CHAT_COMPLETIONS",
         "provider": "OpenAI", "modelName": "test-" + x, "urlEnvironment": "FH_CONN_" + x.upper() + "_URL",
         "keyEnvironment": "FH_CONN_" + x.upper() + "_KEY"} for x in "abc"]}
    cat = tmp_path / "catalog.json"
    cat.write_text(json.dumps(catalog))
    reg = tmp_path / "registry.json"
    reg.write_text(json.dumps(bindings))
    env = {"FH_MODEL_CATALOG": str(cat), "FH_MODEL_REGISTRY": str(reg)}
    for x in "ABC":
        env.update({"FH_CONN_" + x + "_URL": "https://private-" + x + ".example/v1", "FH_CONN_" + x + "_KEY": "secret-" + x})
    return env, catalog, bindings


def selection(registry):
    return dict(extension_type="finance-workbench.selection", schema_version="0.3", runtimeId="openjiuwen-java-0.1.15",
                applicationTemplateId="banking-readonly-web-v1", bankConnectionId="local-simulator", capabilityIds=["account.balance.query"],
                selectedModelRefs=["model-a", "model-b"], defaultModelRef="model-a", modelCatalogRevision=registry.catalog_revision)


def test_registry_has_distinct_stable_refs_and_no_private_values(configured):
    env, _, _ = configured
    registry = ModelRegistry(env)
    public = json.dumps(registry.public_models())
    assert "secret-" not in public and "private-" not in public and "_KEY" not in public
    frozen = registry.snapshot(["model-a", "model-b"], "model-b", registry.catalog_revision)
    assert frozen["defaultModelRef"] == "model-b"
    child = registry.child_environment(frozen)
    assert child["FH_CONN_B_URL"] != child["FH_CONN_A_URL"] and "FH_CONN_C_KEY" not in child
    assert "secret-" not in json.dumps(frozen) and "private-" not in json.dumps(frozen)


@pytest.mark.parametrize("changes", [
    {"selectedModelRefs": []}, {"selectedModelRefs": ["model-a", "model-a"]}, {"defaultModelRef": "model-c"},
    {"selectedModelRefs": ["__auto"]}, {"selectedModelRefs": ["https://private.example"]},
    {"modelConnectionId": "configured-model"}, {"apiKey": "private"}, {"capabilityIds": ["concept-transfer"]}])
def test_strict_selection_rejects_bypasses(configured, changes):
    body = selection(ModelRegistry(configured[0]))
    body.update(changes)
    with pytest.raises(ApiError):
        parse_selection(body)


def test_unknown_disabled_and_unsupported_models(configured):
    env, _, bindings = configured
    registry = ModelRegistry(env)
    with pytest.raises(ModelRegistryError, match="MODEL_UNKNOWN"):
        registry.snapshot(["model-unknown"], "model-unknown", registry.catalog_revision)
    for change in ({"enabled": False}, {"protocol": "OAUTH_SPECIAL"}):
        revised = copy.deepcopy(bindings)
        revised["bindings"][0].update(change)
        Path(env["FH_MODEL_REGISTRY"]).write_text(json.dumps(revised))
        registry = ModelRegistry(env)
        with pytest.raises(ModelRegistryError, match="MODEL_NOT_ENABLED"):
            registry.snapshot(["model-a"], "model-a", registry.catalog_revision)


def test_freezing_detects_endpoint_revision_drift_but_not_key_rotation(configured):
    env, _, bindings = configured
    registry = ModelRegistry(env)
    frozen = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    env["FH_CONN_A_KEY"] = "new-private-key"
    ModelRegistry(env).check_frozen(frozen)
    env["FH_CONN_A_URL"] = "https://changed.example/v1"
    with pytest.raises(ModelRegistryError, match="MODEL_CONFIGURATION_CHANGED"):
        ModelRegistry(env).check_frozen(frozen)
    bindings["bindings"][0]["enabled"] = False
    Path(env["FH_MODEL_REGISTRY"]).write_text(json.dumps(bindings))
    with pytest.raises(ModelRegistryError, match="MODEL_CONNECTION_UNAVAILABLE"):
        ModelRegistry(env).check_frozen(frozen)


def test_missing_binding_can_build_but_never_start(configured, tmp_path, monkeypatch):
    env, _, _ = configured
    env.pop("FH_MODEL_REGISTRY")
    registry = ModelRegistry(env)
    snapshot = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    assert snapshot["connections"][0]["configVersion"] is None
    with pytest.raises(ModelRegistryError):
        registry.check_frozen(snapshot)
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.shutil.which", lambda _: "/bin/true")
    project = Path(__file__).resolve().parents[1]
    from finance_solution_builder.service import solution_for
    recipe = solution_for(["account.balance.query"], "test")
    import shutil
    shutil.copytree(project / "tests/fixtures/component-assembly", tmp_path, dirs_exist_ok=True)
    for ref in [recipe["profileRef"], *recipe["capabilityPackRefs"], recipe["evaluationPackRef"]]:
        (tmp_path / ref["source"].removeprefix("repo://")).mkdir(parents=True, exist_ok=True)
    result = preflight(parse_selection(selection(registry)), project, tmp_path, "mvn", env)
    assert result["canBuild"] and not result["startPrerequisitesSatisfied"] and result["networkRequests"] == 0


def test_alias_rename_does_not_change_connection_identity(configured):
    env, catalog, _ = configured
    registry = ModelRegistry(env)
    before = registry.snapshot(["model-a"], "model-a", registry.catalog_revision)
    catalog["models"][0]["displayName"] = "Renamed"
    Path(env["FH_MODEL_CATALOG"]).write_text(json.dumps(catalog))
    current = ModelRegistry(env)
    current.check_frozen(before)
    assert current.catalog_revision != registry.catalog_revision
    with pytest.raises(ModelRegistryError, match="MODEL_CATALOG_CHANGED"):
        current.snapshot(["model-a"], "model-a", registry.catalog_revision)
