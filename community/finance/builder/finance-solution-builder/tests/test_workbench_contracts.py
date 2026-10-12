import json
from pathlib import Path

from jsonschema import Draft202012Validator
import pytest

from finance_solution_builder.workbench_contracts import (
    ApiError, Selection, catalog, connection_status, default_selection, parse_selection, preflight,
)

PROJECT = Path(__file__).resolve().parents[1]


@pytest.mark.parametrize("capabilities", [["wealth.facts.describe"], ["account.balance.query"],
                                          ["wealth.facts.describe", "account.balance.query"]])
def test_finite_schema_and_runtime_agree(capabilities):
    body = default_selection(capabilities)
    Draft202012Validator(Selection.model_json_schema()).validate(body)
    selection = parse_selection(body)
    assert selection.legacy()["capabilities"] == sorted(capabilities)
    assert selection.applicationTemplateId == "banking-readonly-web-v1"


@pytest.mark.parametrize("field,value", [
    ("runtimeId", "fake-runtime"), ("applicationTemplateId", "../../outside"),
    ("bankConnectionId", "crm"), ("modelConnectionId", "https://secret.example"),
    ("capabilityIds", []), ("capabilityIds", ["account.balance.query"] * 2),
    ("capabilityIds", ["transfer.execute"]), ("capabilityIds", [1]),
    ("scopes", ["admin"]), ("principalId", "other"), ("apiKey", "private-key"),
    ("mandatoryControls", []), ("schema_version", "1.0"),
])
def test_unknown_or_unsafe_selection_is_not_echoed(field, value):
    body = default_selection(["wealth.facts.describe"])
    body[field] = value
    with pytest.raises(ApiError, match="^SELECTION_INVALID$"): parse_selection(body)
    assert list(Draft202012Validator(Selection.model_json_schema()).iter_errors(body))


def test_catalog_only_claims_implemented_backend_and_controls_are_mandatory():
    result = catalog()
    assert len(result["runtimes"]) == 1 and len(result["applicationTemplates"]) == 1
    assert len(result["allowedCapabilitySets"]) == 7
    assert all(item["required"] and not item["configurable"] for item in result["mandatoryControls"])
    assert result["applicationTemplates"][0]["profile"] == "CUSTOMER_SELF"
    assert result["transport"]["agUi"] == "WORKBENCH_ADAPTER_AVAILABLE"
    assert result["transport"]["tokenStreaming"] is False
    Draft202012Validator.check_schema(result["selectionSchema"])


def environment(tmp_path):
    policy = tmp_path / "policy-private.json"
    policy.write_text("private policy never returned")
    return dict(FH_MODEL_URL="https://private.example/v1", FH_MODEL_NAME="model-v1", FH_MODEL_KEY="private-model-key",
                FH_BANK_URL="http://127.0.0.1:19999", FH_BANK_LOGIN="private-employee", FH_BANK_SECRET="private-bank-key",
                FH_ACCESS_POLICY=str(policy))


def test_connection_configuration_is_not_connectivity_and_never_exposes_secrets(tmp_path, monkeypatch):
    import urllib.request
    monkeypatch.setattr(urllib.request, "urlopen", lambda *args, **kwargs: pytest.fail("network was called"))
    env = environment(tmp_path)
    result = connection_status(env)
    assert all(item["configurationStatus"] == "CONFIGURED_UNTESTED" for item in result["connections"])
    assert all(item["connectivityStatus"] == "NOT_CHECKED" for item in result["connections"])
    assert result["networkRequests"] == 0
    serialized = json.dumps(result)
    assert all(env[key] not in serialized for key in env if key != "FH_MODEL_NAME")
    assert "private policy" not in serialized
    env["FH_MODEL_NAME"] = env["FH_MODEL_KEY"]
    assert connection_status(env)["connections"][0]["modelName"] is None
    env["FH_MODEL_URL"] = "https://user:secret@example.com/v1"
    assert connection_status(env)["connections"][0]["configurationStatus"] == "INVALID"


def test_missing_connection_fields_and_invalid_policy_are_explicit(tmp_path):
    empty = connection_status({})
    assert all(item["configurationStatus"] == "MISSING" for item in empty["connections"])
    env = environment(tmp_path)
    env["FH_ACCESS_POLICY"] = str(tmp_path / "absent")
    assert connection_status(env)["connections"][1]["invalidFields"] == ["FH_ACCESS_POLICY"]


def test_preflight_without_model_can_build_but_cannot_start_and_does_not_write(tmp_path, monkeypatch):
    from finance_solution_builder.service import solution_for
    selection = parse_selection(default_selection(["account.balance.query"]))
    solution = solution_for(selection.capabilityIds, "unused")
    refs = [solution["profileRef"], *solution["capabilityPackRefs"], solution["evaluationPackRef"]]
    harness = tmp_path / "harness"
    import shutil
    shutil.copytree(PROJECT / "tests/fixtures/component-assembly", harness)
    for ref in refs:
        file = harness / ref["source"].removeprefix("repo://")
        file.parent.mkdir(parents=True, exist_ok=True)
        if not file.exists(): file.touch()
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.shutil.which", lambda value: "/fake/executable")
    before = sorted(str(p) for p in tmp_path.rglob("*"))
    result = preflight(selection, PROJECT, harness, "mvn", {})
    assert result["canBuild"] and not result["startPrerequisitesSatisfied"]
    assert not result["preflightIsAuthorization"] and result["networkRequests"] == 0
    assert sorted(str(p) for p in tmp_path.rglob("*")) == before
    monkeypatch.setattr("finance_solution_builder.workbench_contracts.shutil.which", lambda value: None)
    assert not preflight(selection, PROJECT, harness, "mvn", {})["canBuild"]
