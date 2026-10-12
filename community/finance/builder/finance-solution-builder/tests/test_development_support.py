import copy
import json
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from finance_solution_builder.assets import tree_digest
from finance_solution_builder.business_assets import check_source, component_development_support, validate_binding
from finance_solution_builder.development_support import INDEX, load_development_support, project_verification
from finance_solution_builder.directory import describe_directory
from finance_solution_builder.experience_contracts import ApiError
from finance_solution_builder.method_api import MethodApi
from finance_solution_builder.method_assets import MethodAssets, canonical, runtime_files, sha
from tests.development_support_fixture import FIXTURE, install_support, write_declarations
from tests.test_business_assets import registered

PROJECT = Path(__file__).resolve().parents[1]
SDK = "maven://com.openjiuwen:agent-core-java:0.1.15"
INTEGRATOR = "maven://io.github.bitlhk.finance:finance-harness-openjiuwen-java:0.1.0-SNAPSHOT"
RUNTIME_SCOPE = {"runtimeId": "openjiuwen-java-0.1.15", "integratorId": INTEGRATOR,
                 "profileId": "local-read-only", "hostId": "fixture-host", "testDataId": "fixture-v1"}


@pytest.fixture
def declared(registered):
    return registered, install_support(registered)[0]


def load(root, declaration, scope=None):
    return load_development_support(root, declaration["subject"], scope or declaration["scope"])


@pytest.mark.parametrize("component,count", [("account.balance.query", 1), ("wealth.facts.describe", 1), ("finance.cashflow.readonly", 2)])
def test_three_existing_components_read_their_own_declarations(registered, component, count):
    result = component_development_support(PROJECT, registered, component, describe_directory(registered)["revision"])
    assert result["status"] == "AVAILABLE" and len(result["methodCases"]) == count
    assert result["sources"] and result["commands"]
    assert result["validation"]["status"] == "NOT_RUN"
    assert result["scope"] == {"capabilityIds": sorted(result["scope"]["capabilityIds"])}


def test_absent_optional_index_on_legacy_harness_needs_no_new_schema(declared):
    root, declaration = declared
    (root / INDEX).unlink()
    for schema in (root / "contracts/json-schema/catalog").glob("*development*.json"):
        schema.unlink()
    result = load(root, declaration)
    assert result["status"] == "NOT_DECLARED" and result["methodCases"] == []
    assert result["message"] == "请补充验证案例"


@pytest.mark.parametrize("key", ["runtimeId", "integratorId", "profileId", "hostId", "testDataId"])
def test_environment_constraints_require_explicit_matching_binding(declared, key):
    root, declaration = declared
    generic = copy.deepcopy(declaration)
    declaration["scope"][key] = "selected-v1"
    write_declarations(root, [declaration])
    assert load(root, declaration, generic["scope"])["status"] == "SCOPE_UNRESOLVED"
    assert load(root, declaration, {**generic["scope"], key: "different"})["status"] == "SCOPE_MISMATCH"
    assert load(root, declaration)["status"] == "AVAILABLE"
    write_declarations(root, [generic, declaration])
    result = load(root, declaration, generic["scope"])
    assert result["methodCases"] == generic["methodCases"] and len(result["sources"]) == 1


def test_conflicting_defaults_rejected_and_identical_defaults_keep_sources(declared):
    root, declaration = declared
    duplicate = copy.deepcopy(declaration)
    write_declarations(root, [declaration, duplicate])
    result = load(root, declaration)
    assert len(result["sources"]) == 2 and len(result["methodCases"]) == 1
    duplicate["methodCases"][0]["query"] = "A conflicting question"
    write_declarations(root, [declaration, duplicate])
    with pytest.raises(ApiError, match="DEVELOPMENT_SUPPORT_CONFLICT"):
        load(root, declaration)


def test_old_subject_digest_and_tampered_declaration_are_rejected(declared):
    root, declaration = declared
    original = copy.deepcopy(declaration)
    declaration["subject"]["digest"] = "sha256:" + "0" * 64
    write_declarations(root, [declaration])
    with pytest.raises(ApiError, match="SUBJECT_DIGEST_MISMATCH"):
        load(root, original)
    refs = write_declarations(root, [original])
    (root / refs[0]["source"].removeprefix("repo://")).write_text("{}")
    with pytest.raises(ApiError, match="DEVELOPMENT_SUPPORT_DIGEST_MISMATCH"):
        load(root, original)


def test_other_version_never_supplies_defaults_and_false_fixed_version_rejected(declared):
    root, declaration = declared
    selected = copy.deepcopy(declaration)
    declaration["subject"]["version"] = "0.0.0"
    write_declarations(root, [declaration])
    assert load(root, selected)["status"] == "NOT_DECLARED"
    with pytest.raises(ApiError, match="SUBJECT_VERSION_MISMATCH"):
        load(root, declaration)


def test_case_refs_keep_original_format_never_convert_expected_behavior(declared):
    root, declaration = declared
    path = root / "smoke.yaml"
    path.write_text("schemaVersion: finance-capability-smoke.v1\ncases:\n- prompt: ask\n  expectedBehavior: secret\n")
    declaration["methodCases"] = []
    declaration["caseRefs"] = [{"source": "repo://smoke.yaml", "format": "finance-capability-smoke.v1", "digest": sha(path.read_bytes())}]
    write_declarations(root, [declaration])
    result = load(root, declaration)
    assert result["caseRefs"] == declaration["caseRefs"] and result["methodCases"] == []
    path.write_text("changed")
    with pytest.raises(ApiError, match="CASE_DIGEST_MISMATCH"):
        load(root, declaration)


@pytest.mark.parametrize("source", ["repo://../outside.json", "repo:///etc/passwd", "https://untrusted.invalid/test", "repo://link.json"])
def test_references_cannot_escape_managed_root(declared, source, tmp_path):
    root, declaration = declared
    outside = tmp_path / "outside.json"
    outside.write_text("private")
    (root / "link.json").symlink_to(outside)
    declaration["caseRefs"] = [{"source": source, "format": "json", "digest": sha(b"private")}]
    write_declarations(root, [declaration])
    with pytest.raises(ApiError):
        load(root, declaration)


def record_for(result):
    runtime = {"family": "openjiuwen", "language": "java", "templateRef": SDK, "integrationRef": INTEGRATOR}
    lock = {"runtime": runtime, "assets": [
        {"kind": "RUNTIME_TEMPLATE", "id": SDK, "version": "0.1.15", "digest": "sha256:" + "a" * 64},
        {"kind": "RUNTIME_INTEGRATION", "id": INTEGRATOR, "version": "0.1.0-SNAPSHOT", "digest": "sha256:" + "b" * 64},
        {**result["subjects"][0], "kind": result["subjects"][0]["kind"].replace("-", "_").upper()}]}
    report = {"schemaVersion": "private-method-trial.v0.1", "status": "PASS", "runtimeVerified": True,
              "trialRuntime": {"family": "openjiuwen", "language": "java"},
              "comparison": {"candidate": {"runtime": runtime, "cases": [
                  {"queryDigest": sha(c["query"].encode()), "loadedMethodObserved": True, "runtimeLock": copy.deepcopy(lock)}
                  for c in result["methodCases"]]}}}
    return {"subject": copy.deepcopy(result["subjects"][0]), "casesDigest": result["casesDigest"],
            "scope": copy.deepcopy(result["scope"]), "executedAt": "2026-09-20T10:00:00Z",
            "reportRef": {"source": "managed-report://test/report.json", "digest": sha(canonical(report))}, "report": report}


@pytest.mark.parametrize("mutation,status", [("version", "VERSION_MISMATCH"), ("digest", "DIGEST_MISMATCH"),
    ("component", "SUBJECT_MISMATCH"), ("environment", "ENVIRONMENT_MISMATCH"), ("runtime", "ENVIRONMENT_MISMATCH"),
    ("cases", "CASE_MISMATCH"), ("platform", "UNSCOPED_REPORT"), ("report_digest", "REPORT_DIGEST_MISMATCH")])
def test_old_or_other_evidence_cannot_claim_component_pass(declared, mutation, status):
    root, declaration = declared
    scope = {**declaration["scope"], **RUNTIME_SCOPE}
    result = load(root, declaration, scope)
    record = record_for(result)
    assert project_verification(result, [record])["status"] == "PASS"
    if mutation == "version": record["subject"]["version"] = "0.0.0"
    if mutation == "digest": record["subject"]["digest"] = "sha256:" + "0" * 64
    if mutation == "component": record["subject"]["id"] = "other"
    if mutation == "environment": record["scope"]["testDataId"] = "other"
    if mutation == "runtime": record["scope"]["runtimeId"] = "other"
    if mutation == "cases": record["casesDigest"] = "sha256:" + "0" * 64
    if mutation == "platform": record["report"] = {"status": "PASS", "testsPassed": 1000, "runtimeVerified": True}
    if mutation == "report_digest": record["reportRef"]["digest"] = "sha256:" + "0" * 64
    assert project_verification(result, [record])["status"] == status


def test_no_runtime_selected_never_claims_report_pass(declared):
    result = load(*declared)
    assert project_verification(result, [record_for(result)])["status"] == "ENVIRONMENT_UNRESOLVED"


@pytest.mark.parametrize("mutation,expected", [("python_scope", "RUNTIME_MISMATCH"), ("sdk", "RUNTIME_MISMATCH"),
    ("integration", "RUNTIME_MISMATCH"), ("family", "RUNTIME_MISMATCH"), ("language", "RUNTIME_MISMATCH"),
    ("no_lock", "EVIDENCE_INCOMPLETE"), ("missing_integrator", "EVIDENCE_INCOMPLETE"),
    ("fake_platform_schema", "EVIDENCE_INCOMPLETE")])
def test_actual_report_runtime_cannot_be_replaced_by_outer_scope(declared, mutation, expected):
    root, declaration = declared
    result = load(root, declaration, {**declaration["scope"], **RUNTIME_SCOPE})
    record = record_for(result)
    report = record["report"]
    candidate = report["comparison"]["candidate"]
    lock = candidate["cases"][0]["runtimeLock"]
    if mutation == "python_scope":
        result["scope"]["runtimeId"] = record["scope"]["runtimeId"] = "openjiuwen-python-0.1.18"
    if mutation == "sdk": lock["assets"][0]["version"] = "0.0.0"
    if mutation == "integration": lock["runtime"]["integrationRef"] = "maven://other:integrator:1.0.0"
    if mutation == "family": report["trialRuntime"]["family"] = "agentscope"
    if mutation == "language": report["trialRuntime"]["language"] = "python"
    if mutation == "no_lock": del candidate["cases"][0]["runtimeLock"]
    if mutation == "missing_integrator": lock["assets"].pop(1)
    if mutation == "fake_platform_schema":
        report.pop("comparison")
        report["testsPassed"] = 10000
    record["reportRef"]["digest"] = sha(canonical(report))
    assert project_verification(result, [record])["status"] == expected


def test_fixed_subject_without_runtime_selection_derives_only_capability_scope(declared):
    root, declaration = declared
    result = load_development_support(root, declaration["subject"])
    assert result["methodCases"] == declaration["methodCases"]
    assert result["scope"] == declaration["scope"]
    assert load_development_support(root, declaration["subject"], {"capabilityIds": []})["methodCases"] == []


def test_one_subject_pass_does_not_validate_a_composite(declared):
    root, declaration = declared
    result = load(root, declaration, {**declaration["scope"], **RUNTIME_SCOPE})
    record = record_for(result)
    result["subjects"].append({**result["subjects"][0], "id": "another-component"})
    assert project_verification(result, [record])["status"] == "EVIDENCE_INCOMPLETE"


@pytest.fixture
def novel(registered, monkeypatch, tmp_path):
    from finance_solution_builder import directory
    spec = json.loads((FIXTURE / "novel-component.json").read_text())
    subject = spec["subject"]
    root = registered / subject["source"].removeprefix("repo://")
    root.mkdir(parents=True)
    (root / "SKILL.md").write_text(spec["content"])
    (root / "pack.yaml").write_text(yaml.safe_dump({"packId": subject["id"], "version": subject["version"],
        "skills": [{"entry": "SKILL.md"}]}))
    subject["digest"] = tree_digest(root)
    snapshot = describe_directory(registered)
    part = copy.deepcopy(next(p for p in snapshot["components"] if p["id"] == "account.balance.query"))
    part.update(id=spec["componentId"], version=subject["version"],
                domainPackRefs=[{k: subject[k] for k in ("id", "version", "source")}])
    snapshot["components"].append(part)
    monkeypatch.setattr(directory, "describe_directory", lambda harness: copy.deepcopy(snapshot))
    declaration = {"schemaVersion": "finance-component-development-support.v0.1", "subject": subject,
        "scope": {"capabilityIds": part["capabilityIds"]}, "methodCases": spec["methodCases"], "caseRefs": [], "commands": []}
    write_declarations(registered, [declaration])
    store = MethodAssets(tmp_path / "store", source_check=lambda v: check_source(PROJECT, registered, v))
    api = MethodApi(SimpleNamespace(project=PROJECT, builder=SimpleNamespace(harness_repo=registered), method_assets=store))
    body = {"componentId": spec["componentId"], "directoryRevision": snapshot["revision"], "name": "Novel fixture"}
    return registered, api, body, declaration


def test_unfamiliar_component_customize_save_validate_and_no_prompt_answer(novel):
    root, api, body, declaration = novel
    _, preview = api.post("/api/v1/assets/customize-preview", body, "alice", None)
    assert preview["data"]["cases"] == declaration["methodCases"]
    assert preview["validation"]["status"] == "VALID"
    validate_binding(root, preview["source"]["binding"])
    assert "caseProvenance" not in preview["source"]["binding"]
    data = {k: v for k, v in preview["data"].items() if k != "cases"}
    _, draft = api.post("/api/v1/assets/customize", {**body, "data": data}, "alice", None)
    assert draft["source"]["caseProvenance"]["sources"]
    path = "/api/v1/assets/" + draft["assetId"]
    saved = api.post(path + "/save", {"revision": 1, "data": draft["data"]}, "alice", None)[1]
    assert api.post(path + "/validate", {"revision": saved["revision"]}, "alice", None)[1]["status"] == "VALID"
    files = runtime_files(draft["assetId"], "0.1.0", draft["data"])
    assert all(b"EVALUATOR_ONLY_SECRET" not in raw for raw in files.values())
    assert api.store.list("bob") == []


@pytest.mark.parametrize("empty", [True, False])
def test_explicit_cases_win_even_when_default_metadata_is_broken(novel, empty):
    root, api, body, declaration = novel
    (root / INDEX).write_text("invalid")
    cases = [] if empty else [{"name": "explicit", "query": "User question", "expectedTerms": []}]
    data = {"name": body["name"], "content": "User method", "capabilityIds": declaration["scope"]["capabilityIds"], "cases": cases}
    _, draft = api.post("/api/v1/assets/customize", {**body, "data": data}, "alice", None)
    assert draft["data"]["cases"] == cases and draft["source"]["caseProvenance"]["kind"] == "USER_PROVIDED"
    assert draft["validation"]["status"] == ("INVALID" if empty else "VALID")


def test_unfamiliar_component_without_declaration_is_editable_not_publishable(novel):
    root, api, body, _ = novel
    (root / INDEX).unlink()
    _, preview = api.post("/api/v1/assets/customize-preview", body, "alice", None)
    assert preview["data"]["cases"] == [] and preview["warnings"][0]["message"] == "请补充验证案例"
    _, draft = api.post("/api/v1/assets/customize", {**body, "data": preview["data"]}, "alice", None)
    with pytest.raises(ApiError, match="ASSET_VALIDATION_REQUIRED"):
        api.store.trial_input("alice", draft["assetId"], draft["revision"])


def test_old_release_keeps_embedded_cases_after_new_catalog_declarations(novel, tmp_path):
    root, api, body, declaration = novel
    _, preview = api.post("/api/v1/assets/customize-preview", body, "alice", None)
    legacy = api.store.create("alice", preview["data"])
    trial = api.store.record_trial("alice", legacy["assetId"], legacy, {"status": "PASS", "runtimeVerified": True})
    release = api.store.publish("alice", legacy["assetId"], 1, "1.0.0", trial["trialId"])
    ref = {k: release[k] for k in ("assetId", "version", "releaseDigest")}
    before = api.store.release("alice", ref)
    declaration["methodCases"][0]["query"] = "Changed defaults"
    write_declarations(root, [declaration])
    assert api.store.release("alice", ref) == before
    assert before["data"]["cases"] != declaration["methodCases"]
